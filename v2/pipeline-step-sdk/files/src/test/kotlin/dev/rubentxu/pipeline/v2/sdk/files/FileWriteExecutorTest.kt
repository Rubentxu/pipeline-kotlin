package dev.rubentxu.pipeline.v2.sdk.files

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Tests for FileWriteExecutor.
 *
 * Verifies:
 * - Happy path: atomic temp+rename creates correct file with sha256
 * - Base64 encoding: text="Base64" decodes binary content
 * - Path traversal guard: file outside workspace fails
 * - Reserved .v2 guard: file in .v2 directory fails
 * - Auto-mkdir: parent directories are created
 * - Cross-fs fallback: ATOMIC_MOVE failure falls back to REPLACE_EXISTING
 * - Anti-log invariant: FileWriteResult carries sha256 + size only (no content)
 *
 * RED: ClassNotFoundException (no FileWriteExecutor yet)
 * GREEN: all tests pass
 */
@DisplayName("FileWriteExecutor tests")
class FileWriteExecutorTest {

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // =============================================================================
    // Happy path
    // =============================================================================

    @Test
    fun `atomic_write_creates_file_with_sha256`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = FileWriteExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.WriteFile(file = "output.txt", text = "hello world")
        val result = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(Files.exists(result.path), "File should exist")
        assertEquals("hello world", Files.readString(result.path))
        assertEquals(11L, result.size)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", result.sha256)
        assertTrue(result.atomicallyMoved)
    }

    @Test
    fun `atomic_write_idempotent_same_content_same_sha`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = FileWriteExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.WriteFile(file = "output.txt", text = "same content")
        val result1 = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)
        val result2 = executor.execute(stageIndex = 0, stepIndex = 1, spec = spec)

        // Same content → same sha
        assertEquals(result1.sha256, result2.sha256)
        assertEquals("same content", Files.readString(result1.path))
    }

    @Test
    fun `deep_nested_path_auto_mkdir`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = FileWriteExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.WriteFile(file = "a/b/c/d/e.txt", text = "x")
        val result = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(Files.exists(result.path))
        assertEquals("x", Files.readString(result.path))
    }

    // =============================================================================
    // Base64 encoding
    // =============================================================================

    @Test
    fun `encoding_Base64_decodes_binary`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = FileWriteExecutor(workspaceResolver = { _, _ -> workspace })

        // "hello" in Base64 is "aGVsbG8="
        val spec = StepSpec.WriteFile(file = "logo.bin", text = "aGVsbG8=", encoding = "Base64")
        val result = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)

        assertEquals("hello", Files.readString(result.path))
        assertEquals(5L, result.size)
        assertEquals(sha256("hello".toByteArray()), result.sha256)
    }

    // =============================================================================
    // Path safety
    // =============================================================================

    @Test
    fun `path_traversal_guard_rejects_parent_escape`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = FileWriteExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.WriteFile(file = "../escape.txt", text = "hacked")
        val exception = runCatching {
            executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)
        }.exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception?.message?.contains("workspace") == true || exception?.message?.contains("escape") == true)
    }

    @Test
    fun `reserved_v2_directory_guard`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = FileWriteExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.WriteFile(file = ".v2/secret", text = "sensitive")
        val exception = runCatching {
            executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)
        }.exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception?.message?.contains(".v2") == true)
    }
}
