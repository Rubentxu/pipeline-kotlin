package dev.rubentxu.pipeline.v2.domain

import kotlinx.serialization.Serializable

import org.jetbrains.annotations.NotNull

/**
 * Value class wrapper for a credentials identifier.
 * The ID itself is NOT a secret - it is a public identifier that safely
 * references credentials stored in the `SecretStore`.
 * This is structural redaction: credentials cannot leak through the ID type alone
 * ## Structural Redaction Role
 * [CredentialsId] is the ONLY public reference type for credentials.
 * No secret bytes ever cross the boundary when only an ID is passed.
 * The typed carrier pattern ensures at compile time that secret values
 * cannot be accidentally substituted for IDs.
 * ## Usage
 * ```
 * val credentialsId = CredentialsId("github-deploy-key")
 * val ref = CredentialsRef(credentialsId)
 * ```
 * @see CredentialsRef for the boundary carrier type
 * @see SecretHandle for the secret value channel (never the ID)
*/
@JvmInline
@Serializable
value class CredentialsId(@NotNull val value: String) {
    init {
        require(value.isNotBlank()) { "CredentialsId value must not be blank" }
    }

    /**
     * The factory that turns text into an identifier.
     *
     * [from] is the way to build one from a value that came from outside,
     * such as a string the user typed. It refuses a blank value exactly as
     * the constructor does, so both routes agree on what an identifier is.
     */
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
