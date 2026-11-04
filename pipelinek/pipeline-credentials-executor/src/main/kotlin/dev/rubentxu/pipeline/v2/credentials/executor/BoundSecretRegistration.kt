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
     * @param registry where the maskable values are registered.
     * @param spec the binding being registered. It names the credential the values belong to.
     * @param bindings the values the binding exposed, by environment variable name.
     * @param warn where the report about a value that cannot be masked goes.
     */
    fun register(
        registry: SecretPatternRegistry,
        spec: CredentialBindingSpec,
        bindings: Map<String, SecretHandle>,
        warn: (SecretShorterThanMinimumMaskableLengthWarning) -> Unit,
    ) {
        for (handle in bindings.values) {
            // A MASKED handle carries a PATH or a reference, not the value: redacting it
            // would break the binding, and there is nothing secret in it to redact.
            if (handle.isMasked) continue
            val value = handle.peek { bytes -> String(bytes, Charsets.UTF_8) }
            registry.addSecretValue(value)
            if (value.length < SecretPatternRegistry.MIN_MASKABLE_LENGTH) {
                warn(
                    SecretShorterThanMinimumMaskableLengthWarning(
                        credentialsId = spec.credentialsId.value,
                        length = value.length,
                    ),
                )
            }
        }

        composedUsernamePassword(spec, bindings)?.let { registry.addSecretValue(it) }
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
