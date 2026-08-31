package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * Local credential provider adapter — wraps [LocalSecretStore] to implement [CredentialProvider] SPI.
 *
 * Design (design §3.1, E-25; backlog L-126 "wrapping LocalSecretStore"):
 * - Implements [CredentialProvider] SPI port
 * - Wraps [LocalSecretStore] and delegates [resolve] to [SecretStore.getAsSecretHandle]
 * - [close] delegates to [SecretStore.close] — LocalSecretStore manages its own lifecycle
 *
 * This is the H0 adapter for credential resolution. The executor depends ONLY on the
 * [CredentialProvider] SPI port, NOT on this concrete implementation.
 *
 * @param store The underlying secret store to delegate to
 */
class LocalCredentialProvider(
    private val store: SecretStore
) : CredentialProvider {

    /**
     * Unique identifier for this provider: "local"
     */
    override val providerId: String = "local"

    /**
     * Resolves a credential by ID, returning a [SecretHandle].
     *
     * Delegates directly to [SecretStore.getAsSecretHandle].
     *
     * @param id The credential identifier
     * @return [SecretHandle] wrapping the secret bytes
     * @throws dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException if not found or tampered
     */
    override fun resolve(id: CredentialsId): SecretHandle {
        return store.getAsSecretHandle(id)
    }

    /**
     * Closes this provider by closing the underlying store.
     * Idempotent — [SecretStore.close] is idempotent.
     */
    override fun close() {
        store.close()
    }
}
