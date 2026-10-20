package dev.rubentxu.pipeline.v2.credentials.multipart

import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceNotFoundException
import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceTypeMismatchException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializedCredential
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKindUnsupportedException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Materializes file-based credentials to temporary files with proper permissions.
 *
 * Materialization kinds:
 * - [MaterializationKind.SecretFile]: creates temp file with 0600 permissions
 * - [MaterializationKind.SshPrivateKey]: creates temp file for key + optionally for passphrase
 * - [MaterializationKind.Certificate]: creates temp file for keystore + optionally for password
 * - [MaterializationKind.Zip]: extracts to temp directory with 0700 permissions
 *
 * All materialized paths are tracked and wiped on [close].
 *
 * @param store The secret store to retrieve credentials from
 */
class CredentialMaterializer(
    private val store: SecretStore
) : AutoCloseable {

    // Track all materialized paths for cleanup
    private val trackedPaths = mutableListOf<Path>()
    private val trackedDirs = mutableListOf<Path>()

    // Idempotency cache: credentialId + kind -> materialized result
    private val materializationCache = mutableMapOf<Pair<CredentialsId, MaterializationKind>, MaterializedCredential>()

    private val supportedFileKinds = listOf(
        MaterializationKind.SecretFile,
        MaterializationKind.SshPrivateKey,
        MaterializationKind.Certificate,
        MaterializationKind.Zip
    )

    /**
     * Materializes a credential to a temporary file/path based on its kind.
     *
     * @param credential The credential to materialize
     * @param kind The materialization kind (must match credential type)
     * @return [MaterializedCredential] with path or handle
     * @throws MaterializationKindUnsupportedException if kind doesn't match credential type
     * @throws LinkedSecretReferenceNotFoundException if referenced credential doesn't exist
     * @throws LinkedSecretReferenceTypeMismatchException if referenced credential is wrong type
     */
    fun materialize(credential: Credential, kind: MaterializationKind): MaterializedCredential {
        val cacheKey = credential.id to kind
        materializationCache[cacheKey]?.let { return it }

        val result = when {
            kind == MaterializationKind.SecretFile && credential is SecretFile -> {
                materializeSecretFile(credential)
            }
            kind == MaterializationKind.SshPrivateKey && credential is SshPrivateKey -> {
                materializeSshPrivateKey(credential)
            }
            kind == MaterializationKind.Certificate && credential is Certificate -> {
                materializeCertificate(credential)
            }
            kind == MaterializationKind.Zip && credential is Zip -> {
                materializeZip(credential)
            }
            else -> {
                throw MaterializationKindUnsupportedException(
                    credential.id,
                    credential::class.simpleName ?: "Unknown",
                    supportedFileKinds
                )
            }
        }

        materializationCache[cacheKey] = result
        return result
    }

    private fun materializeSecretFile(credential: SecretFile): MaterializedCredential {
        // Create parent dir with 0700 perms (mkstemp-style) to satisfy CR-MZ-001 spec
        val parentDir = createTempDir(
            prefix = "pipeline-secret-dir-",
            suffix = "-${credential.id.value}"
        )
        Files.setPosixFilePermissions(parentDir, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
        trackedDirs.add(parentDir)

        // Create temp file inside the parent dir with 0600 permissions
        val tempFile = Files.createTempFile(parentDir, "pipeline-secret-", ".tmp")
        Files.setPosixFilePermissions(tempFile, CredentialFilePermissions.OWNER_READ_WRITE)
        Files.write(tempFile, credential.bytes)

        trackedPaths.add(tempFile)

        return MaterializedCredential.fromPath(credential.id, MaterializationKind.SecretFile, tempFile)
    }

    private fun materializeSshPrivateKey(credential: SshPrivateKey): MaterializedCredential {
        // Create temp file for private key with 0600 permissions
        val keyFile = createTempFile(
            prefix = "pipeline-ssh-key-",
            suffix = "-${credential.id.value}"
        )
        Files.write(keyFile, credential.privateKey)
        Files.setPosixFilePermissions(keyFile, CredentialFilePermissions.OWNER_READ_WRITE)
        trackedPaths.add(keyFile)

        // Handle passphrase if present
        credential.passphraseRef?.let { ref ->
            resolvePassphraseFile(ref, credential.id)
        }

        // Return with key path - passphrase path tracked internally
        return MaterializedCredential.fromPath(credential.id, MaterializationKind.SshPrivateKey, keyFile)
    }

    private fun resolvePassphraseFile(ref: LinkedSecretRef, credentialId: CredentialsId): Path {
        // Get the referenced credential (must be SecretText)
        val refCredential = store.get(ref.credentialsId)
        if (refCredential !is SecretText) {
            throw LinkedSecretReferenceTypeMismatchException(
                ref.credentialsId,
                expectedType = "SecretText",
                actualType = refCredential::class.simpleName ?: "Unknown"
            )
        }

        // Materialize passphrase to temp file
        val passphraseFile = createTempFile(
            prefix = "pipeline-ssh-passphrase-",
            suffix = "-${credentialId.value}"
        )
        Files.write(passphraseFile, refCredential.bytes)
        Files.setPosixFilePermissions(passphraseFile, CredentialFilePermissions.OWNER_READ_WRITE)
        trackedPaths.add(passphraseFile)

        return passphraseFile
    }

    private fun materializeCertificate(credential: Certificate): MaterializedCredential {
        // Create temp file for keystore with 0600 permissions
        val keystoreFile = createTempFile(
            prefix = "pipeline-cert-",
            suffix = "-${credential.alias ?: credential.id.value}"
        )
        Files.write(keystoreFile, credential.keystore)
        Files.setPosixFilePermissions(keystoreFile, CredentialFilePermissions.OWNER_READ_WRITE)
        trackedPaths.add(keystoreFile)

        // Handle password reference if present
        credential.passwordRef?.let { ref ->
            resolvePasswordFile(ref, credential.id)
        }

        return MaterializedCredential.fromPath(credential.id, MaterializationKind.Certificate, keystoreFile)
    }

    private fun resolvePasswordFile(ref: LinkedSecretRef, credentialId: CredentialsId): Path {
        val refCredential = store.get(ref.credentialsId)
        if (refCredential !is SecretText) {
            throw LinkedSecretReferenceTypeMismatchException(
                ref.credentialsId,
                expectedType = "SecretText",
                actualType = refCredential::class.simpleName ?: "Unknown"
            )
        }

        val passwordFile = createTempFile(
            prefix = "pipeline-cert-password-",
            suffix = "-${credentialId.value}"
        )
        Files.write(passwordFile, refCredential.bytes)
        Files.setPosixFilePermissions(passwordFile, CredentialFilePermissions.OWNER_READ_WRITE)
        trackedPaths.add(passwordFile)

        return passwordFile
    }

    private fun materializeZip(credential: Zip): MaterializedCredential {
        // Create temp directory with 0700 permissions
        val tempDir = createTempDir(
            prefix = "pipeline-zip-",
            suffix = "-${credential.id.value}"
        )
        Files.setPosixFilePermissions(tempDir, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
        trackedDirs.add(tempDir)

        // Extract entries
        for ((entryName, entryBytes) in credential.entries) {
            val entryPath = resolveZipEntryPath(credential.id, tempDir, entryName)

            // Create parent directories for nested paths (e.g., "subdir/c.txt")
            val parent = entryPath.parent
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent)
                // Set 0700 on created parent directories
                Files.setPosixFilePermissions(parent, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
                // Track them. Only the top directory was tracked before, so `close()` reached
                // `Files.delete(tempDir)` while a nested `sub/` was still inside it, took a
                // DirectoryNotEmptyException, and swallowed it -- leaving the extracted credential
                // on disk. `close()` deletes trackedDirs in reverse insertion order, so adding a
                // subdirectory after its parent removes the child first.
                for (created in createDirectoriesFrom(tempDir, parent)) {
                    trackedDirs.add(created)
                }
            }
            Files.write(entryPath, entryBytes)
            Files.setPosixFilePermissions(entryPath, CredentialFilePermissions.OWNER_READ_WRITE)
            trackedPaths.add(entryPath)
        }

        return MaterializedCredential.fromPath(credential.id, MaterializationKind.Zip, tempDir)
    }

    private fun createTempFile(prefix: String, suffix: String): Path {
        val tempDir = System.getProperty("java.io.tmpdir").let { Path.of(it) }
        return Files.createTempFile(tempDir, prefix, suffix)
    }

    private fun createTempDir(prefix: String, suffix: String): Path {
        val tempDir = System.getProperty("java.io.tmpdir").let { Path.of(it) }
        // `uniqueName` already carries `suffix`; the previous `uniqueName + suffix` appended it
        // a second time, naming the directory `<prefix><uuid>-<id>-<id>`. Cosmetic, but the
        // credential id appears twice in a path that operators read in logs.
        return Files.createTempDirectory(tempDir, "$prefix${UUID.randomUUID()}$suffix")
    }

    /**
     * Closes the materializer, wiping all tracked paths.
     * Files are zeroed and deleted, directories are deleted.
     */
    override fun close() {
        // Wipe and delete files
        for (path in trackedPaths.reversed()) {
            try {
                if (Files.exists(path)) {
                    // Zero the file content
                    val size = Files.size(path)
                    if (size > 0) {
                        val zeros = ByteArray(size.toInt())
                        Files.write(path, zeros)
                    }
                    Files.delete(path)
                }
            } catch (_: Exception) {
                // Silently ignore cleanup failures
            }
        }
        trackedPaths.clear()

        // Delete directories (in reverse order to handle nested dirs)
        for (dir in trackedDirs.reversed()) {
            try {
                if (Files.exists(dir)) {
                    Files.delete(dir)
                }
            } catch (_: Exception) {
                // Silently ignore cleanup failures
            }
        }
        trackedDirs.clear()

        materializationCache.clear()
    }
}

/**
 * The directories between [from] (exclusive) and [to] (inclusive), shallowest first.
 *
 * `Files.createDirectories` creates every missing level in one call and reports only the leaf, so
 * the intermediate directories have to be recovered if they are to be tracked. Walking from the
 * leaf up to [from] and reversing gives creation order, which is the order `close()` needs: it
 * deletes tracked directories in reverse insertion order, so a child inserted after its parent
 * is removed first.
 *
 * The ordering is not cosmetic. With only the top directory tracked, `close()` reached
 * `Files.delete(tempDir)` while a nested `sub/` was still inside it, took a
 * `DirectoryNotEmptyException`, and swallowed it -- leaving the extracted credential on disk.
 *
 * `internal` and top-level rather than private so the decision is reachable without a filesystem:
 * the only thing that reads it is a `Files.createDirectories` call, and AGENTS.md section 2
 * forbids that in a unit test.
 */
internal fun createDirectoriesFrom(from: Path, to: Path): List<Path> {
    // Both ends are normalised rather than trusted. Walked raw, `a/./x/../b` yields
    // `a/./x/..`, `a/./x` and `a/.` -- three levels `Files.createDirectories` never created, and
    // the `..` level normalises back to `a`, which is not empty, so the reverse-order delete
    // fails on it exactly as it did before. The only caller passes an already-normalised path
    // today, which is precisely why this is not a precondition to write down and hope for.
    val root = from.normalize()
    val created = mutableListOf<Path>()
    var current: Path? = to.normalize()
    while (current != null && current != root) {
        created.add(current)
        current = current.parent
    }
    return created.reversed()
}

/**
 * Where a `Zip` entry may be written, or a refusal naming the entry that may not.
 *
 * Zip Slip. `Zip.entries` is a `Map<String, ByteArray>` of free-form names read out of the
 * secret store, so `..` is not a malformed archive -- it is a legal key. With no confinement,
 * `../escape` and `../../etc/cron.d/x` resolve outside the temp directory and
 * `materializeZip` writes the entry's bytes there and chmods it 0600: an arbitrary write to an
 * arbitrary path, with attacker-chosen content, during credential materialization.
 *
 * `Path.startsWith` compares name ELEMENTS, so the sibling-prefix form (`<tmpdir>-evil`) is
 * refused too. A character prefix would admit exactly the case an attacker picks -- the same
 * distinction `DirExecutor.resolveTargetPath` was corrected for.
 *
 * `internal` and top-level rather than private and inline so the decision is reachable without
 * a filesystem: `materializeZip` calls `Files.createDirectories` and `Files.write`, and
 * AGENTS.md section 2 forbids both in a unit test. A guard whose only path runs through a file
 * write is a guard with no coverage.
 *
 * @throws ZipEntryEscapesMaterializationException if [entryName] leaves [tempDir]
 */
internal fun resolveZipEntryPath(credentialsId: CredentialsId, tempDir: Path, entryName: String): Path {
    val entryPath = tempDir.resolve(entryName).normalize()
    if (!entryPath.startsWith(tempDir)) {
        throw ZipEntryEscapesMaterializationException(credentialsId, entryName, entryPath)
    }
    return entryPath
}

/**
 * Thrown when a `Zip` credential carries an entry name that leaves its materialization directory.
 *
 * Declared here rather than beside [MaterializationKindUnsupportedException] in
 * `:pipeline-credentials-api` because that module has an `api/` compatibility gate, and
 * AGENTS.md section 1 forbids changing a published artifact's surface without an authorised
 * versioned break. The refusal belongs to this materializer, which is the only thing that
 * decides where an entry may land.
 *
 * @property credentialsId the archive whose entry escaped
 * @property entryName the offending key, verbatim -- this is what an operator has to find in
 *   the store to fix it, and a resolved absolute path is a consequence they cannot act on
 * @property resolvedPath where the entry would have been written
 */
class ZipEntryEscapesMaterializationException(
    val credentialsId: CredentialsId,
    val entryName: String,
    val resolvedPath: Path,
) : Exception(
    "Zip credential '$credentialsId' entry '$entryName' resolves to '$resolvedPath', " +
        "outside its materialization directory; refusing to write outside it",
)
