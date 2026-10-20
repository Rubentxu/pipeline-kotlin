package dev.rubentxu.pipeline.v2.credentials.spi

import dev.rubentxu.pipeline.v2.domain.credentials.Credential

/**
 * SPI port for credential materialization to temporary files.
 *
 * Design (design §2.3, research §4.3-proposed; E-24):
 * - MaterializationKind and MaterializedCredential defined in this module (spi package)
 * - These types were moved from :pipeline-credentials-multipart to resolve
 *   circular dependency while maintaining the port contract
 * - `materialize(credential, kind)`: materializes a credential to temp file/path
 * - `close()`: implements AutoCloseable for lifecycle management
 *
 * This port is implemented by outer adapters (e.g., LocalFileMaterialization)
 * and consumed ONLY by WithCredentialsExecutor. The executor depends on this
 * SPI port, NOT on concrete implementations.
 *
 * ## Design constraints
 * - H0 scope: LocalFileMaterialization only (design §3.2)
 * - CredentialMaterializer signature UNCHANGED (E-7 invariant — adapter delegates)
 * - META-INF/services SPI registration deferred to H1+ (design §7)
 *
 * @see dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer for the H0 implementation
 */
interface CredentialMaterialization : AutoCloseable {

    /**
     * Materializes a credential to a temporary file/path based on its kind.
     *
     * @param credential The credential to materialize (must match kind type)
     * @param kind The materialization kind (must match credential type)
     * @return MaterializedCredential with path or handle
     * @throws MaterializationKindUnsupportedException if kind doesn't match credential type
     */
    fun materialize(credential: Credential, kind: MaterializationKind): MaterializedCredential

    /**
     * Closes this materialization, releasing any tracked resources.
     * Implementations should ensure idempotent close.
     */
    override fun close()
}
