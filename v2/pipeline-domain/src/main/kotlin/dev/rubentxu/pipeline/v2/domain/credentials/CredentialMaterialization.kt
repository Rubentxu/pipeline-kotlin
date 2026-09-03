package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.file.Path

/**
 * LF-0403 — Domain-level port for materializing file-based credentials.
 *
 * This port lives in `:pipeline-domain` so that
 * [DefaultCredentialProjector] can compose against it without dragging in
 * the `:pipeline-credentials-api` SPI module. The existing SPI port
 * (`:pipeline-credentials-api/spi/CredentialMaterialization`) is kept for
 * backward compatibility with [dev.rubentxu.pipeline.v2.credentials.spi.MaterializedCredential]
 * carry-types used by `:pipeline-credentials-multipart`; it extends this domain
 * port by subtyping.
 *
 * The contract is single-method:
 *  - [materialize]: turn a [Credential] into a [MaterializedCredentialDomain]
 *    (a path + a closeable). The default implementation in
 *    `:pipeline-credentials-multipart` writes the bytes to a temp file with
 *    `0600` perms and tracks it for [close]-time wipe.
 *
 * The port is intentionally narrower than the SPI one (it strips out
 * the [MaterializationKind] parameter) — the kind is statically derivable
 * from the [Credential] subtype by the projector.
 */
interface CredentialMaterializationDomain : AutoCloseable {

    /**
     * Materializes a credential to a file path on disk with proper permissions.
     *
     * @param credential The credential to materialize. Must be one of
     *   [SecretFile], [SshPrivateKey], [Certificate], or [Zip].
     * @return A [MaterializedCredentialDomain] carrying the path and a
     *   closeable for cleanup.
     * @throws IllegalArgumentException if the credential type is not materializable.
     */
    fun materialize(credential: Credential): MaterializedCredentialDomain

    /**
     * Closes this materialization, wiping any tracked temp files / directories.
     */
    override fun close()
}

/**
 * Result of [CredentialMaterializationDomain.materialize].
 *
 * - [path]: the temp file / directory on disk (or `null` if only an in-memory
 *   handle is returned)
 * - [handle]: the in-memory handle for non-file payloads (or `null`)
 *
 * The result implements [AutoCloseable] so it can be composed with `use{}`
 * blocks at the call site.
 */
data class MaterializedCredentialDomain(
    val path: Path?,
    val handle: SecretHandle?,
) : AutoCloseable {
    override fun close() {
        path?.let { p ->
            if (java.nio.file.Files.isDirectory(p)) {
                // Walk bottom-up to delete a (possibly nested) directory tree.
                java.nio.file.Files.walk(p).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { child ->
                        runCatching { java.nio.file.Files.deleteIfExists(child) }
                    }
                }
            } else {
                runCatching { java.nio.file.Files.deleteIfExists(p) }
            }
        }
        handle?.close()
    }
}
