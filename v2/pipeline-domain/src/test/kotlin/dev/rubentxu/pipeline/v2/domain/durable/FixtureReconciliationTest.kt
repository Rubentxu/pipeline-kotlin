package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * XCA-2C — characterization of the reconciliation function.
 *
 * The math:
 *   E ∩ O  -> ExpectedAndExecuted    (matched)
 *   E − O  -> ExpectedButNotExecuted (missing)
 *   O − E  -> ExecutedSupporting     (extra)
 *
 * These tests verify the function produces the correct classification for each case.
 */
@Timeout(30)
class FixtureReconciliationTest {

    private val echo = PluginStepId("core.echo")
    private val sh = PluginStepId("core.sh")
    private val error = PluginStepId("core.error")
    private val pwd = PluginStepId("core.pwd")

    private fun statusOf(vararg pairs: Pair<PluginStepId, OperationStatus>): Map<PluginStepId, OperationStatus> =
        pairs.toMap()

    // ─────────────────────────────────────────────────────────────────────────────
    // Basic cases
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty expected and empty observed`() {
        val result = reconcile(
            fixturePath = "test.pipeline.kts",
            executionState = FixtureExecutionState.HasEvidence,
            expectedStepKeys = emptySet(),
            observedStepKeys = emptySet(),
            observedByStatus = emptyMap(),
        )

        assertTrue(result.matched.isEmpty())
        assertTrue(result.expectedButNotExecuted.isEmpty())
        assertTrue(result.extraObserved.isEmpty())
        assertTrue(result.isFullyCovered)
    }

    @Test
    fun `E equals O — perfect match`() {
        val result = reconcile(
            fixturePath = "test.pipeline.kts",
            executionState = FixtureExecutionState.HasEvidence,
            expectedStepKeys = setOf(echo, sh),
            observedStepKeys = setOf(echo, sh),
            observedByStatus = statusOf(echo to OperationStatus.SUCCEEDED, sh to OperationStatus.SUCCEEDED),
        )

        assertEquals(setOf(echo, sh), result.matched)
        assertTrue(result.expectedButNotExecuted.isEmpty())
        assertTrue(result.extraObserved.isEmpty())
        assertTrue(result.isFullyCovered)
    }

    @Test
    fun `E subset of O — some steps were executed that were not declared`() {
        val result = reconcile(
            fixturePath = "test.pipeline.kts",
            executionState = FixtureExecutionState.HasEvidence,
            expectedStepKeys = setOf(echo),
            observedStepKeys = setOf(echo, sh, pwd),
            observedByStatus = statusOf(
                echo to OperationStatus.SUCCEEDED,
                sh to OperationStatus.SUCCEEDED,
                pwd to OperationStatus.SUCCEEDED,
            ),
        )

        assertEquals(setOf(echo), result.matched)
        assertTrue(result.expectedButNotExecuted.isEmpty())
        assertEquals(setOf(sh, pwd), result.extraObserved)
        assertTrue(result.isFullyCovered)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Missing: E − O
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `E minus O — some expected steps were not executed`() {
        val result = reconcile(
            fixturePath = "test.pipeline.kts",
            executionState = FixtureExecutionState.HasEvidence,
            expectedStepKeys = setOf(echo, sh, error),
            observedStepKeys = setOf(echo, sh),
            observedByStatus = statusOf(echo to OperationStatus.SUCCEEDED, sh to OperationStatus.SUCCEEDED),
        )

        assertEquals(setOf(echo, sh), result.matched)
        assertEquals(setOf(error), result.expectedButNotExecuted)
        assertTrue(result.extraObserved.isEmpty())
        assertFalse(result.isFullyCovered, "error was expected but not executed — must NOT be fully covered")
    }

    @Test
    fun `expected steps all missing — completely uncovered`() {
        val result = reconcile(
            fixturePath = "test.pipeline.kts",
            executionState = FixtureExecutionState.HasEvidence,
            expectedStepKeys = setOf(echo, sh),
            observedStepKeys = setOf(pwd),
            observedByStatus = statusOf(pwd to OperationStatus.SUCCEEDED),
        )

        assertTrue(result.matched.isEmpty())
        assertEquals(setOf(echo, sh), result.expectedButNotExecuted)
        assertEquals(setOf(pwd), result.extraObserved)
        assertFalse(result.isFullyCovered)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // FAILED status
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a FAILED step is still executed — isFullyCovered still holds`() {
        val result = reconcile(
            fixturePath = "test.pipeline.kts",
            executionState = FixtureExecutionState.HasEvidence,
            expectedStepKeys = setOf(echo, sh),
            observedStepKeys = setOf(echo, sh),
            observedByStatus = statusOf(
                echo to OperationStatus.SUCCEEDED,
                sh to OperationStatus.FAILED,
            ),
        )

        // FAILED is observed (execution started), so sh counts as covered
        val shRelation = result.stepRelations
            .filterIsInstance<StepExpectationRelation.ExpectedAndExecuted>()
            .find { it.stepKey == sh }

        assertEquals(OperationStatus.FAILED, shRelation?.status)
        assertTrue(result.isFullyCovered)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // FixtureExecutionState
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `HasEvidence with empty observed is still covered when no expectations`() {
        val result = reconcile(
            fixturePath = "test.pipeline.kts",
            executionState = FixtureExecutionState.HasEvidence,
            expectedStepKeys = emptySet(),
            observedStepKeys = emptySet(),
            observedByStatus = emptyMap(),
        )

        assertTrue(result.isFullyCovered)
    }

    @Test
    fun `NoEvidence — isFullyCovered is false when there are expectations`() {
        val result = FixtureReconciliation(
            fixturePath = "test.pipeline.kts",
            executionState = FixtureExecutionState.NoEvidence,
            stepRelations = listOf(
                StepExpectationRelation.ExpectedButNotExecuted(echo),
            ),
            observedStepKeys = emptySet(),
            expectedStepKeys = setOf(echo),
        )

        assertTrue(result.expectedButNotExecuted.contains(echo))
        assertFalse(result.isFullyCovered)
    }

    @Test
    fun `ExecutionFailed with a reason is distinguishable from NoEvidence`() {
        val failed = FixtureExecutionState.ExecutionFailed("compilation error: unexpected token")
        val noEvidence = FixtureExecutionState.NoEvidence

        assertTrue(failed is FixtureExecutionState.ExecutionFailed)
        assertTrue(noEvidence is FixtureExecutionState.NoEvidence)
        assertTrue(failed !is FixtureExecutionState.NoEvidence)
    }
}
