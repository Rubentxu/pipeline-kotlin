package dev.rubentxu.pipeline.v2.sdk.workflow

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.sdk.api.BlockStepFlattener
import dev.rubentxu.pipeline.v2.sdk.api.FlattenedStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.full.primaryConstructor

/**
 * Tests for DirExecutor — DIR-S-001..006 scenarios.
 *
 * DIR-S-001: happy path enter + exit restores cwd
 * DIR-S-002: thrown exception inside block still restores cwd
 * DIR-S-003: path traversal guard refuses ../escape
 * DIR-S-004: block-step flattening produces monotonic indices
 * DIR-S-005: replay cursor resumes mid-dir block correctly (idempotent cd)
 * DIR-S-006: Jenkins-verbatim signature reflection
 *
 * RED: ClassNotFoundException (no DirExecutor yet)
 * GREEN: all 6 scenarios pass
 */
@DisplayName("DirExecutor tests — DIR-S-001..006")
class DirExecutorTest {

    // =============================================================================
    // DIR-S-001: happy path enter + exit restores cwd
    // =============================================================================

    @Test
    fun `DIR-S-001 happy path returns workspace context without changing cwd`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = DirExecutor(workspaceResolver = { _, _ -> workspace })

        val prevDir = System.getProperty("user.dir")
        val step = StepSpec.Dir(path = "sub", steps = emptyList())

        val result = executor.execute(
            stageName = "TestStage",
            stageIndex = 0,
            stepIndex = 0,
            step = step,
            action = { "success" }
        )

        assertEquals("success", result.outcome)
        assertEquals(prevDir, System.getProperty("user.dir"))
        assertTrue(result.currentPath.endsWith("sub") || result.currentPath.contains("sub"),
            "currentPath should contain 'sub': ${result.currentPath}")
        assertEquals(workspace.toString(), result.restoredPath, "restoredPath must equal workspace context")
        assertEquals(workspace.toString(), result.previousPath, "previousPath must capture workspace context")
    }

    @Test
    fun `DIR-S-001 keeps the JVM working-directory property unchanged while executing`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = DirExecutor(workspaceResolver = { _, _ -> workspace })
        val previousJvmDirectory = System.getProperty("user.dir")

        executor.execute(
            stageName = "TestStage",
            stageIndex = 0,
            stepIndex = 0,
            step = StepSpec.Dir(path = "sub", steps = emptyList()),
            action = {
                assertEquals(
                    previousJvmDirectory,
                    System.getProperty("user.dir"),
                    "dir context must not mutate the controller JVM working directory",
                )
                "success"
            },
        )
    }

    // =============================================================================
    // DIR-S-002: thrown exception inside block still restores cwd
    // =============================================================================

    @Test
    fun `DIR-S-002 thrown exception inside block still restores cwd`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = DirExecutor(workspaceResolver = { _, _ -> workspace })

        val prevDir = System.getProperty("user.dir")
        val step = StepSpec.Dir(path = "sub", steps = emptyList())

        val exception = runCatching {
            executor.execute(
                stageName = "TestStage",
                stageIndex = 0,
                stepIndex = 0,
                step = step,
                action = { throw RuntimeException("boom") }
            )
        }.exceptionOrNull()

        assertNotNull(exception, "action exception must propagate")
        assertEquals("boom", exception?.message)
        // cwd must be restored even after exception
        assertEquals(prevDir, System.getProperty("user.dir"),
            "cwd must be restored after exception")
    }

    // =============================================================================
    // DIR-S-003: path traversal guard refuses ../escape
    // =============================================================================

    @Test
    fun `DIR-S-003 path traversal guard refuses escape`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = DirExecutor(workspaceResolver = { _, _ -> workspace })

        val prevDir = System.getProperty("user.dir")
        val step = StepSpec.Dir(path = "../etc", steps = emptyList())

        val exception = runCatching {
            executor.execute(
                stageName = "TestStage",
                stageIndex = 0,
                stepIndex = 0,
                step = step,
                action = { "should not reach" }
            )
        }.exceptionOrNull()

        assertTrue(exception is PathTraversalException,
            "must throw PathTraversalException, got: ${exception?.javaClass?.simpleName}")
        // cwd unchanged
        assertEquals(prevDir, System.getProperty("user.dir"),
            "cwd must not change after traversal rejection")
    }

    // =============================================================================
    // DIR-S-004: block-step flattening produces monotonic indices
    // =============================================================================

    @Test
    fun `DIR-S-004 block step flattening produces monotonic indices`() {
        // dir { withEnv { sh } } → [outerDir, innerWithEnv, innerSh]
        // (RetryBlock not yet defined; test dir nesting which IS available)
        val innerSh = StepSpec.Shell(command = "echo hi")
        val innerWithEnv = StepSpec.WithEnv(overrides = listOf("FOO=bar"), steps = listOf(innerSh))
        val outerDir = StepSpec.Dir(path = "a", steps = listOf(innerWithEnv))

        val flattened = BlockStepFlattener.flatten(outerDir)

        assertEquals(3, flattened.size, "flattened must have 3 steps")
        // Sequence: dir, withEnv, sh
        assertTrue(flattened[0].spec is StepSpec.Dir)
        assertTrue(flattened[1].spec is StepSpec.WithEnv)
        assertTrue(flattened[2].spec is StepSpec.Shell)

        // blockPath must be strictly monotonic
        val paths = flattened.map { it.blockPath }
        assertTrue(paths[0] < paths[1], "blockPath[0] < blockPath[1]: $paths")
        assertTrue(paths[1] < paths[2], "blockPath[1] < blockPath[2]: $paths")

        // inner sh carries blockPath="0.0"
        assertEquals("0.0", flattened[2].blockPath,
            "inner sh must carry blockPath 0.0")
    }

    @Test
    fun `DIR-S-004 Dir is handled in BlockStepFlattener recursion`() {
        // Verify StepSpec.Dir is in the flattener's when clause
        val dirStep = StepSpec.Dir(path = "x", steps = listOf(StepSpec.Echo("hello")))
        val flattened = BlockStepFlattener.flatten(dirStep)

        // Must recurse into Dir, producing dir + echo
        assertEquals(2, flattened.size)
        assertTrue(flattened[0].spec is StepSpec.Dir)
        assertTrue(flattened[1].spec is StepSpec.Echo)
        // Depth must increment for inner steps
        assertEquals(0, flattened[0].depth)
        assertEquals(1, flattened[1].depth)
    }

    @Test
    fun `DIR-S-004 nested dir depth increments correctly`() {
        // dir("a") { dir("b") { echo } }
        // BlockStepFlattener recurses into Dir → [outerDir, innerDir, innerEcho]
        // Root has blockPath="" (no parent path). Children get accumulated paths.
        val innerEcho = StepSpec.Echo("inner")
        val innerDir = StepSpec.Dir(path = "b", steps = listOf(innerEcho))
        val outerDir = StepSpec.Dir(path = "a", steps = listOf(innerDir))

        val flattened = BlockStepFlattener.flatten(outerDir)

        assertEquals(3, flattened.size, "flattened = [outerDir, innerDir, innerEcho]")
        assertEquals(0, flattened[0].depth.toLong(), "outer dir depth must be 0")
        assertEquals(1, flattened[1].depth.toLong(), "inner dir depth must be 1")
        assertEquals(2, flattened[2].depth.toLong(), "inner echo depth must be 2")
        // blockPath: root has no parent so "", first child of root gets "0"
        assertEquals("", flattened[0].blockPath, "root blockPath is empty (no parent)")
        assertEquals("0", flattened[1].blockPath, "inner dir is at path 0")
        assertEquals("0.0", flattened[2].blockPath, "inner echo is at path 0.0")
    }

    // =============================================================================
    // DIR-S-005: replay cursor resumes mid-dir block correctly (idempotent cd)
    // =============================================================================

    @Test
    fun `DIR-S-005 replay cursor resumes mid-dir block idempotently`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = DirExecutor(workspaceResolver = { _, _ -> workspace })

        val prevDir = System.getProperty("user.dir")
        val step = StepSpec.Dir(path = "sub", steps = emptyList())

        // Simulate replay: enter dir block, action returns "success"
        val result1 = executor.execute(
            stageName = "TestStage",
            stageIndex = 0,
            stepIndex = 0,
            step = step,
            action = { "success" }
        )
        assertEquals(workspace.toString(), result1.restoredPath)

        // Simulate resume: re-enter same dir block (idempotent cd)
        val result2 = executor.execute(
            stageName = "TestStage",
            stageIndex = 0,
            stepIndex = 0,
            step = step,
            action = { "success" }
        )

        // Both must succeed without changing the controller JVM directory.
        assertEquals("success", result2.outcome)
        assertEquals(prevDir, System.getProperty("user.dir"))
        assertEquals(workspace.toString(), result2.restoredPath)
    }

    @Test
    fun `DIR-S-005 restored path equals previous path on normal exit`(@TempDir tempDir: Path) {
        val workspace = tempDir.resolve("workspace/build-0")
        Files.createDirectories(workspace)
        val executor = DirExecutor(workspaceResolver = { _, _ -> workspace })

        val prevDir = System.getProperty("user.dir")
        val step = StepSpec.Dir(path = "sub", steps = emptyList())

        val result = executor.execute(
            stageName = "TestStage",
            stageIndex = 0,
            stepIndex = 0,
            step = step,
            action = { "success" }
        )

        assertEquals(result.previousPath, result.restoredPath,
            "DirResult.restoredPath must equal previousPath on normal exit")
    }

    // =============================================================================
    // DIR-S-006: Jenkins-verbatim signature reflection
    // =============================================================================

    @Test
    fun `DIR-S-006 StepSpec_Dir has correct Jenkins-verbatim signature`() {
        val kclass = StepSpec.Dir::class
        val primaryConstructor = kclass.primaryConstructor!!
        assertNotNull(primaryConstructor, "StepSpec.Dir must have a primary constructor")

        val params = primaryConstructor.parameters
        assertEquals(2, params.size, "StepSpec.Dir must have exactly 2 parameters")

        // Parameter order: path first, steps second (Jenkins catalog §1.2 line 63)
        assertEquals("path", params[0].name, "First parameter must be 'path'")
        assertEquals(String::class, params[0].type.classifier,
            "path parameter must be String type")

        assertEquals("steps", params[1].name, "Second parameter must be 'steps'")
        assertEquals(List::class, params[1].type.classifier,
            "steps parameter must be List<StepSpec> type")
    }
}
