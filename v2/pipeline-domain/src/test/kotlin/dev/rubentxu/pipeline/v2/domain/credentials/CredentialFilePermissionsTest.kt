package dev.rubentxu.pipeline.v2.domain.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.attribute.PosixFilePermissions

/**
 * Locks the byte-exact posture of [CredentialFilePermissions] constants.
 *
 * D-001 ensures that adapters reuse the canonical constants rather than
 * inlining `PosixFilePermissions.fromString("...")` literals. A regression
 * here would silently relax or tighten the security posture of every
 * materialised credential — a class of bug that the runtime would not
 * catch.
 *
 * The tests assert against [PosixFilePermissions.fromString] directly
 * (the same factory the constants are built from) so any drift between
 * the constants and the POSIX octal string is caught immediately.
 */
@DisplayName("CredentialFilePermissions canonical posture")
class CredentialFilePermissionsTest {

    @Test
    fun `OWNER_READ_WRITE matches rw------- literal`() {
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            CredentialFilePermissions.OWNER_READ_WRITE,
            "OWNER_READ_WRITE must equal the canonical rw------- literal"
        )
    }

    @Test
    fun `OWNER_READ_WRITE_EXECUTE matches rwx------ literal`() {
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE,
            "OWNER_READ_WRITE_EXECUTE must equal the canonical rwx------ literal"
        )
    }

    @Test
    fun `OWNER_READ_WRITE excludes group and other permissions`() {
        val perms = CredentialFilePermissions.OWNER_READ_WRITE
        for (perm in perms) {
            // PosixFilePermission.name() is one of:
            //   OWNER_READ, OWNER_WRITE, OWNER_EXECUTE,
            //   GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
            //   OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE.
            // The audience is encoded in the first word of the name;
            // the last character (e.g. "D" in "OWNER_READ") is the
            // permission letter, not the audience.
            assertTrue(
                perm.name.startsWith("OWNER_"),
                "OWNER_READ_WRITE must not include GROUP_* or OTHERS_* permissions; found $perm"
            )
        }
        assertEquals(2, perms.size, "OWNER_READ_WRITE must contain exactly OWNER_READ + OWNER_WRITE")
    }

    @Test
    fun `OWNER_READ_WRITE_EXECUTE includes execute bit and excludes group and other`() {
        val perms = CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE
        for (perm in perms) {
            assertTrue(
                perm.name.startsWith("OWNER_"),
                "OWNER_READ_WRITE_EXECUTE must not include GROUP_* or OTHERS_* permissions; found $perm"
            )
        }
        assertEquals(3, perms.size, "OWNER_READ_WRITE_EXECUTE must contain exactly OWNER_READ + OWNER_WRITE + OWNER_EXECUTE")
    }
}
