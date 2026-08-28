package dev.rubentxu.pipeline.v2.credentials.multipart

import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceNotFoundException
import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceTypeMismatchException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
        Files.setPosixFilePermissions(parentDir, OWNER_READ_WRITE_EXECUTE)
        trackedDirs.add(parentDir)

        // Create temp file inside the parent dir with 0600 permissions
        val tempFile = Files.createTempFile(parentDir, "pipeline-secret-", ".tmp")
        Files.setPosixFilePermissions(tempFile, OWNER_READ_WRITE)
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
        Files.setPosixFilePermissions(keyFile, OWNER_READ_WRITE)
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
        Files.setPosixFilePermissions(passphraseFile, OWNER_READ_WRITE)
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
        Files.setPosixFilePermissions(keystoreFile, OWNER_READ_WRITE)
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
        Files.setPosixFilePermissions(passwordFile, OWNER_READ_WRITE)
        trackedPaths.add(passwordFile)

        return passwordFile
    }

    private fun materializeZip(credential: Zip): MaterializedCredential {
        // Create temp directory with 0700 permissions
        val tempDir = createTempDir(
            prefix = "pipeline-zip-",
            suffix = "-${credential.id.value}"
        )
        Files.setPosixFilePermissions(tempDir, OWNER_READ_WRITE_EXECUTE)
        trackedDirs.add(tempDir)

        // Extract entries
        for ((entryName, entryBytes) in credential.entries) {
            val entryPath = tempDir.resolve(entryName)
            // Create parent directories for nested paths (e.g., "subdir/c.txt")
            val parent = entryPath.parent
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent)
                // Set 0700 on created parent directories
                Files.setPosixFilePermissions(parent, OWNER_READ_WRITE_EXECUTE)
            }
            Files.write(entryPath, entryBytes)
            Files.setPosixFilePermissions(entryPath, OWNER_READ_WRITE)
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
        val uniqueName = "$prefix${UUID.randomUUID()}$suffix"
        return Files.createTempDirectory(tempDir, uniqueName + suffix)
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

    companion object {
        private val OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------")
        private val OWNER_READ_WRITE_EXECUTE = PosixFilePermissions.fromString("rwx------")
    }
}
