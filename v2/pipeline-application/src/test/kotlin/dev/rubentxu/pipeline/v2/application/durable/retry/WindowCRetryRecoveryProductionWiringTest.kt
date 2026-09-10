package dev.rubentxu.pipeline.v2.application.durable.retry

import dev.rubentxu.pipeline.v2.application.durable.ChildRowReader
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * RETRY-D D2.6 → Window C (production wiring) — recovery from a crash between child
 * terminal persistence and retry aggregate terminal persistence.
 *
 * Frozen rules being verified:
 *
 *   10. Window C precondition: child terminal SUCCESS present + retry aggregate
 *       terminal absent.
 *   11. Fault injection — TEST ONLY. We do NOT sleep or race the dispatch loop;
 *       we seed the precondition directly into the production wiring stores,
 *       then exercise the production reconciliation chain:
 *
 *           OperationJournal → ProductionRetryChildRowReader
 *                              → RetryChildRowSnapshot list
 *                              → RetryReconciler
 *                              → RetryReconciliationDecision
 *
 *   12. Production wiring. Reconciler MUST NOT know OperationJournal.
 *
 *   Acceptance assertion (Rule 10): the resulting decision is
 *       `CloseSuccessFromChild(1)`. A subsequent re-plan returns `ReuseSuccess(1)`,
 *       proving the recovered aggregate terminal row causes zero additional child
 *       executions on subsequent coordinator invocations.
 */
class WindowCRetryRecoveryProductionWiringTest {

    @TempDir
    lateinit var tempDir: Path

    private val fingerprint = Fingerprint(
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )

    @Test
    fun `Window C production wiring — precondition proven then CloseSuccessFromChild(1) returned`() {
        // ---- Precondition: child terminal SUCCESS persisted in OperationJournal,
        //      retry aggregate still RUNNING (i.e. "crash" between the two persistence
        //      points in Rule 11).
        val runId = "run-1"
        val stageIndex = 0
        val stepIndex = 0
        val parentBodyPath = emptyList<BlockSegment>()
        val attempt = 1
        val childIndex = 0
        val childPluginStepId = "core.sh"
        val controlOpId = RetryIdentityFactory.controlOperationId(runId, stageIndex, stepIndex, parentBodyPath)
        val childOpId = RetryIdentityFactory.childOperationId(
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            parentBodyPath = parentBodyPath,
            attempt = attempt,
            childIndex = childIndex,
            childPluginStepId = PluginStepId(childPluginStepId),
        )

        val journal = InMemoryOperationJournalForWindowC().also { opJournal ->
            opJournal.upsert(
                opId = childOpId,
                op = MemoizedOperation(
                    id = childOpId,
                    fingerprint = fingerprint,
                    input = OperationInput(stepId = childPluginStepId, params = emptyMap(), runId = runId, attempt = attempt),
                    output = null,
                    status = OperationStatus.SUCCEEDED,
                    attempt = attempt,
                    cachedOutput = null,
                ),
            )
        }

        val childRowReader: ChildRowReader = ProductionRetryChildRowReader(
            operationJournal = journal,
            identityFactory = RetryIdentityFactory,
            retryBody = retryBodyWithAtomic(childPluginStepId),
        )

        val controlJournal = FileBasedRetryControlJournal(
            controlDirRoot = tempDir,
            childRowReader = childRowReader,
        ).also {
            // "Crash" cut: child terminal SUCCEEDED already above; aggregate is RUNNING.
            it.beginAttempt(controlOpId, attempt = attempt, fingerprint = fingerprint, status = OperationStatus.RUNNING)
        }

        val snapshots: List<RetryChildRowSnapshot> = childRowReader.childrenForControlOpId(
                controlOpId = controlOpId,
                runId = runId,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                parentBodyPath = parentBodyPath,
                maxAttempts = 1,
            )

        // Precondition must be PROVEN before asserting recovery (Rule 11).
        val child = snapshots.single { it.attempt == attempt }
        assertEquals(OperationStatus.SUCCEEDED, child.status, "precondition: child must be SUCCEEDED")
        assertEquals(1, snapshots.size, "1 attempt × 1 body child")
        assertEquals(0, child.childIndex)

        // Run the production reconciliation chain.
        val driver = RetryReconciliationDriver(
            journal = controlJournal,
            identity = RetryControlIdentity(operationId = controlOpId),
            controlOpId = controlOpId,
            parentBodyPath = parentBodyPath,
            fingerprint = fingerprint,
            maxAttempts = 1,
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
        )

        val decision = driver.plan()

        assertTrue(
            decision is RetryReconciliationDecision.CloseSuccessFromChild,
            "Window C must produce CloseSuccessFromChild, got: $decision",
        )
        assertEquals(1, (decision as RetryReconciliationDecision.CloseSuccessFromChild).attempt)

        // Second planner invocation simulates a re-run of dispatchBody with the same precondition.
        // After CloseSuccessFromChild, the aggregate row's attempt 1 is terminal SUCCEEDED,
        // so re-planning MUST return ReuseSuccess (i.e. NO additional child execution).
        controlJournal.updateStatus(
            controlOpId = controlOpId,
            attempt = attempt,
            status = OperationStatus.SUCCEEDED,
            fingerprint = fingerprint,
        )

        val secondDecision = driver.plan()
        assertTrue(
            secondDecision is RetryReconciliationDecision.ReuseSuccess,
            "after CloseSuccessFromChild the aggregate terminal row causes ReuseSuccess on re-plan, got: $secondDecision",
        )
    }

    // ---------- helpers ----------

    private fun retryBodyWithAtomic(pluginStepId: String): BlockStepNode = BlockStepNode(
        id = StepId("retry-block"),
        pluginStepId = PluginStepId("core.retry"),
        payload = VersionedStepPayload("dsl-v1", "{}"),
        body = listOf(
            OpaqueStepNode(
                id = StepId("step-$pluginStepId"),
                pluginStepId = PluginStepId(pluginStepId),
                payload = VersionedStepPayload("dsl-v1", "{}"),
            ),
        ),
    )
}

/** Minimal OperationJournal stub used only by Window C tests. Point-lookup is sufficient. */
private class InMemoryOperationJournalForWindowC : OperationJournal {
    private val byId = mutableMapOf<String, MemoizedOperation>()

    fun upsert(opId: String, op: MemoizedOperation) {
        byId[opId] = op
    }

    override fun append(op: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation, deadlineMs: Long?) = error("unused")
    override fun get(opId: String): dev.rubentxu.pipeline.v2.domain.durable.DurableOperation? = byId[opId]
    override fun get(opId: String, attempt: Int): dev.rubentxu.pipeline.v2.domain.durable.DurableOperation? = byId[opId]
    override fun listForRun(runId: String): List<dev.rubentxu.pipeline.v2.domain.durable.DurableOperation> = byId.values.toList()
    override fun getDeadlineMs(opId: String, attempt: Int): Long? = null
    override fun getEndedAt(opId: String, attempt: Int): Long? = null
    override fun getStartedAt(opId: String, attempt: Int): Long? = null
    override fun beginOperation(opId: String, attempt: Int, fingerprint: String, inputJson: String, deadlineMs: Long?) =
        error("unused")
}
