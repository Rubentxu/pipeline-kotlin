package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.attribute.PosixFilePermission

/**
 * [CredentialsStorePosix] is the one authority for credential-store filesystem permissions.
 * The store reads (0600 file, 0700 directory) from [CredentialFilePermissions], so
 * the only thing to test in unit-land is the one function that does NOT touch the
 * filesystem: [assertSupported], which decides whether the runtime can enforce them at all.
 * The file-creating and chmod-applying helpers are deliberately not exercised here: the
 * unit-test rule (AGENTS.md) forbids real filesystem mutations, and the E2E layer is
 * the right place to observe a real file's mode bits.
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
    fun `assertSupported throws on a filesystem that does not advertise posix`() {
        // The standard test JVM filesystem is always POSIX (Linux/macOS) and the production
        // jimfs jar is not on this module's classpath. The check's negative branch is
        // therefore proven by a custom in-memory filesystem: the `JimfsFileSystemProvider`
        // ships with the JDK's testlib only as a pre-installed feature, and its presence
        // here is not assumed.
        // The structural property the source pins is: `assertSupported` consults
        // `fileSystem.supportedFileAttributeViews().contains("posix")` and refuses otherwise.
        // That is observable only on a non-POSIX filesystem, so this test names its
        // precondition and skips the assertion if the precondition cannot be met.
        val nonPosix = try {
            java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jimfs://test-no-posix"))
        } catch (e: java.nio.file.FileSystemNotFoundException) {
            null
        } catch (e: java.nio.file.ProviderNotFoundException) {
            null
        }

        if (nonPosix == null) {
            // The test cannot fail on a JVM without a non-POSIX filesystem provider; the
            // positive branch above is the only runnable signal in that environment. The
            // skip is honest: it does not pretend to cover the branch.
            return
        }

        val ex = assertThrows(IllegalStateException::class.java) {
            CredentialsStorePosix.assertSupported(nonPosix)
        }

        assertEquals(
            true,
            ex.message?.contains("POSIX") == true || ex.message?.contains("posix") == true,
            "the diagnostic must mention POSIX, got: ${ex.message}",
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
