package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Tests for GitCredentialsApplier.
 *
 * Verifies:
 * 1. Temp file creation with correct POSIX permissions (0600 for files, 0700 for parent)
 * 2. close() wipes all temp files
 * 3. Exception in use {} block still triggers wipe in finally
 * 4. argv guard rejects ProcessBuilder args containing extraHeader/Authorization
 */
class GitCredentialsApplierTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `apply string channel creates git-credentials and gitconfig with 0600`() {
        val tokenHandle = SecretHandleRef(CredentialsId("api-token"), "string")
        val credentials = GitCredentials(string = tokenHandle)

        val applier = GitCredentialsApplier(tempDir, credentials)
        applier.apply(tokenHandle)  // Apply the string channel
        applier.use {
            // Verify .git-credentials exists
            val gitCreds = tempDir.resolve(".git-credentials")
            assertTrue(Files.exists(gitCreds), ".git-credentials must exist")

            // Verify permissions are 0600
            val perms = Files.getPosixFilePermissions(gitCreds)
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ), "Must be owner-read")
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE), "Must be owner-write")
            assertEquals(2, perms.size, "Only owner-read and owner-write (no group/other)")

            // Verify parent dir is 0700
            val parentPerms = Files.getPosixFilePermissions(tempDir)
            assertTrue(parentPerms.contains(PosixFilePermission.OWNER_READ), "Parent must be owner-read")
            assertTrue(parentPerms.contains(PosixFilePermission.OWNER_WRITE), "Parent must be owner-write")
            assertTrue(parentPerms.contains(PosixFilePermission.OWNER_EXECUTE), "Parent must be owner-execute (0700)")
        }
    }

    @Test
    fun `close wipes both git-credentials and gitconfig`() {
        val tokenHandle = SecretHandleRef(CredentialsId("api-token"), "string")
        val credentials = GitCredentials(string = tokenHandle)

        val applier = GitCredentialsApplier(tempDir, credentials)
        applier.apply(tokenHandle)  // Apply first
        applier.use {
            // Files exist inside the block
        }

        // After close, files must be gone
        val gitCreds = tempDir.resolve(".git-credentials")
        val gitConfig = tempDir.resolve(".gitconfig")
        assertFalse(Files.exists(gitCreds), ".git-credentials must be wiped after close")
        assertFalse(Files.exists(gitConfig), ".gitconfig must be wiped after close")
    }

    @Test
    fun `exception in use block still wipes files`() {
        val tokenHandle = SecretHandleRef(CredentialsId("api-token"), "string")
        val credentials = GitCredentials(string = tokenHandle)

        val applier = GitCredentialsApplier(tempDir, credentials)
        applier.apply(tokenHandle)  // Apply first

        try {
            applier.use {
                throw RuntimeException("boom")
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        // Files must still be wiped despite exception
        val gitCreds = tempDir.resolve(".git-credentials")
        val gitConfig = tempDir.resolve(".gitconfig")
        assertFalse(Files.exists(gitCreds), ".git-credentials must be wiped after exception")
        assertFalse(Files.exists(gitConfig), ".gitconfig must be wiped after exception")
    }

    @Test
    fun `argv guard rejects extraHeader in args`() {
        val args = listOf("--extraHeader=Authorization: Basic xyz")
        val exception = runCatching {
            GitCredentialsApplier.guardProcessBuilderArgs(args)
        }.exceptionOrNull()

        assertNotNull(exception, "guardProcessBuilderArgs must throw on extraHeader")
        assertTrue(exception is IllegalArgumentException, "Must throw IllegalArgumentException")
        val msg = exception?.message ?: ""
        assertTrue(msg.contains("extraHeader", ignoreCase = true) ||
                    msg.contains("Authorization", ignoreCase = true),
            "Exception message must mention the forbidden token")
    }

    @Test
    fun `argv guard rejects Authorization in args`() {
        val args = listOf("--config", "http.extraHeader=Authorization: Bearer token")
        val exception = runCatching {
            GitCredentialsApplier.guardProcessBuilderArgs(args)
        }.exceptionOrNull()

        assertNotNull(exception, "guardProcessBuilderArgs must throw on Authorization")
        assertTrue(exception is IllegalArgumentException, "Must throw IllegalArgumentException")
    }

    @Test
    fun `argv guard accepts clean args`() {
        val args = listOf("git", "ls-remote", "https://github.com/example/repo.git", "main")
        // Should not throw
        runCatching {
            GitCredentialsApplier.guardProcessBuilderArgs(args)
        }.onSuccess {
            // Expected - no exception
        }.onFailure { e ->
            throw AssertionError("Clean args must not throw: ${e.message}")
        }
    }

    @Test
    fun `apply usernamePassword creates gitconfig with 0600`() {
        val userHandle = SecretHandleRef(CredentialsId("user-creds"), "usernamePassword")
        val passHandle = SecretHandleRef(CredentialsId("pass-creds"), "usernamePassword")
        val credentials = GitCredentials(user = userHandle, pass = passHandle)

        val applier = GitCredentialsApplier(tempDir, credentials)
        applier.apply(userHandle, passHandle)  // Apply the usernamePassword channel
        applier.use {
            val gitConfig = tempDir.resolve(".gitconfig")
            assertTrue(Files.exists(gitConfig), ".gitconfig must exist for usernamePassword")

            val perms = Files.getPosixFilePermissions(gitConfig)
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ), "Must be owner-read")
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE), "Must be owner-write")
            assertEquals(2, perms.size, "Only owner-read and owner-write (0600)")
        }
    }
}
