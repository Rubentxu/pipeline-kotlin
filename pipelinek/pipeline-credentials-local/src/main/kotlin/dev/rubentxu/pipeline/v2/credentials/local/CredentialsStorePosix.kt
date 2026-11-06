package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStorePosixPermissionsException
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * POSIX permission helpers for credential store files.
 * ## Requirements
 * - Store file: 0600 (owner read/write only)
 * - Store directory: 0700 (owner read/write/execute only)
 * These permissions protect the credentials at rest from other users on the same system.
 * The permission sets come from [CredentialFilePermissions] so
 * any drift between adapters is caught at compile time, not by
 * adversarial filesystem testing.
*/
object CredentialsStorePosix {

    /**
     * The name of the attribute view a filesystem announces when it can
     * express POSIX permissions.
     */
    private const val POSIX_VIEW = "posix"

    /**
     * Creates the parent directory with 0700 permissions.
     *
     * Creating a directory with an attribute is not enough: the attribute
     * applies to a directory this call CREATES, and a directory that was
     * already there keeps whatever permissions it was created with. That is
     * why [enforce] also sets them explicitly.
*/
    fun createDirectory(dir: Path) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
            CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE))
    }

    /**
     * Sets the store file to 0600.
*/
    fun setFilePermissions(file: Path) {
        Files.setPosixFilePermissions(file, CredentialFilePermissions.OWNER_READ_WRITE)
    }

    /**
     * Sets the directory that holds the store to 0700.
     *
     * A directory whose permissions were never set is a directory the whole
     * machine can list, and listing it says which credentials exist.
    */
    fun setDirectoryPermissions(dir: Path) {
        Files.setPosixFilePermissions(dir, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
    }

    /**
     * Asserts that the filesystem supports POSIX permissions.
     *
     * The refusal names the state that was found, and not an argument of the
     * caller, so it is a state and not an invalid argument. The store's own
     * permissions exception belongs to the store that raises it.
     *
     * @throws IllegalStateException if POSIX is not supported
    */
    fun assertSupported(fileSystem: FileSystem) {
        assertSupported(fileSystem.supportedFileAttributeViews())
    }

    /**
     * Asserts that a filesystem that announces these views supports POSIX
     * permissions.
     *
     * The announced views are the argument, and not the filesystem, so the
     * branch that refuses can be reached on any machine: the filesystem that
     * does not announce a POSIX view is a filesystem this repository does not
     * have, and a refusal nobody can reach is a refusal nobody reads.
     *
     * @param views The attribute views the filesystem announces.
     * @throws IllegalStateException if POSIX is not among them
    */
    fun assertSupported(views: Set<String>) {
        check(views.contains(POSIX_VIEW)) {
            "the filesystem announces the attribute views ${views.sorted()} and not $POSIX_VIEW, " +
                "so the store's permissions cannot be applied nor verified"
        }
    }

    /**
     * Enforces POSIX permissions on a store file and its parent directory.
     *
     * The directory's permissions are set as well as created, because a
     * directory that already existed keeps the permissions it was created
     * with, and both are read back afterwards: a filesystem that accepts a
     * permission change and does not apply it has to be caught HERE, where
     * the store can still refuse to open.
    */
    fun enforce(file: Path, dir: Path) {
        enforce(file, dir, file.fileSystem.supportedFileAttributeViews())
    }

    /**
     * Enforces POSIX permissions on a store file and its parent directory,
     * on a filesystem that announces these views.
     *
     * @param file The file that holds the secrets.
     * @param dir The directory that holds that file.
     * @param views The attribute views the filesystem announces.
     * @throws IllegalStateException if the filesystem does not announce a
     *   POSIX view.
     * @throws CredentialsStorePosixPermissionsException if the permissions
     *   were applied and are not the ones this store requires.
    */
    fun enforce(file: Path, dir: Path, views: Set<String>) {
        assertSupported(views)
        createDirectory(dir)
        setDirectoryPermissions(dir)
        if (Files.exists(file)) {
            setFilePermissions(file)
        } else {
            // Create empty file with correct permissions
            Files.createFile(file, PosixFilePermissions.asFileAttribute(
                CredentialFilePermissions.OWNER_READ_WRITE))
        }
        verify(file, dir)
    }

    /**
     * Reads the permissions back and checks them against what this store
     * requires.
     *
     * Setting a permission is not the same as having it: a filesystem that
     * accepts the change and does not apply it leaves the file readable by
     * everybody, and the store would be open with a secret inside it. The
     * only place where that can still be caught is here.
     *
     * @throws CredentialsStorePosixPermissionsException if either permission
     *   is not the one this store requires.
    */
    private fun verify(file: Path, dir: Path) {
        requireExpected(
            file = file,
            dir = dir,
            filePermissions = Files.getPosixFilePermissions(file),
            directoryPermissions = Files.getPosixFilePermissions(dir),
        )
    }

    /**
     * The decision that says whether the permissions that were found are the
     * ones this store requires.
     *
     * The permissions are the ARGUMENT and not the filesystem, so the decision
     * can be asked about any pair of them: a filesystem that accepted a
     * permission change and did not apply it is a filesystem this repository
     * does not have, and a decision nobody can ask about is a decision nobody
     * reads.
     *
     * @param file The file that holds the secrets.
     * @param dir The directory that holds that file.
     * @param filePermissions The permissions the file was found with.
     * @param directoryPermissions The permissions the directory was found with.
     * @throws CredentialsStorePosixPermissionsException if either permission
     *   is not the one this store requires.
    */
    internal fun requireExpected(
        file: Path,
        dir: Path,
        filePermissions: Set<PosixFilePermission>,
        directoryPermissions: Set<PosixFilePermission>,
    ) {
        if (filePermissions != CredentialFilePermissions.OWNER_READ_WRITE) {
            throw CredentialsStorePosixPermissionsException(
                "the store file $file holds $filePermissions and not " +
                    "${CredentialFilePermissions.OWNER_READ_WRITE}: the filesystem accepted the " +
                    "permissions and did not apply them",
            )
        }
        if (directoryPermissions != CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE) {
            throw CredentialsStorePosixPermissionsException(
                "the directory $dir that holds the store has $directoryPermissions and not " +
                    "${CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE}: the filesystem accepted " +
                    "the permissions and did not apply them",
            )
        }
    }
}
