package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretShorterThanMinimumMaskableLengthWarning
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePasswordBindingSpec

/**
 * What a binding exposes to the run, and what the redaction registry is told about it.
 *
 * ## Why this is a seam and not a line inside the executor
 *
 * Two facts are decided here and nowhere else: WHICH values become maskable, and WHICH
 * credential a value that cannot be masked belongs to. The second one needs the binding, and
 * the binding is known while the binding is being projected — a registry handed every bound
 * value at the end of the run would hold values whose credential nobody could name.
 *
 * ## The short secret
 *
 * A value shorter than [SecretPatternRegistry.MIN_MASKABLE_LENGTH] is not registered for
 * masking: a literal that short collides with ordinary output, and a redactor that scrubs
 * four characters everywhere destroys more observability than it protects. The credential
 * still works — the threshold governs MASKING, not VALIDITY — but its value reaches the
 * transcript IN THE CLEAR. An operator who is not told that reads a transcript and concludes
 * that nothing was masked, which is the one conclusion the transcript cannot support.
 *
 * So the binding REPORTS it: the credential id and the length, never the value.
 *
 * ## The composed value of a username and a password
 *
 * A username/password binding exposes TWO variables, and the two halves are registered
 * separately. The value those two halves make TOGETHER — `user:pass` — is a value the world
 * also knows how to write: a script that pipes it to `base64` produces a string no redaction
 * rule matches, because no rule ever held `user:pass`. The composed value is therefore
 * registered as well, and the registry's own variant generation covers its base64 forms.
 *
 * ## What this does NOT do
 *
 * It does not change WHAT is masked: a value below the threshold is still not masked, and the
 * composed value goes through the same threshold as every other value. One threshold, one
 * authority.
 */
internal object BoundSecretRegistration {

    /**
     * Register the values ONE binding exposed, and report the ones that cannot be masked.
     *
     * Every value the binding exposed is CLASSIFIED first, and the classification decides
     * what happens to it:
     *
     *  - a value that is long enough is registered, so the registry scrubs it;
     *  - a value that is too short is NOT registered and IS reported, and the report says
     *    which credential it belongs to and how short it is, never what it is;
     *  - a value that is NOT THERE is neither registered nor reported: there is no value
     *    that could reach the transcript, and a report about a secret that does not exist
     *    teaches the operator to ignore the reports that matter;
     *  - a value that is not a secret (a PATH) is neither registered nor reported.
     *
     * The THRESHOLD is the registry's, and the registry stays the authority on it: this
     * seam asks [classifySecretValue], which asks the registry's own constant, and it
     * never carries a second copy of the number.
     *
     * The REGISTRY may be absent, and the classification does not depend on it. A run with
     * no registry masks NOTHING, so every value it binds reaches the transcript in the
     * clear; the value that is too short to be masked is still too short, and the report
     * about it is still true. Deciding the policy only when a registry happens to be wired
     * would make the guarantee depend on the wiring instead of on the value.
     *
     * @param registry where the maskable values are registered, or `null` when the run
     *   masks nothing at all.
     * @param spec the binding being registered. It names the credential the values belong to.
     * @param bindings the values the binding exposed, by environment variable name.
     * @param warn where the report about a value that cannot be masked goes.
     * @return the values that exist and cannot be masked, one per offending value.
     */
    fun register(
        registry: SecretPatternRegistry?,
        spec: CredentialBindingSpec,
        bindings: Map<String, SecretHandle>,
        warn: (SecretShorterThanMinimumMaskableLengthWarning) -> Unit,
    ): List<UnmaskableSecret> {
        val unmaskable = mutableListOf<UnmaskableSecret>()

        for (handle in bindings.values) {
            // A MASKED handle carries a PATH or a reference, not the value: redacting it
            // would break the binding, and there is nothing secret in it to redact. The
            // classification is what says so, so the skip is not a rule of its own.
            val value = handle.peek { bytes -> String(bytes, Charsets.UTF_8) }
            val outcome = classifySecretValue(value, isSecret = !handle.isMasked)
            when (outcome) {
                is SecretMaskingOutcome.Masked -> registry?.addSecretValue(value)
                is SecretMaskingOutcome.TooShortToMask -> {
                    unmaskable += UnmaskableSecret(spec.credentialsId, outcome.length)
                    warn(
                        SecretShorterThanMinimumMaskableLengthWarning(
                            credentialsId = spec.credentialsId.value,
                            length = outcome.length,
                        ),
                    )
                }
                SecretMaskingOutcome.Absent, SecretMaskingOutcome.NotASecret -> Unit
            }
        }

        composedUsernamePassword(spec, bindings)?.let { composed ->
            // The composed value is a value the world knows how to write, so it is masked on
            // its own account and not only as part of its two halves.
            when (val outcome = classifySecretValue(composed, isSecret = true)) {
                is SecretMaskingOutcome.Masked -> registry?.addSecretValue(composed)
                is SecretMaskingOutcome.TooShortToMask -> {
                    unmaskable += UnmaskableSecret(spec.credentialsId, outcome.length)
                    warn(
                        SecretShorterThanMinimumMaskableLengthWarning(
                            credentialsId = spec.credentialsId.value,
                            length = outcome.length,
                        ),
                    )
                }
                SecretMaskingOutcome.Absent, SecretMaskingOutcome.NotASecret -> Unit
            }
        }

        return unmaskable
    }

    /**
     * The value a username and a password make together, or `null` when this binding is not
     * one of those.
     *
     * The two halves are read through [SecretHandle.peek], which observes without consuming:
     * the binding still owns them and the pipeline still needs them.
     */
    private fun composedUsernamePassword(
        spec: CredentialBindingSpec,
        bindings: Map<String, SecretHandle>,
    ): String? {
        if (spec !is UsernamePasswordBindingSpec) return null
        val username = valueOf(bindings[spec.usernameVariable]) ?: return null
        val password = valueOf(bindings[spec.passwordVariable]) ?: return null
        return "$username:$password"
    }

    private fun valueOf(handle: SecretHandle?): String? =
        handle?.takeIf { !it.isMasked }?.peek { bytes -> String(bytes, Charsets.UTF_8) }
}
