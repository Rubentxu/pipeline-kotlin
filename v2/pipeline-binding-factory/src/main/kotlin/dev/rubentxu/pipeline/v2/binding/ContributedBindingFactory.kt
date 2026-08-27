package dev.rubentxu.pipeline.v2.binding

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * SPI interface for contributing binding resolvers.
 *
 * Implementations are loaded via `META-INF/services` (Java SPI discovery).
 * Each implementation handles specific [CredentialsBinding] kinds and resolves
 * them to environment variable entries.
 *
 * ## Design (INV-L6-CR-010)
 *
 * Parallel multi-binding isolation: each [ContributedBindingFactory] is invoked
 * independently for its supported kinds. Failures in one factory MUST NOT affect
 * other factories (isolation boundary).
 *
 * ## Fail-Fast Semantics
 *
 * [MultiBindingWithCredentials] enforces partial-failure = nothing injected:
 * if ANY binding fails resolution, NO environment variables are injected.
 * This is achieved by resolving ALL bindings first, then committing NONE if
 * any resolution fails.
 *
 * @see MultiBindingWithCredentials for the orchestrating resolver
 */
interface ContributedBindingFactory {

    /**
     * Returns the set of binding kinds this factory can handle.
     *
     * @return Set of kind strings (e.g., "string", "usernamePassword", "sshUserPrivateKey")
     */
    fun supportedKinds(): Set<String>

    /**
     * Resolves a [CredentialsBinding] to environment variable entries.
     *
     * @param binding The binding to resolve
     * @param credentialResolver Function that resolves a [CredentialsId] to a [SecretHandle]
     * @return List of environment variable entries, or null if this factory cannot handle the binding
     * @throws BindingResolutionException if resolution fails for this binding
     */
    fun resolve(
        binding: CredentialsBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<EnvEntry>

    /**
     * Environment variable entry produced by a binding resolver.
     *
     * @param name Environment variable name
     * @param handle Secret handle containing the value (never exposed directly)
     */
    data class EnvEntry(
        val name: String,
        val handle: SecretHandle
    )
}

/**
 * Exception thrown when binding resolution fails.
 *
 * Fail-fast semantics: this exception causes [MultiBindingWithCredentials]
 * to abort and NOT inject ANY environment variables.
 */
class BindingResolutionException(
    val binding: CredentialsBinding,
    message: String,
    cause: Throwable? = null
) : Exception("Failed to resolve binding for '${binding.credentialsId.value}': $message", cause)

/**
 * Thrown when no factory can handle a given binding kind.
 */
class UnsupportedBindingKindException(
    val kind: String,
    val supportedKinds: Set<String>
) : Exception("No factory registered for binding kind '$kind'. Supported kinds: $supportedKinds")
