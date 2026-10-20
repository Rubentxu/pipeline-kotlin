package dev.rubentxu.pipeline.v2.domain

/**
 * Tagged union for environment variable values with secret-tracking semantics.
 *
 * [Plain] carries a visible string value that may be used directly in a process
 * environment. [Secret] carries an opaque [SecretRef] that must be resolved
 * via a credential provider before the value can be used.
 *
 * This sealed type makes the distinction between plain-text and secret-bearing
 * environment values explicit at the type level, preventing accidental secret
 * materialisation in logs or error messages.
 *
 * @see SecretRef for the opaque credential identifier type
 */
sealed interface EnvValue {

    /**
     * A plain-text environment variable value.
     *
     * The [value] is safe to include in process environment maps, logs,
     * or error messages without redaction.
     */
    data class Plain(val value: String) : EnvValue

    /**
     * A secret-bearing environment variable value.
     *
     * The [ref] is an opaque identifier that must be resolved via the
     * Slice 1 typed channel: either
     * [dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider.resolve]
     * (returns [dev.rubentxu.pipeline.v2.domain.SecretHandle] directly),
     * or
     * [dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor.bind]
     * (returns [dev.rubentxu.pipeline.v2.credentials.executor.BoundCredentials]
     * whose [dev.rubentxu.pipeline.v2.credentials.executor.BoundCredentials.env]
     * accessor returns `Map<String, SecretHandle>`).
     *
     * The resolved [dev.rubentxu.pipeline.v2.domain.SecretHandle] provides
     * the secret bytes through a `borrow { bytes -> ... }` channel — the
     * raw secret string must never appear in a plain-text environment
     * context.
     */
    data class Secret(val ref: SecretRef) : EnvValue
}

/**
 * Type alias for a credential identifier used as a secret reference in [EnvValue.Secret].
 *
 * This is an alias for [CredentialsId] to make the intent explicit: a [SecretRef]
 * is used only to reference a secret stored in the [dev.rubentxu.pipeline.v2.credentials.api.SecretStore],
 * never to carry the secret value itself.
 *
 * The alias lives alongside [dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec]
 * and [dev.rubentxu.pipeline.v2.domain.SecretHandleRef] in the domain layer,
 * each representing a different semantic role for credential identifiers.
 *
 * @see CredentialsId for the underlying value class
 * @see EnvValue.Secret for the environment value that carries a secret reference
 */
typealias SecretRef = CredentialsId
