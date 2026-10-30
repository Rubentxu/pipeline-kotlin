package dev.rubentxu.pipeline.v2.domain.step

import kotlin.reflect.KClass

/**
 * WHY a capability could not be handed to the consumer that asked for it.
 *
 * A closed vocabulary, not a sentence. The product's rule is that a fail-closed
 * boundary refuses with something the CONSUMER can switch on: a `String` reason is
 * how a fail-closed boundary degrades into a log line nobody can switch on. The
 * same rule already governs admission ([dev.rubentxu.pipeline.v2.domain.AdmissionRejection]),
 * the workspace ([dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathError]) and
 * the saga ([dev.rubentxu.pipeline.v2.domain.saga.SagaActionError]); a capability
 * that cannot be read is a refusal like any other, so it speaks that language and
 * not the language of exceptions.
 *
 * The two cases are two different FACTS, not two wordings of one:
 * - [Absent] — nothing was provisioned for this key. The repair is upstream: bind
 *   the capability, or do not declare it.
 * - [Incompatible] — something WAS provisioned and it is not what the consumer
 *   declared. The repair is at the binding: the provider and the consumer disagree
 *   about the shape of the seam, and no amount of retrying makes them agree.
 */
sealed interface CapabilityRefusal {

    /** The capability the consumer asked for. */
    val key: StepCapability

    /**
     * The capability is NOT part of this invocation's provision.
     *
     * The wording is the one the bridge has always used, byte for byte, because
     * this refusal reaches the operator through the same diagnostic as before and
     * a consumer that parses it must keep working.
     */
    data class Absent(override val key: StepCapability) : CapabilityRefusal {
        override fun describe(): String = "capability unavailable to this invocation: $key"
    }

    /**
     * The capability IS provisioned, but the provisioned value is not of the type
     * the consumer declared.
     *
     * This case did not exist as a refusal before: the value travelled as `Any`,
     * the consumer cast it without checking, and a mismatch surfaced as a
     * `ClassCastException` in the CONSUMER's frame — a fact about the consumer's
     * bytecode, reported against the wrong owner. Here the seam that provided the
     * value and the consumer that declared the type are both named, so the
     * diagnostic points at the disagreement instead of at the frame that noticed it.
     */
    data class Incompatible(
        override val key: StepCapability,
        val expected: KClass<*>,
        val actual: KClass<*>,
    ) : CapabilityRefusal {
        override fun describe(): String =
            "capability $key is not of the declared type: expected ${nameOf(expected)}, found ${nameOf(actual)}"
    }

    /** The refusal in one line, for the diagnostic that carries it. */
    fun describe(): String

    companion object {
        /**
         * A readable name for a class, never a classpath fragment.
         * `KClass.simpleName` is null for an anonymous class, and the fallback
         * would otherwise be the qualified name — a classpath fragment in a
         * message an operator reads.
         */
        private fun nameOf(type: KClass<*>): String = type.simpleName ?: "an unnamed type"
    }
}

/**
 * The refusal, carried where the product already refuses an unusable capability.
 *
 * It replaces the bare [IllegalArgumentException] the bridge used to throw, and
 * it replaces it with something that carries the REASON: the exception type says
 * that a capability could not be handed over, and the [refusal] says which of the
 * two facts it was. A consumer that only wants to know "did I get my capability"
 * catches the type; a consumer that wants to know "was it missing or was it the
 * wrong shape" reads the refusal, and can switch on it.
 */
class CapabilityRefusedException(
    val refusal: CapabilityRefusal,
) : RuntimeException(refusal.describe())
