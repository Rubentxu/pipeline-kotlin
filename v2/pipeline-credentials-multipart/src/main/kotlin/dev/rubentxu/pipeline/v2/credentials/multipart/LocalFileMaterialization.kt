package dev.rubentxu.pipeline.v2.credentials.multipart

import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializedCredential
import dev.rubentxu.pipeline.v2.domain.credentials.Credential

/**
 * Local file materialization adapter — wraps [CredentialMaterializer] to implement [CredentialMaterialization] SPI.
 *
 * Design (design §3.2, E-7; backlog L-126 "extracted from CredentialMaterializer"):
 * - Implements [CredentialMaterialization] SPI port
 * - Wraps [CredentialMaterializer] and delegates [materialize] to its corresponding method
 * - [close] delegates to [CredentialMaterializer.close]
 *
 * This adapter is a thin wrapper — [CredentialMaterializer] signature is UNCHANGED (E-7 invariant).
 * The adapter delegates, does not modify.
 *
 * @param materializer The underlying materializer to delegate to
 */
class LocalFileMaterialization(
    private val materializer: CredentialMaterializer
) : CredentialMaterialization {

    /**
     * Materializes a credential to a temporary file/path based on its kind.
     *
     * Delegates directly to [CredentialMaterializer.materialize].
     *
     * @param credential The credential to materialize
     * @param kind The materialization kind (must match credential type)
     * @return [MaterializedCredential] with path or handle
     * @throws MaterializationKindUnsupportedException if kind doesn't match credential type
     */
    override fun materialize(credential: Credential, kind: MaterializationKind): MaterializedCredential {
        return materializer.materialize(credential, kind)
    }

    /**
     * Closes this materialization by closing the underlying materializer.
     * Idempotent — [CredentialMaterializer.close] handles idempotency.
     */
    override fun close() {
        materializer.close()
    }
}
