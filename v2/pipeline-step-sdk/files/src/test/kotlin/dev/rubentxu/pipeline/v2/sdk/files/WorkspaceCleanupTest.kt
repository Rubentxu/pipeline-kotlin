package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.full.primaryConstructor

/**
 * Tests for workspace cleanup steps — WCL-S-001..008 scenarios.
 *
 * WCL-S-001: deleteDir happy path on non-empty workspace
 * WCL-S-002: deleteDir idempotent on replay (MEMOIZED)
 * WCL-S-003: deleteDir refuses to delete outside workspace root
 * WCL-S-004: cleanWs with patterns only deletes matching files
 * WCL-S-005: cleanWs(deleteDirs=true) removes now-empty subdirs
 * WCL-S-006: cleanWs preserves .v2/artifacts/ (F-ARCH-L6-003)
 * WCL-S-007: Ant-style glob semantics
 * WCL-S-008: Jenkins-verbatim signature reflection
 */
@DisplayName("Workspace cleanup tests — WCL-S-001..008")
class WorkspaceCleanupTest {

    // =============================================================================
    // WCL-S-001: deleteDir happy path on non-empty workspace
    // =============================================================================

    @Test
    fun `WCL-S-001 deleteDir happy path on non-empty workspace`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("a.txt"), "hello")
        Files.createDirectories(workspace.resolve("b"))
        Files.writeString(workspace.resolve("b/c.txt"), "world")

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val spec = StepSpec.DeleteDir(path = ".")
        val result = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(!Files.exists(workspace.resolve("a.txt")), "a.txt should be deleted")
        assertTrue(!Files.exists(workspace.resolve("b/c.txt")), "b/c.txt should be deleted")

        assertTrue(result.deletedCount >= 2, "deletedCount should be >= 2, got: ${result.deletedCount}")
        assertTrue(result.sha256.isNotEmpty(), "sha256 must be non-empty")

        val markerFile = workspace.resolve(".deleted")
        assertTrue(Files.exists(markerFile), ".deleted marker must exist")
        val markerContent = Files.readString(markerFile).trim()
        assertEquals(result.sha256, markerContent, "marker content must match sha256")
    }

    // =============================================================================
    // WCL-S-002: deleteDir idempotent on replay (MEMOIZED)
    // =============================================================================

    @Test
    fun `WCL-S-002 deleteDir idempotent on replay`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("a.txt"), "hello")

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )

        val result1 = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = StepSpec.DeleteDir())
        assertTrue(result1.deletedCount >= 1, "first run should delete")

        val result2 = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = StepSpec.DeleteDir())
        assertEquals(0, result2.deletedCount, "replay should be no-op")
        assertEquals(result1.sha256, result2.sha256, "replay sha256 must match first run (MEMOIZED)")
    }

    @Test
    fun `WCL-S-002 deleteDir idempotent with pre-existing marker`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val knownSha = "abc123def456"
        Files.writeString(workspace.resolve(".deleted"), knownSha)

        val result = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = StepSpec.DeleteDir())
        assertEquals(0, result.deletedCount, "should be no-op with existing marker")
        assertEquals(knownSha, result.sha256, "sha256 must match pre-existing marker")
    }

    // =============================================================================
    // WCL-S-003: deleteDir refuses to delete outside workspace root
    // =============================================================================

    @Test
    fun `WCL-S-003 deleteDir refuses outside workspace root`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )

        val spec = StepSpec.DeleteDir(path = "/etc")
        val exception = runCatching {
            executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = spec)
        }.exceptionOrNull()

        assertNotNull(exception, "must throw when path escapes workspace")
        assertTrue(
            exception is IllegalArgumentException ||
            (exception?.message ?: "").contains("workspace") ||
            (exception?.message ?: "").contains("escape"),
            "exception must mention workspace/escape: ${exception?.message}",
        )
    }

    // =============================================================================
    // WCL-S-004: cleanWs with patterns only deletes matching files
    // =============================================================================

    @Test
    fun `WCL-S-004 cleanWs with patterns only deletes matching files`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("keep.txt"), "keep me")
        Files.createDirectories(workspace.resolve("target"))
        Files.writeString(workspace.resolve("target/x.class"), "class1")
        Files.writeString(workspace.resolve("target/y.class"), "class2")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val spec = StepSpec.CleanWs(deleteDirs = false, patterns = listOf("target/**/*"))
        val result = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(Files.exists(workspace.resolve("keep.txt")), "keep.txt must survive")
        assertTrue(!Files.exists(workspace.resolve("target/x.class")), "target/x.class must be deleted")
        assertTrue(!Files.exists(workspace.resolve("target/y.class")), "target/y.class must be deleted")
        assertEquals(2, result.deletedFiles, "deletedFiles should be 2")
        assertTrue(result.patterns.contains("target/**/*"), "patterns must be preserved")
    }

    // =============================================================================
    // WCL-S-005: cleanWs(deleteDirs=true) removes now-empty subdirs
    // =============================================================================

    @Test
    fun `WCL-S-005 cleanWs deleteDirs removes empty parents`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.createDirectories(workspace.resolve("target"))
        Files.createDirectories(workspace.resolve("logs"))
        Files.writeString(workspace.resolve("target/x.class"), "class")
        Files.writeString(workspace.resolve("logs/y.log"), "log")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val spec = StepSpec.CleanWs(deleteDirs = true, patterns = listOf("target/**"))
        val result = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(!Files.exists(workspace.resolve("target")), "target/ must be removed")
        assertTrue(Files.exists(workspace.resolve("logs/y.log")), "logs/ must survive")
        assertEquals(1, result.deletedFiles, "deletedFiles should be 1")
        assertTrue(result.deletedDirs >= 1, "deletedDirs should be >= 1")
    }

    // =============================================================================
    // WCL-S-006: cleanWs preserves .v2/artifacts/ (F-ARCH-L6-003)
    // =============================================================================

    @Test
    fun `WCL-S-006 cleanWs preserves v2 artifacts`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.createDirectories(workspace.resolve(".v2/artifacts/run123"))
        Files.writeString(workspace.resolve(".v2/artifacts/run123/foo.jar"), "jar content")
        Files.createDirectories(workspace.resolve("target"))
        Files.writeString(workspace.resolve("target/x.class"), "class")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val spec = StepSpec.CleanWs(deleteDirs = true, patterns = null)
        val result = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(Files.exists(workspace.resolve(".v2/artifacts/run123/foo.jar")),
            ".v2/artifacts/foo.jar must survive")
        assertTrue(!Files.exists(workspace.resolve("target/x.class")),
            "target/x.class must be deleted")
        assertEquals(1, result.deletedFiles)
        // target/ is now empty and gets removed by deleteEmptyParents
        assertTrue(result.deletedDirs >= 1, "deletedDirs should be >= 1 (target/ removed)")
    }

    // =============================================================================
    // WCL-S-007: Ant-style glob semantics
    // =============================================================================

    @Test
    fun `WCL-S-007 cleanWs ant-style glob double-star`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.createDirectories(workspace.resolve("a/b"))
        Files.writeString(workspace.resolve("a/b/deep.txt"), "deep")
        Files.writeString(workspace.resolve("root.txt"), "root")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val spec = StepSpec.CleanWs(deleteDirs = false, patterns = listOf("a/**/*.txt"))
        val result = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(!Files.exists(workspace.resolve("a/b/deep.txt")), "a/b/deep.txt must be deleted")
        assertTrue(Files.exists(workspace.resolve("root.txt")), "root.txt must survive")
        assertEquals(1, result.deletedFiles)
    }

    @Test
    fun `WCL-S-007 cleanWs ant-style glob single star`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("a.tmp"), "a")
        Files.writeString(workspace.resolve("b.tmp"), "b")
        Files.writeString(workspace.resolve("c.txt"), "c")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val spec = StepSpec.CleanWs(deleteDirs = false, patterns = listOf("*.tmp"))
        val result = executor.execute(stageName = "Test", stageIndex = 0, stepIndex = 0, spec = spec)

        assertTrue(!Files.exists(workspace.resolve("a.tmp")))
        assertTrue(!Files.exists(workspace.resolve("b.tmp")))
        assertTrue(Files.exists(workspace.resolve("c.txt")), "c.txt must survive")
        assertEquals(2, result.deletedFiles)
    }

    // =============================================================================
    // WCL-S-008: Jenkins-verbatim signature reflection
    // =============================================================================

    @Test
    fun `WCL-S-008 StepSpec_DeleteDir has correct Jenkins-verbatim signature`() {
        val kclass = StepSpec.DeleteDir::class
        val constructor = kclass.primaryConstructor!!
        assertEquals(1, constructor.parameters.size, "DeleteDir must have exactly 1 parameter")
        assertEquals("path", constructor.parameters[0].name, "First parameter must be 'path'")
    }

    @Test
    fun `WCL-S-008 StepSpec_CleanWs has correct Jenkins-verbatim signature`() {
        val kclass = StepSpec.CleanWs::class
        val constructor = kclass.primaryConstructor!!
        assertEquals(2, constructor.parameters.size, "CleanWs must have exactly 2 parameters")
        assertEquals("deleteDirs", constructor.parameters[0].name, "First parameter must be 'deleteDirs'")
        assertEquals("patterns", constructor.parameters[1].name, "Second parameter must be 'patterns'")
    }

    // =============================================================================
    // C8: a shared user workspace (--workspace) must never be wipeable
    // =============================================================================

    @Test
    fun `C8 deleteDir refuses the workspace root when the root is a user project`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("my-project")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("README.md"), "important")
        Files.createDirectories(workspace.resolve("src"))
        Files.writeString(workspace.resolve("src/main.kt"), "code")

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.UserOwned,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            executor.execute("Test", 0, 0, StepSpec.DeleteDir(path = "."))
        }
        assertTrue(
            error.message!!.contains("refuses to delete the workspace root"),
            "error must name the refusal, got: ${error.message}",
        )
        assertTrue(Files.exists(workspace.resolve("README.md")), "README.md must survive")
        assertTrue(Files.exists(workspace.resolve("src/main.kt")), "src/main.kt must survive")
        assertTrue(!Files.exists(workspace.resolve(".deleted")), "no marker may be written on refusal")
    }

    @Test
    fun `C8 deleteDir still removes a sub-path inside a protected workspace`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("my-project")
        Files.createDirectories(workspace.resolve("build/classes"))
        Files.writeString(workspace.resolve("build/classes/out.jar"), "x")
        Files.writeString(workspace.resolve("keep.txt"), "keep")

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.UserOwned,
        )

        val result = executor.execute("Test", 0, 0, StepSpec.DeleteDir(path = "build"))

        assertTrue(result.deletedCount >= 1, "sub-path contents must be deleted")
        assertTrue(!Files.exists(workspace.resolve("build/classes/out.jar")), "out.jar must be deleted")
        assertTrue(Files.exists(workspace.resolve("keep.txt")), "sibling file must survive")
    }

    @Test
    fun `C8 deleteDir rejects traversal outside a protected workspace`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("my-project")
        Files.createDirectories(workspace)
        val outside = tempDir.resolve("elsewhere")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("precious.txt"), "do not touch")

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.UserOwned,
        )

        assertThrows(IllegalArgumentException::class.java) {
            executor.execute("Test", 0, 0, StepSpec.DeleteDir(path = "../elsewhere"))
        }
        assertTrue(Files.exists(outside.resolve("precious.txt")), "outside file must survive")
    }

    @Test
    fun `C8 dot path is rejected even when spelled as the workspace root`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("my-project")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("file.txt"), "data")

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.UserOwned,
        )

        // "./" normalises to the root as well; both spellings must be refused.
        assertThrows(IllegalArgumentException::class.java) {
            executor.execute("Test", 0, 0, StepSpec.DeleteDir(path = "./"))
        }
        assertTrue(Files.exists(workspace.resolve("file.txt")), "file.txt must survive")
    }

    @Test
    fun `C8 scratch workspace keeps its wipe contract`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("a.txt"), "scratch")

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val result = executor.execute("Test", 0, 0, StepSpec.DeleteDir(path = "."))

        assertTrue(result.deletedCount >= 1, "scratch workspace must still be wipeable")
        assertTrue(!Files.exists(workspace.resolve("a.txt")), "a.txt must be deleted in scratch mode")
    }

    // =============================================================================
    // C9: cleanWs with no patterns deletes EVERY file in a shared user workspace
    // =============================================================================

    @Test
    fun `C9 cleanWs refuses the pattern-less form on a user project`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("my-project")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("README.md"), "important")
        Files.writeString(workspace.resolve("main.kt"), "code")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.UserOwned,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            executor.execute("Test", 0, 0, StepSpec.CleanWs(patterns = null))
        }
        assertTrue(
            error.message!!.contains("refuses to run without patterns"),
            "error must name the refusal, got: ${error.message}",
        )
        assertTrue(Files.exists(workspace.resolve("README.md")), "README.md must survive")
        assertTrue(Files.exists(workspace.resolve("main.kt")), "main.kt must survive")
        assertTrue(!Files.exists(workspace.resolve(".cleaned")), "no marker may be written on refusal")
    }

    @Test
    fun `C9 cleanWs refuses an empty pattern list too`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("my-project")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("keep.txt"), "keep")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.UserOwned,
        )

        // An empty list is semantically the same as null: both take the
        // "delete all non-.v2 files" branch. It must be refused too.
        assertThrows(IllegalArgumentException::class.java) {
            executor.execute("Test", 0, 0, StepSpec.CleanWs(patterns = emptyList()))
        }
        assertTrue(Files.exists(workspace.resolve("keep.txt")), "keep.txt must survive")
    }

    @Test
    fun `C9 cleanWs with patterns still cleans a user project`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("my-project")
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/out.txt"), "generated")
        Files.writeString(workspace.resolve("README.md"), "keep")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.UserOwned,
        )

        val result = executor.execute("Test", 0, 0, StepSpec.CleanWs(patterns = listOf("build/**")))

        assertTrue(result.deletedFiles >= 1, "matched file must be deleted")
        assertTrue(!Files.exists(workspace.resolve("build/out.txt")), "out.txt must be deleted")
        assertTrue(Files.exists(workspace.resolve("README.md")), "README.md must survive")
    }

    @Test
    fun `C9 cleanWs keeps its wipe contract in a scratch workspace`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("a.txt"), "scratch")

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        val result = executor.execute("Test", 0, 0, StepSpec.CleanWs(patterns = null))

        assertTrue(result.deletedFiles >= 1, "scratch wipe must still work")
        assertTrue(!Files.exists(workspace.resolve("a.txt")), "a.txt must be deleted in scratch mode")
    }
}
