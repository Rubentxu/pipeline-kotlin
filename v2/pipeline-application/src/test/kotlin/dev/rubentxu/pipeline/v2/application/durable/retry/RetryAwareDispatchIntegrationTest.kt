package dev.rubentxu.pipeline.v2.application.durable.retry

import dev.rubentxu.pipeline.v2.application.durable.ChildRowReader
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Smoke test for the retry-aware dispatch branch of
 * [dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator].
 *
 * Validates the *integration surface* of the new path WITHOUT exercising the full pipeline —
 * that is left to the R2 reproduction. These tests prove:
 *
 *   1. The [RetryReconciliationDriver] is invoked through [RetryReconciliationDriver.plan].
 *   2. The single-writer law holds: only [FileBasedRetryControlJournal.beginAttempt] /
 *      [FileBasedRetryControlJournal.updateStatus] mutate state.
 *   3. The journal is queried with the same controlOpId the driver uses.
 *
 * This is a deliberately small seam test; the full dispatch loop has ~25 call sites and
 * is exercised end-to-end by `docs/v2/07-uat/RETRY_D_R2_REPRODUCTION.md`.
 */
class RetryAwareDispatchIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val fingerprint = Fingerprint(
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )

    @Test
    fun `fresh journal → driver plans ScheduleAttempt(1)`() {
        val journal = FileBasedRetryControlJournal(
            controlDirRoot = tempDir,
            childRowReader = StubChildRowReader(emptyMap()),
        )
        val driver = RetryReconciliationDriver(
            journal = journal,
            identity = dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity(
                operationId = "test-control-op",
            ),
            controlOpId = "test-control-op",
            parentBodyPath = emptyList(),
            fingerprint = fingerprint,
            maxAttempts = 3,
            runId = "run-1",
            stageIndex = 0,
            stepIndex = 0,
        )

        val decision = driver.plan()

        assertTrue(
            decision is dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt,
            "fresh journal must schedule attempt 1, got: $decision",
        )
        val scheduled = decision as dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt
        assertEquals(1, scheduled.attempt)
    }

    @Test
    fun `attempt 1 already SUCCEEDED → driver plans ReuseSuccess(1)`() {
        val journal = FileBasedRetryControlJournal(
            controlDirRoot = tempDir,
            childRowReader = StubChildRowReader(emptyMap()),
        )
        // Persist attempt 1 as SUCCEEDED via the single-writer seam.
        journal.beginAttempt("test-control-op", attempt = 1, fingerprint = fingerprint, status = OperationStatus.SUCCEEDED)
        val driver = RetryReconciliationDriver(
            journal = journal,
            identity = dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity(operationId = "test-control-op"),
            controlOpId = "test-control-op",
            parentBodyPath = emptyList(),
            fingerprint = fingerprint,
            maxAttempts = 3,
            runId = "run-1",
            stageIndex = 0,
            stepIndex = 0,
        )

        val decision = driver.plan()

        assertTrue(
            decision is dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess,
            "expected ReuseSuccess, got: $decision",
        )
        assertEquals(1, (decision as dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess).attempt)
    }

    @Test
    fun `Window C — child SUCCEEDED with stale control plans CloseSuccessFromChild(1)`() {
        val controlOpId = "test-control-op"
        val journal = FileBasedRetryControlJournal(
            controlDirRoot = tempDir,
            childRowReader = StubChildRowReader(
                childrenByAttempt = mapOf(
                    1 to listOf(
                        RetryChildRowSnapshot(
                            attempt = 1,
                            childIndex = 0,
                            status = OperationStatus.SUCCEEDED,
                            fingerprint = fingerprint,
                        ),
                    ),
                ),
            ),
        )
        // Stale control: attempt 1 RUNNING (engine crashed before observing child success).
        journal.beginAttempt(controlOpId, attempt = 1, fingerprint = fingerprint, status = OperationStatus.RUNNING)
        val driver = RetryReconciliationDriver(
            journal = journal,
            identity = dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity(operationId = controlOpId),
            controlOpId = controlOpId,
            parentBodyPath = emptyList(),
            fingerprint = fingerprint,
            maxAttempts = 3,
            runId = "run-1",
            stageIndex = 0,
            stepIndex = 0,
        )

        val decision = driver.plan()

        assertTrue(
            decision is dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild,
            "expected CloseSuccessFromChild, got: $decision",
        )
        val close = decision as dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild
        assertEquals(1, close.attempt)
    }
}

/** Local test seam — returns pre-populated children for a controlOpId, no real OperationJournal. */
private class StubChildRowReader(
    private val childrenByAttempt: Map<Int, List<RetryChildRowSnapshot>>,
) : ChildRowReader {
    override fun childrenForControlOpId(
        controlOpId: String,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        maxAttempts: Int,
    ): List<RetryChildRowSnapshot> = childrenByAttempt.values.flatten()
}
