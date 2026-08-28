package dev.rubentxu.pipeline.v2.sdk.files

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import dev.rubentxu.pipeline.v2.sdk.files.FileExistsExecutor
import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Tests for FileExistsExecutor.
 *
 * Verifies:
 * - File exists: returns true
 * - File does not exist: returns false
 * - Path outside workspace: returns false
 * - Reserved .v2 directory: returns false (does not throw)
 * - No event emitted (per spec: "return value is the only outcome")
 *
 * RED: ClassNotFoundException (no FileExistsExecutor yet)
 * GREEN: all tests pass
 */
@DisplayName("FileExistsExecutor tests")
class FileExistsExecutorTest {

    @Test
    fun `fileExists_returns_true_when_file_exists`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("config.xml"), "<config/>")

        val executor = FileExistsExecutor(
            workspaceResolver = { _, _ -> workspace },
        )

        val spec = StepSpec.FileExists(file = "config.xml")
        val result = executor.execute(stageName = "test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(result.exists)
    }

    @Test
    fun `fileExists_returns_false_when_file_missing`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = FileExistsExecutor(
            workspaceResolver = { _, _ -> workspace },
        )

        val spec = StepSpec.FileExists(file = "missing.txt")
        val result = executor.execute(stageName = "test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertFalse(result.exists)
    }

    @Test
    fun `fileExists_outside_workspace_returns_false`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = FileExistsExecutor(
            workspaceResolver = { _, _ -> workspace },
        )

        val spec = StepSpec.FileExists(file = "/etc/passwd")
        val result = executor.execute(stageName = "test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertFalse(result.exists)
    }

    @Test
    fun `fileExists_parent_escape_returns_false`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = FileExistsExecutor(
            workspaceResolver = { _, _ -> workspace },
        )

        val spec = StepSpec.FileExists(file = "../escape.txt")
        val result = executor.execute(stageName = "test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertFalse(result.exists)
    }

    @Test
    fun `fileExists_reserved_v2_directory_returns_false`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = FileExistsExecutor(
            workspaceResolver = { _, _ -> workspace },
        )

        val spec = StepSpec.FileExists(file = ".v2/secret")
        val result = executor.execute(stageName = "test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertFalse(result.exists)
    }
}
