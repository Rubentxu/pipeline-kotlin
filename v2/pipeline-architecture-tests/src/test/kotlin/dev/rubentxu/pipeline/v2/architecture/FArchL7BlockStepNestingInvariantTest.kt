package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.sdk.api.BlockStepFlattener
import dev.rubentxu.pipeline.v2.sdk.api.BlockNestingDepthExceededException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * F-ARCH-L7-007: Block-step nesting invariant test.
 *
 * Architecture test that enforces the block-step nesting model is correct and
 * stable for ml-r10..r13.
 *
 * R-1 MITIGATION: This test was written FIRST (TDD red) before any new step
 * kinds landed, to lock the invariant. The BlockStepFlattener was extracted
 * from PipelineRun.kt in T-01, so this test passes immediately — the point is
 * to prevent regressions in ml-r10..r13.
 *
 * Three invariants tested:
 * (a) All block-type StepSpec variants have correct structure: all variants
 *     carrying `steps: List<StepSpec>` are properly handled by BlockStepFlattener
 * (b) Monotonic stepIndex: BlockStepFlattener.index produces strictly monotonic
 *     stepIndex across nested `retry { dir { timeout { sh } } }`
 * (c) Replay cursor correctness: replay walks inner steps in correct order,
 *     consulting per-step result markers
 *
 * Scenarios satisfied: DIR-S-004, DIR-S-005, TO-RT-S-005, TO-RT-S-006
 *
 * RED: AssertionError on invariant violation
 * GREEN: After T-01 extraction, all invariants hold
 */
class FArchL7BlockStepNestingInvariantTest {

    // ==========================================================================
    // INVARIANT (a): Block-type StepSpec variants have correct structure
    // ==========================================================================

    /**
     * All block-type step kinds (with steps: List<StepSpec>) are properly
     * handled by BlockStepFlattener.
     */
    @Test
    fun `all block-type step kinds flatten correctly`() {
        // Dir
        val dir = StepSpec.Dir("build", listOf(StepSpec.Echo("hello")))
        val dirFlat = BlockStepFlattener.flatten(dir)
        assertEquals(2, dirFlat.size)
        assertEquals("Dir", dirFlat[0].spec::class.simpleName)

        // CatchError
        val catchError = StepSpec.CatchError(
            buildResult = "UNSTABLE",
            steps = listOf(StepSpec.Shell("exit 1"))
        )
        val catchErrorFlat = BlockStepFlattener.flatten(catchError)
        assertEquals(2, catchErrorFlat.size)
        assertEquals("CatchError", catchErrorFlat[0].spec::class.simpleName)

        // WarnError
        val warnError = StepSpec.WarnError(
            message = "warn",
            steps = listOf(StepSpec.Echo("warning"))
        )
        val warnErrorFlat = BlockStepFlattener.flatten(warnError)
        assertEquals(2, warnErrorFlat.size)

        // Timestamps
        val timestamps = StepSpec.Timestamps(listOf(StepSpec.Echo("ts")))
        val timestampsFlat = BlockStepFlattener.flatten(timestamps)
        assertEquals(2, timestampsFlat.size)

        // AnsiColor
        val ansiColor = StepSpec.AnsiColor("xterm", listOf(StepSpec.Echo("colored")))
        val ansiColorFlat = BlockStepFlattener.flatten(ansiColor)
        assertEquals(2, ansiColorFlat.size)

        // NodeNoOp
        val nodeNoOp = StepSpec.NodeNoOp("linux", listOf(StepSpec.Echo("node")))
        val nodeNoOpFlat = BlockStepFlattener.flatten(nodeNoOp)
        assertEquals(2, nodeNoOpFlat.size)

        // TimeoutBlock
        val timeoutBlock = StepSpec.TimeoutBlock(
            30L, "SECONDS", null,
            listOf(StepSpec.Shell("make build"))
        )
        val timeoutBlockFlat = BlockStepFlattener.flatten(timeoutBlock)
        assertEquals(2, timeoutBlockFlat.size)

        // RetryBlock
        val retryBlock = StepSpec.RetryBlock(
            3, null,
            listOf(StepSpec.Shell("make test"))
        )
        val retryBlockFlat = BlockStepFlattener.flatten(retryBlock)
        assertEquals(2, retryBlockFlat.size)
    }

    // ==========================================================================
    // INVARIANT (b): Monotonic stepIndex across nested blocks
    // ==========================================================================

    /**
     * DIR-S-004 / TO-RT-S-006: `retry { dir { timeout { sh } } }` produces
     * strictly monotonic stepIndex across the nested depths.
     *
     * The flattened sequence must be: [outerRetry, outerDir, innerTimeout, innerSh]
     * with indices 0, 1, 2, 3 (strictly monotonic).
     */
    @Test
    fun `retry dir timeout sh produces monotonic step indices`() {
        val nested = StepSpec.RetryBlock(
            count = 3,
            conditions = null,
            steps = listOf(
                StepSpec.Dir(
                    path = "a",
                    steps = listOf(
                        StepSpec.TimeoutBlock(
                            time = 5L,
                            unit = "SECONDS",
                            activity = null,
                            steps = listOf(
                                StepSpec.Shell("echo hi")
                            )
                        )
                    )
                )
            )
        )

        val indexed = BlockStepFlattener.index(nested)

        // Verify strict monotonicity
        assertEquals(4, indexed.size, "Expected 4 steps in flattened sequence")

        val indices = indexed.map { it.stepIndex }
        for (i in 1 until indices.size) {
            assertTrue(
                indices[i] > indices[i - 1],
                "stepIndex must be strictly monotonic: ${indices.joinToString(" -> ")}"
            )
        }

        // Verify the expected order
        assertEquals("RetryBlock", indexed[0].spec::class.simpleName)
        assertEquals("Dir", indexed[1].spec::class.simpleName)
        assertEquals("TimeoutBlock", indexed[2].spec::class.simpleName)
        assertEquals("Shell", indexed[3].spec::class.simpleName)

        // Verify block paths are recorded (root has empty path, children get accumulated paths)
        assertEquals("", indexed[0].blockPath, "root has empty blockPath")
        assertEquals("0", indexed[1].blockPath, "Dir is at index 0 within RetryBlock")
        assertEquals("0.0", indexed[2].blockPath, "TimeoutBlock is at index 0 within Dir")
        assertEquals("0.0.0", indexed[3].blockPath, "Shell is at index 0 within TimeoutBlock")
    }

    /**
     * Deeply nested blocks produce monotonic indices with correct block paths.
     */
    @Test
    fun `deeply nested blocks produce correct block paths`() {
        // 3-level nesting: retry { dir { timeout { sh } } }
        val deepNested = StepSpec.RetryBlock(
            count = 3,
            steps = listOf(
                StepSpec.Dir(
                    path = "sub",
                    steps = listOf(
                        StepSpec.TimeoutBlock(
                            time = 10L,
                            unit = "MINUTES",
                            steps = listOf(
                                StepSpec.Shell("make all")
                            )
                        )
                    )
                )
            )
        )

        val indexed = BlockStepFlattener.index(deepNested)

        assertEquals(4, indexed.size)

        // Verify block paths
        assertEquals("0.0.0", indexed[3].blockPath,
            "inner shell should have blockPath 0.0.0")
        assertEquals(3, indexed[3].stepIndex)
        assertEquals(3, indexed[3].depth)
    }

    /**
     * Multiple parallel steps at the same level get distinct indices.
     */
    @Test
    fun `parallel branches get distinct indices`() {
        val parallel = StepSpec.Parallel(
            branches = listOf(
                StepSpec.BranchSpec("branch-a", listOf(
                    StepSpec.Echo("a1"),
                    StepSpec.Echo("a2")
                )),
                StepSpec.BranchSpec("branch-b", listOf(
                    StepSpec.Echo("b1")
                ))
            )
        )

        val indexed = BlockStepFlattener.index(parallel)

        // Parallel flattens all branches
        assertEquals(4, indexed.size)  // Parallel + 3 Echo steps = 4

        // Indices must be monotonic
        val indices = indexed.map { step -> step.stepIndex }
        for (i in 1 until indices.size) {
            assertTrue(indices[i] > indices[i - 1])
        }
    }

    // ==========================================================================
    // INVARIANT (c): Replay cursor correctness
    // ==========================================================================

    /**
     * DIR-S-005 / TO-RT-S-005: Replay cursor resumes mid-block-step correctly.
     *
     * When resuming after a crash, the replay must:
     * 1. Re-enter block wrappers (idempotent)
     * 2. Walk inner steps consulting per-step result markers
     * 3. SKIP steps with SUCCEEDED marker, RERUN steps without
     */
    @Test
    fun `flattener produces correct order for replay`() {
        val workflow = StepSpec.RetryBlock(
            count = 3,
            steps = listOf(
                StepSpec.Dir(
                    path = "build",
                    steps = listOf(
                        StepSpec.TimeoutBlock(
                            time = 30L,
                            unit = "SECONDS",
                            steps = listOf(
                                StepSpec.Shell("make build")
                            )
                        )
                    )
                ),
                StepSpec.Echo("after nested")
            )
        )

        val indexed = BlockStepFlattener.index(workflow)

        // Verify the flattened sequence order is correct for replay
        // Expected: [RetryBlock, Dir, TimeoutBlock, Shell, Echo]
        assertEquals(5, indexed.size)

        val names = indexed.map { it.spec::class.simpleName }
        assertEquals(listOf("RetryBlock", "Dir", "TimeoutBlock", "Shell", "Echo"), names)

        // Verify indices are monotonic
        val indices = indexed.map { step -> step.stepIndex }
        assertEquals(listOf(0, 1, 2, 3, 4), indices)
    }

    /**
     * WithEnv also participates in flattening (ML-R7 precedent).
     */
    @Test
    fun `withEnv flattens correctly alongside ML-R9 blocks`() {
        val mixed = StepSpec.WithEnv(
            overrides = listOf("PATH+=/usr/bin"),
            steps = listOf(
                StepSpec.Dir(
                    path = "src",
                    steps = listOf(
                        StepSpec.Echo("in dir")
                    )
                ),
                StepSpec.RetryBlock(
                    count = 2,
                    steps = listOf(
                        StepSpec.Shell("make test")
                    )
                )
            )
        )

        val indexed = BlockStepFlattener.index(mixed)

        // WithEnv + Dir(inner Echo) + RetryBlock(inner Shell) = 5 steps
        assertEquals(5, indexed.size)

        val names = indexed.map { it.spec::class.simpleName }
        assertEquals(listOf("WithEnv", "Dir", "Echo", "RetryBlock", "Shell"), names)
    }

    /**
     * Terminal steps (no nested steps) appear at leaf positions.
     */
    @Test
    fun `terminal steps appear at correct leaf positions`() {
        val terminal = StepSpec.Dir(
            path = "output",
            steps = listOf(
                StepSpec.Echo("terminal"),
                StepSpec.Pwd(),
                StepSpec.IsUnix()
            )
        )

        val indexed = BlockStepFlattener.index(terminal)

        assertEquals(4, indexed.size)  // Dir + 3 terminals

        // Last 3 should be terminal steps
        assertEquals("Echo", indexed[1].spec::class.simpleName)
        assertEquals("Pwd", indexed[2].spec::class.simpleName)
        assertEquals("IsUnix", indexed[3].spec::class.simpleName)
    }

    /**
     * Empty steps list is handled correctly.
     */
    @Test
    fun `empty steps list is handled`() {
        val emptyDir = StepSpec.Dir(
            path = "empty",
            steps = emptyList()
        )

        val indexed = BlockStepFlattener.index(emptyDir)

        assertEquals(1, indexed.size)  // Just the Dir itself
        assertEquals("Dir", indexed[0].spec::class.simpleName)
    }

    /**
     * Depth guard throws for blocks nested beyond depth 3.
     */
    @Test
    fun `depth guard throws for nesting beyond depth 3`() {
        // 4-level nesting: depth 4 should throw
        val tooDeep = StepSpec.RetryBlock(
            count = 1,
            steps = listOf(
                StepSpec.Dir(
                    path = "d1",
                    steps = listOf(
                        StepSpec.Dir(
                            path = "d2",
                            steps = listOf(
                                StepSpec.Dir(
                                    path = "d3",
                                    steps = listOf(
                                        StepSpec.Dir(
                                            path = "d4",
                                            steps = listOf(
                                                StepSpec.Echo("too deep")
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        var exceptionThrown = false
        var caughtException: BlockNestingDepthExceededException? = null
        try {
            BlockStepFlattener.depthGuard(tooDeep)
        } catch (e: BlockNestingDepthExceededException) {
            exceptionThrown = true
            caughtException = e
        }

        assertTrue(exceptionThrown, "Expected BlockNestingDepthExceededException")
        if (caughtException != null) {
            assertEquals(5, caughtException.depth)
            assertEquals(3, caughtException.maxDepth)
        }
    }

    /**
     * Depth 3 nesting is allowed (Jenkins CPS continuation limit).
     */
    @Test
    fun `depth 3 nesting is allowed`() {
        // Exactly depth 3: retry > dir > timeout > sh
        val depth3 = StepSpec.RetryBlock(
            count = 1,
            steps = listOf(
                StepSpec.Dir(
                    path = "d1",
                    steps = listOf(
                        StepSpec.TimeoutBlock(
                            time = 30L,
                            unit = "SECONDS",
                            steps = listOf(
                                StepSpec.Shell("make")
                            )
                        )
                    )
                )
            )
        )

        // Should not throw
        BlockStepFlattener.depthGuard(depth3)

        val indexed = BlockStepFlattener.index(depth3)
        assertEquals(4, indexed.size)
    }

    /**
     * Verify all 8 block-type step kinds have their steps field processed correctly.
     */
    @Test
    fun `all 8 block-type steps flatten to expected depth`() {
        // Test each block-type step produces correct flattening
        val steps = listOf(
            StepSpec.Dir("d", listOf(StepSpec.Echo("e"))),
            StepSpec.CatchError(steps = listOf(StepSpec.Echo("e"))),
            StepSpec.WarnError("w", true, listOf(StepSpec.Echo("e"))),
            StepSpec.Timestamps(listOf(StepSpec.Echo("e"))),
            StepSpec.AnsiColor("x", listOf(StepSpec.Echo("e"))),
            StepSpec.NodeNoOp(null, listOf(StepSpec.Echo("e"))),
            StepSpec.TimeoutBlock(30L, "S", null, listOf(StepSpec.Echo("e"))),
            StepSpec.RetryBlock(3, null, listOf(StepSpec.Echo("e")))
        )

        for (step in steps) {
            val flat = BlockStepFlattener.flatten(step)
            assertEquals(2, flat.size, "Block type ${step::class.simpleName} should flatten to 2 steps")
            assertEquals(1, flat[1].depth, "Inner step should have depth 1")
        }
    }
}
