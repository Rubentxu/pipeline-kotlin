package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * What a value that a binding exposes MEANS for masking.
 *
 * ## Why this is a type and not a boolean
 *
 * The registry answers one question with a yes/no: "is this value long enough to be masked?".
 * That answer is enough to DECIDE whether to register a pattern, and not enough to
 * tell the operator what is going on. Three different facts collapse into that one
 * boolean today:
 *
 *  - the value is a secret that is too short to mask — it reaches the transcript IN THE CLEAR;
 *  - there is no value at all — nothing can reach the transcript, because nothing is there;
 *  - the value is not a secret — a PATH, a reference, something the operator must read.
 *
 * The second one is the dangerous collapse: a value that is NOT THERE is reported as
 * "a secret of 0 characters ... its value will appear in the transcript IN THE CLEAR",
 * which is a fact about a secret that does not exist. An operator who reads that
 * learns that a credential leaked when nothing leaked, and stops reading the reports
 * that matter.
 *
 * ## The threshold is not re-implemented here
 *
 * [SecretPatternRegistry.MIN_MASKABLE_LENGTH] is the ONE threshold, and the registry
 * stays the authority on it. This policy asks the registry's constant; it does not
 * carry a second copy of the number, and it does not decide what the registry masks.
 * The registry still decides whether a pattern is built; this type only says what
 * the value IS.
 */
sealed interface SecretMaskingOutcome {

    /**
     * The value is a secret and it is long enough to be masked.
     *
     * It is registered with the redaction registry, so it is scrubbed before it
     * reaches any observable or durable surface.
     */
    data object Masked : SecretMaskingOutcome

    /**
     * The value IS a secret and it is too short to be masked.
     *
     * The value is real and it will reach the transcript IN THE CLEAR. The length
     * travels with the outcome so the operator can be told how short it is without
     * the value ever being named.
     */
    data class TooShortToMask(val length: Int) : SecretMaskingOutcome

    /**
     * There is no value.
     *
     * Nothing is there, so there is nothing to mask and nothing to leak. This is NOT
     * the same fact as [TooShortToMask], and it is never reported: a report about a
     * secret that does not exist is a lie about the run.
     */
    data object Absent : SecretMaskingOutcome

    /**
     * The value is not a secret.
     *
     * A PATH, a reference, a user name the binding needs in clear. It is never
     * registered for masking — redacting it would break the binding — and it is
     * never reported: there is nothing secret about it to report.
     */
    data object NotASecret : SecretMaskingOutcome
}

/**
 * A secret that exists and cannot be masked.
 *
 * Carries the credential and the length, NEVER the value: the whole point of the
 * report is to name the credential that is exposed without repeating the exposure.
 *
 * ## Why this is not the published warning
 *
 * [dev.rubentxu.pipeline.v2.credentials.api.SecretShorterThanMinimumMaskableLengthWarning]
 * carries the same two facts, and it is the ADVISORY report: it says "here is a secret
 * below the floor, the run continues". This is the REFUSAL's payload: it says "here is a
 * secret below the floor, the binding is not used". Two facts, one report each, and the
 * published warning keeps its shape because it is a published contract.
 *
 * @property credentialsId the credential whose secret cannot be masked.
 * @property length how many characters the value has.
 */
data class UnmaskableSecret(
    val credentialsId: CredentialsId,
    val length: Int,
)

/**
 * How strict the policy is about a value it cannot mask.
 *
 * ## The two postures
 *
 *  - [ADVISORY] is what the product has always done: the value is not masked, the
 *    operator is told, and the run continues. The threshold governs MASKING, not
 *    VALIDITY — a short password is a weak password, not a malformed one.
 *  - [STRICT] refuses the binding before it is used. It exists for the runs whose
 *    output is DURABLE: there, an unmasked secret is not a warning on a console,
 *    it is a secret written to disk that nobody can take back.
 *
 * ## What strictness does NOT change
 *
 * The threshold. A value of seven characters is too short to mask under both
 * postures; strictness decides whether the run may proceed with that fact, not
 * whether the fact exists.
 */
enum class SecretMaskingStrictness {

    /** Report the value that cannot be masked and continue. The legacy posture. */
    ADVISORY,

    /**
     * Refuse the binding that exposes a value that cannot be masked.
     *
     * The refusal happens BEFORE the binding is used: the step never runs, so the
     * value never reaches a transcript, a console or a disk.
     */
    STRICT,
    ;

    /**
     * The environment variable that carries the operator's decision.
     *
     * The variable is read where the credential seam is composed, and it can move
     * the posture in either direction: a run that wants the strict posture in a
     * branch that does not default to it can ask for it, and a run that cannot live
     * with the strict posture can give it up.
     */
    companion object {

        /** The name of the variable the operator sets to choose the posture. */
        const val ENVIRONMENT_VARIABLE: String = "PIPELINE_CREDENTIALS_MASKING"

        /** The value that asks for the strict posture. */
        const val REQUIRED: String = "required"

        /** The value that accepts a value that cannot be masked. */
        const val BEST_EFFORT: String = "best-effort"

        /**
         * The posture this strictness asks for, given what the operator declared.
         *
         * A variable that names a posture this product does not have is a decision
         * the product cannot honour, and honouring it any other way would be
         * guessing. So the answer is [null] and the caller refuses the run.
         *
         * @param declared what the operator wrote, or `null` when they wrote nothing.
         * @return the posture to apply, or `null` when the declaration cannot be read.
         */
        fun fromDeclaration(declared: String?): SecretMaskingStrictness? = when (declared?.trim()?.lowercase()) {
            null, "" -> null
            REQUIRED -> STRICT
            BEST_EFFORT -> ADVISORY
            else -> null
        }
    }
}

/**
 * The decision, in one place.
 *
 * A pure function over two facts: the value, and whether the value is a secret at
 * all. It answers what the value MEANS; it does not register anything and it does
 * not report anything.
 *
 * @param value the value the binding exposed, or `null` when there is none.
 * @param isSecret whether the value is a secret rather than a reference such as a PATH.
 * @return what the value means for masking.
 */
fun classifySecretValue(value: String?, isSecret: Boolean): SecretMaskingOutcome = when {
    !isSecret -> SecretMaskingOutcome.NotASecret
    value.isNullOrEmpty() -> SecretMaskingOutcome.Absent
    value.length < SecretPatternRegistry.MIN_MASKABLE_LENGTH ->
        SecretMaskingOutcome.TooShortToMask(value.length)
    else -> SecretMaskingOutcome.Masked
}

/**
 * The refusal of a binding that would expose a secret the product cannot mask.
 *
 * Names every credential involved and the length of each value, and NEVER the
 * value. The message also names the variable that lets the operator accept the
 * fact on purpose: a refusal the operator cannot act on is a wall, and a wall
 * without a door is a bug report waiting to happen.
 *
 * @property unmaskable the values that cannot be masked, one per offending binding.
 * @property minimumLength the length a value needs to be masked.
 */
class SecretMaskingRefusedException(
    val unmaskable: List<UnmaskableSecret>,
    val minimumLength: Int = SecretPatternRegistry.MIN_MASKABLE_LENGTH,
) : IllegalStateException(
    buildMessage(unmaskable, minimumLength),
) {

    private companion object {

        /**
         * The refusal, said once.
         *
         * Every offending credential is named: refusing the first of three and
         * leaving the operator to find the other two by hand is a refusal that
         * costs a second round trip.
         */
        fun buildMessage(unmaskable: List<UnmaskableSecret>, minimumLength: Int): String {
            val named = unmaskable.joinToString(", ") { "${it.credentialsId.value} (${it.length} characters)" }
            return "the credential(s) $named bind a value shorter than the minimum maskable length " +
                "($minimumLength): the value would reach the durable output IN THE CLEAR, so the " +
                "binding is refused before it is used. Set " +
                "${SecretMaskingStrictness.ENVIRONMENT_VARIABLE}=" +
                "${SecretMaskingStrictness.BEST_EFFORT} to accept a value that cannot be masked."
        }
    }
}
