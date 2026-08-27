package dev.rubentxu.pipeline.v2.credentials.local

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * POSIX permission helpers for credential store files.
 *
 * ## Requirements
 *
 * - Store file: 0600 (owner read/write only)
 * - Store directory: 0700 (owner read/write/execute only)
 *
 * These permissions protect the credentials at rest from other users on the same system.
 */
object CredentialsStorePosix {

    /**
     * Creates the parent directory with 0700 permissions.
     */
    fun createDirectory(dir: Path) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")))
    }

    /**
     * Sets the store file to 0600.
     */
    fun setFilePermissions(file: Path) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }

    /**
     * Asserts that the filesystem supports POSIX permissions.
     *
     * @throws LocalSecretStore.CredentialsStorePosixPermissionsUnsupportedException
     * if POSIX is not supported
     */
    fun assertSupported(fileSystem: java.nio.file.FileSystem) {
        check(fileSystem.supportedFileAttributeViews().contains("posix")) {
            "POSIX file attributes not supported on this filesystem"
        }
    }

    /**
     * Enforces POSIX permissions on a store file and its parent directory.
     */
    fun enforce(file: Path, dir: Path) {
        val fs = file.fileSystem
        assertSupported(fs)
        createDirectory(dir)
        if (Files.exists(file)) {
            setFilePermissions(file)
        } else {
            // Create empty file with correct permissions
            Files.createFile(file, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")))
        }
    }
}
