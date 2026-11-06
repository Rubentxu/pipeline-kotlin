package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStorePosixPermissionsException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The permissions of the store that holds the secrets.
 *
 * ## What is under test
 *
 * A credential store is a file of secrets. Its safety does not come from the
 * encryption — it comes from the permissions of the file and of the directory
 * that holds it, because the ciphertext of a store is still a store that an
 * attacker can take away and attack offline.
 *
 * So the store has to make TWO promises, and both of them have to be true on
 * the machine where it runs:
 *
 * 1. the file is 0600 and the directory is 0700 — nobody else on the box can
 *    read the file, and nobody else can even LIST the directory that names it;
 * 2. where the platform cannot make that promise true, the store says so
 *    instead of opening anyway.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 *
 * The permissions of a file are a fact about the filesystem, not about a
 * value: the unit lane of this module forbids touching the disk, and a
 * permission cannot be observed without a file that has one. The store's own
 * file is the effect that leaves the process, so it is real here, in a
 * directory this test owns.
 *
 * ## What is faked
 *
 * The world outside the process and nothing else. The passphrase is a
 * constant this test supplies. No clock, no subprocess and no network.
 */
class CredentialsStorePermissionsTest {

    /** The directory this test owns. */
    @TempDir
    lateinit var tempDir: Path

    private val passphrase = "a passphrase this test owns".toCharArray()

    /**
     * A filesystem that does NOT announce a POSIX view.
     *
     * The JDK's own zip filesystem is one: it is a real filesystem, it is
     * writable, and it announces the views it has — which are not `posix`.
     * That makes the branch the store has to refuse reachable in this
     * repository, without a test-only dependency.
     */
    private fun fileSystemWithoutPosix(): FileSystem {
        val zip = tempDir.resolve("vault.zip")
        return FileSystems.newFileSystem(zip, mapOf("create" to "true"))
    }

    /**
     * A store on a platform that cannot guarantee its permissions does NOT
     * open.
     *
     * The store used to skip the permission work when the filesystem did not
     * announce a POSIX view, and it opened anyway: a file of secrets whose
     * permissions nobody checked, and no word to the operator about it. A
     * store that cannot protect itself has to say so.
     *
     * The refusal names the platform, so the operator knows whether the
     * filesystem is the problem or the store is.
     */
    @Test
    fun `a store on a filesystem without a POSIX view refuses to open`() {
        fileSystemWithoutPosix().use { fs ->
            assertFalse(
                fs.supportedFileAttributeViews().contains("posix"),
                "this row needs a filesystem that does not announce a POSIX view, and this one " +
                    "announces ${fs.supportedFileAttributeViews()}",
            )
            val storeFile = fs.getPath("/credentials.bin")

            val refusal = assertThrows(CredentialsStorePosixPermissionsException::class.java) {
                LocalSecretStore(file = storeFile, passphrase = passphrase)
            }

            assertTrue(
                refusal.message.orEmpty().contains("posix", ignoreCase = true),
                "the refusal has to name the platform that cannot guarantee the permissions, " +
                    "and said: ${refusal.message}",
            )
        }
    }

    /**
     * The store's directory is 0700, and its file is 0600.
     *
     * The directory is the half that is easy to lose: the file's permissions
     * are set every time the store writes, but the directory is created ONCE
     * — and a directory created by the platform keeps the platform's default
     * permissions unless somebody says otherwise. A store whose directory
     * stays 0755 lets every user of the machine list the store and see what
     * is in it, even if they cannot read a byte of it.
     *
     * The directory is created here with 0755 on purpose, so the row does not
     * depend on the umask of the machine that runs it.
     */
    @Test
    fun `the store's directory is 0700 and its file is 0600`() {
        val vault = Files.createDirectories(tempDir.resolve("vault"))
        Files.setPosixFilePermissions(vault, PosixFilePermissions.fromString("rwxr-xr-x"))
        val storeFile = vault.resolve("credentials.bin")

        val store = LocalSecretStore(file = storeFile, passphrase = passphrase)
        store.put(CredentialsId("an-id"), "a secret this test owns".toByteArray())

        assertEquals(
            CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE,
            Files.getPosixFilePermissions(vault),
            "the directory that holds the store must be 0700: nobody else may list it",
        )
        assertEquals(
            CredentialFilePermissions.OWNER_READ_WRITE,
            Files.getPosixFilePermissions(storeFile),
            "the store file must be 0600: nobody else may read it",
        )
    }

    /**
     * The store does not open when the platform cannot guarantee its
     * permissions.
     *
     * The guard is the seam where the platform's answer enters the store, so
     * a guard that refuses IS a platform that refuses — and the store has to
     * take that answer, not to look away from it. The store used to look
     * away: it asked the platform, and when the answer was "no", it carried
     * on opening the store anyway.
     *
     * The refusal leaves nothing behind: a store that refuses is a store
     * that never existed on that platform.
     */
    @Test
    fun `the store does not open when the platform refuses the permissions`() {
        val vault = tempDir.resolve("vault")
        val storeFile = vault.resolve("credentials.bin")

        val refusal = assertThrows(CredentialsStorePosixPermissionsException::class.java) {
            LocalSecretStore(
                file = storeFile,
                passphrase = passphrase,
                permissions = CredentialStorePermissionGuard { file, _ ->
                    throw CredentialsStorePosixPermissionsException(
                        "the credential store at $file cannot hold its secrets with safe " +
                            "permissions: the filesystem announces the attribute views [basic] and " +
                            "not posix",
                    )
                },
            )
        }

        assertTrue(
            refusal.message.orEmpty().contains("posix", ignoreCase = true),
            "the refusal has to name the platform that refused, and said: ${refusal.message}",
        )
        assertFalse(
            Files.exists(vault),
            "a store that refused to open must leave nothing behind on the platform that refused it",
        )
    }

    /**
     * A permission error is the store's own refusal, and it names the file.
     *
     * The filesystem refused the operation — the directory cannot be written,
     * the file cannot be chmodded — and the operator has to know WHICH store
     * is the one that could not be protected. A refusal that does not say
     * leaves the operator looking at every store they have.
     *
     * The failure the platform reports says NOTHING about the file: it says
     * "permission denied", and nothing more. If it named the file, this row
     * would be satisfied by the cause's message and the store could stop
     * naming its own file without anybody noticing.
     */
    @Test
    fun `a permission error becomes the store's own refusal naming the file`() {
        val storeFile = tempDir.resolve("vault").resolve("credentials.bin")

        val refusal = assertThrows(CredentialsStorePosixPermissionsException::class.java) {
            LocalSecretStore(
                file = storeFile,
                passphrase = passphrase,
                permissions = CredentialStorePermissionGuard { _, _ ->
                    throw java.nio.file.AccessDeniedException("permission denied")
                },
            )
        }

        assertTrue(
            refusal.message.orEmpty().contains(storeFile.toString()),
            "the refusal has to name the store whose secrets are at stake, and said: ${refusal.message}",
        )
    }
}
