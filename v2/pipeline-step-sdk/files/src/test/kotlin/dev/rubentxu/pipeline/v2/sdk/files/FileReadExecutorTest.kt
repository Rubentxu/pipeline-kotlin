package dev.rubentxu.pipeline.v2.sdk.files

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Tests for FileReadExecutor.
 *
 * Verifies:
 * - Happy path: file read returns correct content + sha256
 * - Non-existent file: returns exists=false
 * - Base64 encoding: encodes bytes to Base64 string
 * - Path safety: outside workspace returns exists=false
 *
 * RED: ClassNotFoundException (no FileReadExecutor yet)
 * GREEN: all tests pass
 */
@DisplayName("FileReadExecutor tests")
class FileReadExecutorTest {

    @Test
    fun `readFile_returns_file_content`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("input.txt"), "hello world")

        val executor = FileReadExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.ReadFile(file = "input.txt")
        val result = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(result.exists)
        assertEquals("hello world", result.content)
        assertEquals(11L, result.size)
        assertNotNull(result.sha256)
    }

    @Test
    fun `readFile_encoding_Base64_returns_base64_encoded_string`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.write(workspace.resolve("logo.bin"), "hello".toByteArray())

        val executor = FileReadExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.ReadFile(file = "logo.bin", encoding = "Base64")
        val result = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(result.exists)
        assertEquals("aGVsbG8=", result.content) // "hello" in Base64
        assertEquals(5L, result.size) // size of "hello", not the Base64 string
    }

    @Test
    fun `readFile_non_existent_file_returns_exists_false`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = FileReadExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.ReadFile(file = "missing.txt")
        val result = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)

        assertFalse(result.exists)
        assertNull(result.content)
        assertNull(result.sha256)
    }

    @Test
    fun `readFile_outside_workspace_returns_exists_false`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = FileReadExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.ReadFile(file = "../etc/passwd")
        val result = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)

        assertFalse(result.exists)
        assertNull(result.content)
    }

    @Test
    fun `readFile_reserved_v2_directory_returns_exists_false`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = FileReadExecutor(workspaceResolver = { _, _ -> workspace })

        val spec = StepSpec.ReadFile(file = ".v2/secret")
        val result = executor.execute(stageIndex = 0, stepIndex = 0, spec = spec)

        assertFalse(result.exists)
        assertNull(result.content)
    }
}
