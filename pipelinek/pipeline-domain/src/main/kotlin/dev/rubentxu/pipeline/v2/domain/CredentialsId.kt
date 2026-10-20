package dev.rubentxu.pipeline.v2.domain

import org.jetbrains.annotations.NotNull

/**
 * Value class wrapper for a credentials identifier.
 *
 * The ID itself is NOT a secret - it is a public identifier that safely
 * references credentials stored in the [SecretStore][dev.rubentxu.pipeline.v2.credentials.api.SecretStore].
 * This is L1 structural redaction: credentials cannot leak through the ID type alone.
 *
 * ## L1 Structural Redaction Role
 *
 * [CredentialsId] is the ONLY public reference type for credentials.
 * No secret bytes ever cross the boundary when only an ID is passed.
 * The typed carrier pattern ensures at compile time that secret values
 * cannot be accidentally substituted for IDs.
 *
 * ## Usage
 *
 * ```
 * val credentialsId = CredentialsId("github-deploy-key")
 * val ref = CredentialsRef(credentialsId)
 * ```
 *
 * @see CredentialsRef for the boundary carrier type
 * @see SecretHandle for the secret value channel (never the ID)
 */
@JvmInline
value class CredentialsId(@NotNull val value: String) {
    init {
        require(value.isNotBlank()) { "CredentialsId value must not be blank" }
    }

    companion object {
        /**
         * Factory for creating a CredentialsId from a string value.
         * Use this instead of the constructor when the ID comes from
         * external input (e.g., user-provided string).
         */
        fun from(value: String): CredentialsId {
            return CredentialsId(value)
        }
    }
}
