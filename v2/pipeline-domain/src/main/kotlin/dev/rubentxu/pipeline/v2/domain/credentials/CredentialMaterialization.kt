package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.file.Path

/**
 * Domain-level port for materializing file-based credentials.
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
 * blocks at the call site. The class is [open] so test doubles can override
 * [close] to simulate wipe failures without needing root privileges.
 *
 * [close] is NOT silent. A delete that fails is reported: the first failure is thrown with the
 * rest suppressed, so [ProjectionResult.close] can build the [ProjectionResult.WipeException] that
 * names the orphan paths. See [wipeMaterialization] for why that reporting used to be missing and
 * what it cost.
 *
 * NOTE: intentionally not a `data class` because `open` and `data` are
 * incompatible modifiers in Kotlin. [equals] and [hashCode] are implemented
 * explicitly to preserve value semantics.
 */
open class MaterializedCredentialDomain(
    val path: Path?,
    val handle: SecretHandle?,
) : AutoCloseable {
    override fun equals(other: Any?): Boolean = other is MaterializedCredentialDomain &&
        other.path == this.path && other.handle == this.handle

    override fun hashCode(): Int {
        var result = path?.hashCode() ?: 0
        result = 31 * result + (handle?.hashCode() ?: 0)
        return result
    }

    override fun close() {
        // The decision lives in [wipeMaterialization] so it can be pinned without a filesystem;
        // the three lambdas below are the only I/O, and they are what this class owns.
        val failures = wipeMaterialization(
            path = path,
            handle = handle,
            isDirectory = { java.nio.file.Files.isDirectory(it) },
            childrenOf = { root ->
                java.nio.file.Files.walk(root).use { walk ->
                    walk.iterator().asSequence().toList()
                }
            },
            delete = { java.nio.file.Files.deleteIfExists(it) },
        )

        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }
}

/**
 * The wipe decision behind [MaterializedCredentialDomain.close], with every filesystem operation
 * injected so it can be pinned by a unit test (AGENTS.md section 2 forbids touching a disk).
 *
 * ## Why this exists at all
 *
 * `close()` used to wrap each delete in `runCatching { }` and return. The consequence was not a
 * slow leak but a silent one with a typed shape: [ProjectionResult.close] accumulates a
 * `WipeException` naming every orphan path, and with a `close` that cannot throw, that exception
 * was never constructed. The materialised bytes stayed on disk and the pipeline reported success.
 * Every test double in the tree had to *override* `close` to produce a wipe failure, which is the
 * tell — the failure mode existed only where somebody had been able to manufacture it.
 *
 * ## The rules
 *
 *  - A failing delete does NOT abort the remaining ones. A directory whose second entry is locked
 *    must still lose its other entries; abandoning the walk half-done is strictly worse.
 *  - Every failure is recorded, not just the first. [MaterializedCredentialDomain] has one path,
 *    but a directory has many, and an operator can only act on the ones they are told about.
 *  - The handle is closed even when a path delete failed. A throwable from the delete must not
 *    cost the in-memory secret its wipe.
 *
 * @return the failures in occurrence order; empty when everything was wiped. Never throws.
 */
internal fun wipeMaterialization(
    path: Path?,
    handle: SecretHandle?,
    isDirectory: (Path) -> Boolean,
    childrenOf: (Path) -> List<Path>,
    delete: (Path) -> Unit,
): List<Throwable> {
    val failures = mutableListOf<Throwable>()

    path?.let { root ->
        try {
            // Deepest first: a directory cannot be deleted until it is empty, so the order is
            // what makes a nested tree wipeable at all.
            val entries = if (isDirectory(root)) {
                // `sortedWith`, not `sorted`: the no-argument `sorted()` is the Kotlin stdlib
                // extension for a List, while the comparator-taking `sorted(Comparator)` this
                // replaced belonged to java.util.stream.Stream. `childrenOf` returns a List, so
                // the old spelling resolves to the parameterless extension and does not compile.
                childrenOf(root).sortedWith(Comparator.reverseOrder())
            } else {
                listOf(root)
            }
            for (entry in entries) {
                try {
                    delete(entry)
                } catch (t: Throwable) {
                    failures += t
                }
            }
        } catch (t: Throwable) {
            // The walk itself failed, or [isDirectory] did. Recorded, not propagated: there is
            // nothing left to attempt, but the caller still has to learn that the path survived.
            failures += t
        }
    }

    try {
        handle?.close()
    } catch (t: Throwable) {
        failures += t
    }

    return failures
}
