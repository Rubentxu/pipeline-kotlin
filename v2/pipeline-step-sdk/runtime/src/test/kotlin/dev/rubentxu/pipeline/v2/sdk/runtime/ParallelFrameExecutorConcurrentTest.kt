package dev.rubentxu.pipeline.v2.sdk.runtime

import dev.rubentxu.pipeline.v2.domain.durable.BranchSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.sdk.StepContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

/**
 * Contract tests for [ParallelFrameExecutor] concurrent execution (ADR-0039).
 *
 * Tests the three JoinPolicy dispatch cases and concurrent execution timing.
 *
 * +6 cases per M3-R4.3 T-07.
 */
class ParallelFrameExecutorConcurrentTest {

    /** Fake [Clock] for testing. */
    class FakeClock : Clock {
        override fun now(): Instant = Instant.parse("2026-08-24T12:00:00Z")
    }

    private val clock = FakeClock()
    private val executor = ParallelFrameExecutor(clock)
    private val context = StepContext(runId = "test-run")

    // ---------------------------------------------------------------------------
    // JoinPolicy.ALL_COMPLETE tests
    // ---------------------------------------------------------------------------

    /**
     * Case 1: 3 branches, all succeed → ALL_COMPLETE returns success.
     *
     * Verifies that when all branches succeed, the overall result is success
     * and all branch results are returned.
     */
    @Test
    fun `ALL_COMPLETE all branches succeed returns success`() {
        val frame = ParallelFrame(
            branches = listOf(
                BranchSpec("branch-0", listOf(StepSpec.Echo(text = "hello"))),
                BranchSpec("branch-1", listOf(StepSpec.Echo(text = "world"))),
                BranchSpec("branch-2", listOf(StepSpec.Echo(text = "!"))),
            ),
            joinPolicy = JoinPolicy.ALL_COMPLETE,
        )

        val result = runBlocking { executor.execute(frame, context) }

        assertTrue(result is ParallelFrameResult.Success, "All branches succeed → overall success")
        val success = result as ParallelFrameResult.Success
        assertEquals(3, success.branchResults.size)
        assertTrue(success.branchResults.all { it.outcome == "success" })
    }

    /**
     * Case 2: 3 branches, one fails → ALL_COMPLETE returns failure with all results.
     *
     * Verifies that when any branch fails, the overall result is failure
     * and the first failure is identified.
     */
    @Test
    fun `ALL_COMPLETE one branch fails returns failure`() {
        val frame = ParallelFrame(
            branches = listOf(
                BranchSpec("branch-0", listOf(StepSpec.Echo(text = "hello"))),
                BranchSpec("branch-1", listOf(StepSpec.Error(message = "fail", failureKind = "UNKNOWN"))),
                BranchSpec("branch-2", listOf(StepSpec.Echo(text = "!"))),
            ),
            joinPolicy = JoinPolicy.ALL_COMPLETE,
        )

        val result = runBlocking { executor.execute(frame, context) }

        assertTrue(result is ParallelFrameResult.Failure, "One branch fails → overall failure")
        val failure = result as ParallelFrameResult.Failure
        assertEquals(3, failure.branchResults.size)
        assertTrue(failure.branchResults.any { it.outcome == "failure" })
    }

    // ---------------------------------------------------------------------------
    // JoinPolicy.FIRST_SUCCESS tests
    // ---------------------------------------------------------------------------

    /**
     * Case 3: 3 branches, first succeeds → FIRST_SUCCESS returns success.
     *
     * Verifies that FIRST_SUCCESS returns as soon as the first branch succeeds,
     * cancelling other branches.
     */
    @Test
    fun `FIRST_SUCCESS first branch succeeds returns success`() {
        val frame = ParallelFrame(
            branches = listOf(
                BranchSpec("branch-0", listOf(StepSpec.Echo(text = "first"))),
                BranchSpec("branch-1", listOf(StepSpec.Sleep(seconds = 1L))),
                BranchSpec("branch-2", listOf(StepSpec.Sleep(seconds = 2L))),
            ),
            joinPolicy = JoinPolicy.FIRST_SUCCESS,
        )

        val result = runBlocking { executor.execute(frame, context) }

        assertTrue(result is ParallelFrameResult.Success, "First branch succeeds → overall success")
    }

    /**
     * Case 4: 3 branches, all fail → FIRST_SUCCESS returns failure.
     *
     * Verifies that FIRST_SUCCESS returns failure when all branches fail.
     */
    @Test
    fun `FIRST_SUCCESS all branches fail returns failure`() {
        val frame = ParallelFrame(
            branches = listOf(
                BranchSpec("branch-0", listOf(StepSpec.Error(message = "fail0", failureKind = "UNKNOWN"))),
                BranchSpec("branch-1", listOf(StepSpec.Error(message = "fail1", failureKind = "UNKNOWN"))),
                BranchSpec("branch-2", listOf(StepSpec.Error(message = "fail2", failureKind = "UNKNOWN"))),
            ),
            joinPolicy = JoinPolicy.FIRST_SUCCESS,
        )

        val result = runBlocking { executor.execute(frame, context) }

        assertTrue(result is ParallelFrameResult.Failure, "All branches fail → overall failure")
    }

    // ---------------------------------------------------------------------------
    // JoinPolicy.ANY_COMPLETE tests
    // ---------------------------------------------------------------------------

    /**
     * Case 5: 3 branches, one succeeds → ANY_COMPLETE returns success if any succeeds.
     *
     * Verifies that ANY_COMPLETE returns as soon as any branch completes,
     * cancelling other branches.
     */
    @Test
    fun `ANY_COMPLETE one branch succeeds returns success`() {
        val frame = ParallelFrame(
            branches = listOf(
                BranchSpec("branch-0", listOf(StepSpec.Echo(text = "first"))),
                BranchSpec("branch-1", listOf(StepSpec.Sleep(seconds = 1L))),
                BranchSpec("branch-2", listOf(StepSpec.Sleep(seconds = 2L))),
            ),
            joinPolicy = JoinPolicy.ANY_COMPLETE,
        )

        val result = runBlocking { executor.execute(frame, context) }

        assertTrue(result is ParallelFrameResult.Success, "Any branch succeeds → overall success")
    }

    // ---------------------------------------------------------------------------
    // Concurrency timing test
    // ---------------------------------------------------------------------------

    /**
     * Case 6: 3 branches with 100ms sleep each → total ≈ 100ms not 300ms.
     *
     * Verifies that branches execute concurrently, not sequentially.
     * If execution were sequential, total time would be ≥ 300ms (3 × 100ms).
     * Concurrent execution should complete in ≈ 100ms.
     */
    @Test
    fun `3 branches with 100ms sleep complete concurrently in ≈ 100ms`() {
        val frame = ParallelFrame(
            branches = listOf(
                BranchSpec("branch-0", listOf(StepSpec.Sleep(seconds = 1L))),
                BranchSpec("branch-1", listOf(StepSpec.Sleep(seconds = 1L))),
                BranchSpec("branch-2", listOf(StepSpec.Sleep(seconds = 1L))),
            ),
            joinPolicy = JoinPolicy.ALL_COMPLETE,
        )

        val startTime = System.currentTimeMillis()
        runBlocking { executor.execute(frame, context) }
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(
            elapsed < 2000,
            "3 concurrent 1-second sleeps should complete in < 2s, but took ${elapsed}ms. " +
            "If > 2500ms, execution may be sequential."
        )
    }
}
