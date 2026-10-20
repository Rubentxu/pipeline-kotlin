package dev.rubentxu.pipeline.v2.credentials.spi

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential

/**
 * SPI port for credential resolution — ISP-optimal single-responsibility interface.
 *
 * Design (design §2.1, research §4.1-proposed; backlog L-126):
 * - `providerId`: identifies the provider for audit/debugging
 * - `resolve(id)`: retrieves a credential as SecretHandle (typed channel)
 * - `close()`: implements AutoCloseable for lifecycle management
 *
 * This port is implemented by outer adapters (e.g., LocalCredentialProvider)
 * and consumed ONLY by WithCredentialsExecutor. The executor depends on this
 * SPI port, NOT on concrete implementations.
 *
 * ## Design constraints
 * - H0 scope: LocalCredentialProvider only (design §3.1)
 * - META-INF/services SPI registration deferred to H1+ (design §7)
 * - Composition-root constructs concrete directly (design §4.2)
 *
 * @see dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider for the H0 implementation
 */
interface CredentialProvider : AutoCloseable {

    /**
     * Unique identifier for this provider.
     * Used for audit trail and debugging.
     */
    val providerId: String

    /**
     * Resolves a credential by ID, returning a SecretHandle.
     *
     * @param id The credential identifier (NOT secret material)
     * @return SecretHandle wrapping the secret bytes
     * @throws dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException if not found or tampered
     */
    fun resolve(id: CredentialsId): SecretHandle

    /**
     * Resolves a credential by ID, returning the full typed Credential.
     *
     * Required for materialization of file-based credentials (SSH key, certificate, ZIP).
     * The Credential type carries the typed payload needed for file creation.
     *
     * @param id The credential identifier
     * @return Credential typed credential (NOT a handle)
     * @throws dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException if not found or tampered
     */
    fun resolveToCredential(id: CredentialsId): Credential

    /**
     * Closes this provider, releasing any resources.
     * Implementations should ensure idempotent close.
     */
    override fun close()
}
