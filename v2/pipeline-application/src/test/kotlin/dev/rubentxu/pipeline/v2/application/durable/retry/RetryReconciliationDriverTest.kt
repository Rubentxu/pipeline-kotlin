package dev.rubentxu.pipeline.v2.application.durable.retry

import dev.rubentxu.pipeline.v2.application.durable.ChildRowReader
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.NullChildRowReader
import dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * RED-first tests for [RetryReconciliationDriver] — the orchestration
 * that ties together the durable control journal and the pure reconciler
 * into a single decision tree.
 *
 * ADR-0075 §4, §6, §7 — the driver is the narrow seam that:
 *  - reads the durable state,
 *  - builds the [dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationInput],
 *  - calls the pure [dev.rubentxu.pipeline.v2.domain.durable.RetryReconciler],
 *  - returns the [dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision].
 *
 * It does NOT mutate the journal directly — that's the caller's
 * responsibility. The driver is the dispatch thinker, not the writer.
 */
class RetryReconciliationDriverTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "run-x"
    private val stageIndex = 0
    private val stepIndex = 2
    private val parentPath = listOf(dev.rubentxu.pipeline.v2.domain.BlockSegment(0, PluginStepId("dir")))
    private val controlOpId by lazy {
        RetryIdentityFactory.controlOperationId(runId, stageIndex, stepIndex, parentPath)
    }
    private val fingerprint: Fingerprint = Fingerprint.compute(
        dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            "core.retry", emptyMap(), runId, 1,
        ),
        "core.retry",
        dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
        1,
    )
    private val identity by lazy {
        dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity(operationId = controlOpId, schemaVersion = 1)
    }

    private fun driver(
        presetControlStatuses: Map<Int, OperationStatus> = emptyMap(),
        childrenByAttempt: Map<Int, List<RetryChildRowSnapshot>> = emptyMap(),
        maxAttempts: Int = 3,
    ): Pair<RetryReconciliationDriver, FileBasedRetryControlJournal> {
        val journal = FileBasedRetryControlJournal(
            controlDirRoot = tempDir,
            childRowReader = StaticChildRowReader(
                childrenByAttempt = childrenByAttempt,
            ),
        )
        // Pre-populate the control file from the fixture so the driver reads the
        // exact retry state we want it to plan against.
        presetControlStatuses.forEach { (attempt, status) ->
            journal.beginAttempt(
                controlOpId = controlOpId,
                attempt = attempt,
                fingerprint = fingerprint,
                status = status,
            )
        }
        return RetryReconciliationDriver(
            journal = journal,
            identity = identity,
            controlOpId = controlOpId,
            parentBodyPath = parentPath,
            fingerprint = fingerprint,
            maxAttempts = maxAttempts,
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
        ) to journal
    }

    @Nested
    @DisplayName("Plan only — does NOT mutate journal")
    inner class PlanOnly {
        @Test
        fun `fresh retry plans ScheduleAttempt 1`() {
            val (d, journal) = driver()
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt(1),
                d.plan(),
            )
            // Plan MUST NOT mutate.
            val post = journal.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, fingerprint)
            assertTrue(post.controlRows.isEmpty(), "plan() MUST NOT persist control rows")
        }

        @Test
        fun `aggregate already SUCCEEDED plans ReuseSuccess without mutation`() {
            val (d, journal) = driver(presetControlStatuses = mapOf(2 to OperationStatus.SUCCEEDED))
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess(2),
                d.plan(),
            )
            val post = journal.readState(controlOpId, runId, stageIndex, stepIndex, parentPath, 3, fingerprint)
            // No mutation.
            assertEquals(1, post.controlRows.size)
            assertEquals(OperationStatus.SUCCEEDED, post.controlRows.first().status)
        }

        @Test
        fun `terminal failure plans ReuseFailure without mutation`() {
            val (d, _) = driver(presetControlStatuses = mapOf(2 to OperationStatus.FAILED))
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseFailure(2),
                d.plan(),
            )
        }

        @Test
        fun `Window C — child SUCCEEDED with stale control plans CloseSuccessFromChild`() {
            val (d, _) = driver(
                childrenByAttempt = mapOf(
                    1 to listOf(
                        RetryChildRowSnapshot(attempt = 1, childIndex = 0, status = OperationStatus.SUCCEEDED),
                    ),
                ),
            )
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild(1),
                d.plan(),
            )
        }
    }

    @Nested
    @DisplayName("Compose — driver delegates to the Reconciler unchanged")
    inner class Compose {
        @Test
        fun `child fingerprint divergence surfaces as RejectDivergence`() {
            val mismatched = Fingerprint("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
            // Pre-populate the journal directly with a mismatched fingerprint.
            val journal = FileBasedRetryControlJournal(
                controlDirRoot = tempDir,
                childRowReader = StaticChildRowReader(childrenByAttempt = emptyMap()),
            )
            journal.beginAttempt(controlOpId, attempt = 1, fingerprint = mismatched, status = OperationStatus.RUNNING)
            val d = RetryReconciliationDriver(
                journal = journal,
                identity = identity,
                controlOpId = controlOpId,
                parentBodyPath = parentPath,
                fingerprint = fingerprint,
                maxAttempts = 3,
                runId = runId,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
            )
            val decision = d.plan()
            assertTrue(decision is dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.RejectDivergence)
        }
    }
}

/**
 * Test fixture for [ChildRowReader] — returns the caller-supplied control
 * rows + child rows, ignoring other inputs.
 */
private class StaticChildRowReader(
    private val childrenByAttempt: Map<Int, List<RetryChildRowSnapshot>>,
) : ChildRowReader {
    override fun childrenForControlOpId(
        controlOpId: String,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        maxAttempts: Int,
    ): List<RetryChildRowSnapshot> =
        childrenByAttempt.values.flatten()
}

/**
 * Convenience: compile the canonical op id from a retry context. Mirrors
 * [RetryIdentityFactory.controlOperationId] for in-test sanity checks.
 */
private fun canonicalOpId(runId: String, stageIndex: Int, stepIndex: Int, parentPath: List<BlockSegment>): String =
    OpId(runId, stageIndex, stepIndex, branchIndex = null, bodyPath = parentPath).format()
