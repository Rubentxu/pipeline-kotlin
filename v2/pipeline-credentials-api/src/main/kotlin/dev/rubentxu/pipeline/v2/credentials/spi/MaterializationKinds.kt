package dev.rubentxu.pipeline.v2.credentials.spi

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.file.Path

/**
 * Kind of materialization for file-based credential types.
 *
 * Design (design §2.3, E-24):
 * - Part of the CredentialMaterialization SPI
 * - Reused from what was previously in :pipeline-credentials-multipart
 */
sealed interface MaterializationKind {
    /** Materialize to a temporary file with 0600 permissions. */
    data object SecretFile : MaterializationKind

    /** Materialize SSH private key to a temporary file with 0600 permissions. */
    data object SshPrivateKey : MaterializationKind

    /** Materialize certificate keystore to a temporary file with 0600 permissions. */
    data object Certificate : MaterializationKind

    /** Materialize ZIP archive by extracting to a temp directory with 0700 permissions. */
    data object Zip : MaterializationKind
}

/**
 * Result of credential materialization.
 * Either provides a path (for file-based kinds) or a SecretHandle (for in-memory kinds).
 *
 * Design (design §2.3, E-24):
 * - Part of the CredentialMaterialization SPI
 * - Reused from what was previously in :pipeline-credentials-multipart
 */
data class MaterializedCredential(
    val credentialsId: CredentialsId,
    val kind: MaterializationKind,
    val path: Path?,
    val handle: SecretHandle?
) : AutoCloseable {

    /**
     * Executes the given block with this materialized credential, then ensures cleanup.
     */
    inline fun <R> use(block: (MaterializedCredential) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }

    override fun close() {
        path?.let { java.nio.file.Files.deleteIfExists(it) }
        handle?.let { /* handle cleanup if needed */ }
    }

    companion object {
        fun fromPath(credentialsId: CredentialsId, kind: MaterializationKind, path: Path): MaterializedCredential {
            return MaterializedCredential(credentialsId, kind, path, null)
        }

        fun fromHandle(credentialsId: CredentialsId, kind: MaterializationKind, handle: SecretHandle): MaterializedCredential {
            return MaterializedCredential(credentialsId, kind, null, handle)
        }
    }
}

/**
 * Thrown when a credential kind cannot be materialized to a file.
 */
class MaterializationKindUnsupportedException(
    val credentialsId: CredentialsId,
    val actualKind: String,
    val supportedKinds: List<MaterializationKind>
) : Exception("Credential '$credentialsId' of kind '$actualKind' is not materializable to a file. Supported: $supportedKinds")
