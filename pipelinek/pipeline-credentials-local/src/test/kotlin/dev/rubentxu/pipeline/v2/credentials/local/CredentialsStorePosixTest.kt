package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStorePosixPermissionsException
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * [CredentialsStorePosix] is the one authority for credential-store filesystem permissions.
 * The store reads (0600 file, 0700 directory) from [CredentialFilePermissions], so
 * the only thing to test in unit-land is the one function that does NOT touch the
 * filesystem: [assertSupported], which decides whether the runtime can enforce them at all.
 * The file-creating and chmod-applying helpers are deliberately not exercised here: the
 * unit-test rule (AGENTS.md) forbids real filesystem mutations, and no test in this tree
 * observes a real file's mode bits.
*/
class CredentialsStorePosixTest {

    @Test
    fun `assertSupported accepts a POSIX-capable filesystem`() {
        // The default JVM filesystem on Linux/macOS exposes the "posix" view. The test
        // would need a non-POSIX fs to drive the negative branch; we ship the positive
        // branch here and pin the failure mode separately.
        val default: FileSystem = FileSystems.getDefault()

        CredentialsStorePosix.assertSupported(default)
    }

    @Test
    fun `assertSupported refuses a filesystem that announces no POSIX view`() {
        // The ANNOUNCED VIEWS are the argument, not the filesystem, and that is what
        // makes this branch reachable anywhere: the filesystem that does not announce
        // a POSIX view is a filesystem this repository does not have, and the row that
        // needed one used to return without asserting anything at all.
        val ex = assertThrows(IllegalStateException::class.java) {
            CredentialsStorePosix.assertSupported(setOf("basic", "owner"))
        }

        assertTrue(
            ex.message?.contains("posix", ignoreCase = true) == true,
            "the diagnostic must mention POSIX, got: ${ex.message}",
        )
        assertTrue(
            ex.message?.contains("basic") == true,
            "the diagnostic must name the views the filesystem DID announce, got: ${ex.message}",
        )
    }

    @Test
    fun `enforce refuses a filesystem that announces no POSIX view before touching it`() {
        // The refusal comes FIRST: a store that cannot be protected must not leave a
        // directory behind on its way out.
        val ex = assertThrows(IllegalStateException::class.java) {
            CredentialsStorePosix.enforce(
                file = Path.of("/a-store-that-does-not-exist.bin"),
                dir = Path.of("/a-directory-that-does-not-exist"),
                views = setOf("basic", "owner"),
            )
        }

        assertTrue(
            ex.message?.contains("posix", ignoreCase = true) == true,
            "the diagnostic must mention POSIX, got: ${ex.message}",
        )
    }

    @Test
    fun `a file the filesystem left readable by everybody is refused`() {
        // A filesystem that accepts a permission change and does not apply it
        // leaves the file open to the machine. The decision is asked about the
        // permissions FOUND, so this branch is reachable without a filesystem
        // that ignores chmod.
        val ex = assertThrows(CredentialsStorePosixPermissionsException::class.java) {
            CredentialsStorePosix.requireExpected(
                file = Path.of("/a-store.bin"),
                dir = Path.of("/a-directory"),
                filePermissions = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OTHERS_READ,
                ),
                directoryPermissions = CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE,
            )
        }

        assertTrue(
            ex.message.orEmpty().contains("/a-store.bin"),
            "the refusal has to name the file that is exposed, and said: ${ex.message}",
        )
    }

    @Test
    fun `a directory the filesystem left listable by everybody is refused`() {
        val ex = assertThrows(CredentialsStorePosixPermissionsException::class.java) {
            CredentialsStorePosix.requireExpected(
                file = Path.of("/a-store.bin"),
                dir = Path.of("/a-directory"),
                filePermissions = CredentialFilePermissions.OWNER_READ_WRITE,
                directoryPermissions = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
        }

        assertTrue(
            ex.message.orEmpty().contains("/a-directory"),
            "the refusal has to name the directory that is exposed, and said: ${ex.message}",
        )
    }

    @Test
    fun `the permissions this store requires are accepted`() {
        // The decision has to accept what is right as well as refuse what is
        // wrong: a refusal that rejects everything is a store that never
        // opens, and that is not a safer store, it is a broken one.
        CredentialsStorePosix.requireExpected(
            file = Path.of("/a-store.bin"),
            dir = Path.of("/a-directory"),
            filePermissions = CredentialFilePermissions.OWNER_READ_WRITE,
            directoryPermissions = CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE,
        )
    }

    @Test
    fun `OWNER_READ_WRITE has read and write, no execute, no group, no other`() {
        // The store file's permission set is a contract: a future edit that adds "execute"
        // would let another user on the same box run the file. The set is loaded from
        // CredentialFilePermissions and tested here by its public surface
        val perms: Set<PosixFilePermission> = dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions.OWNER_READ_WRITE

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
            "the store file must be 0600 — owner read and write, nothing else",
        )
    }

    @Test
    fun `OWNER_READ_WRITE_EXECUTE has read write execute on the owner, no group no other`() {
        val perms: Set<PosixFilePermission> =
            dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            perms,
            "the store directory must be 0700 — owner read, write and execute, nothing else",
        )
    }
}
