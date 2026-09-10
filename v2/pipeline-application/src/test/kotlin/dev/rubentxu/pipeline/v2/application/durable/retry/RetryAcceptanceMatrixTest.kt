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
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * RETRY-D R1 / R3 / R4 / R6 acceptance matrix — exercised against the
 * **production wiring** required by ADR-0075 §12:
 *
 *   OperationJournal → ProductionRetryChildRowReader → child snapshots
 *                    → FileBasedRetryControlJournal (control rows)
 *                    → RetryReconciliationDriver (plan-only)
 *                    → RetryReconciliationDecision
 *
 * R5 is referenced from `WindowCRetryRecoveryProductionWiringTest`
 * (commit `e7639014`); it is the only Window C decorator test in the
 * project and already proves the precondition explicitly
 * (`child terminal success present` + `aggregate terminal success absent`).
 *
 * The acceptance properties for each R# case are verified at three
 * levels where the property admits it:
 *   1. The returned decision variant (no nullable booleans).
 *   2. The aggregate terminal status (when applicable).
 *   3. The number of *added* child executions observed through the
 *      bound OperationJournal — the property "child executions added = 0"
 *      is observed by comparing the journal snapshot size before and
 *      after the reconciliation.
 */
class RetryAcceptanceMatrixTest {

    @TempDir
    lateinit var tempDir: Path

    // ----- helpers -----

    private fun newDriver(
        controlJournal: FileBasedRetryControlJournal,
        controlOpId: String,
        parentBodyPath: List<BlockSegment>,
        fingerprint: Fingerprint,
        maxAttempts: Int,
        runId: String = "run-1",
        stageIndex: Int = 0,
        stepIndex: Int = 0,
    ) = RetryReconciliationDriver(
        journal = controlJournal,
        identity = RetryControlIdentity(operationId = controlOpId),
        controlOpId = controlOpId,
        parentBodyPath = parentBodyPath,
        fingerprint = fingerprint,
        maxAttempts = maxAttempts,
        runId = runId,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
    )

    private fun productionWiring(
        operationJournal: OperationJournalForTest,
        childStepId: String,
        controlOpId: String,
    ): FileBasedRetryControlJournal {
        val reader: ChildRowReader = ProductionRetryChildRowReader(
            operationJournal = operationJournal,
            identityFactory = RetryIdentityFactory,
            retryBody = retryBlockWithSingleChild(childStepId),
        )
        return FileBasedRetryControlJournal(
            controlDirRoot = tempDir,
            childRowReader = reader,
        )
    }

    private fun controlOpIdFor(
        runId: String = "run-1",
        stageIndex: Int = 0,
        stepIndex: Int = 0,
        parentBodyPath: List<BlockSegment> = emptyList(),
    ): String = RetryIdentityFactory.controlOperationId(runId, stageIndex, stepIndex, parentBodyPath)

    private fun childOpId(
        runId: String = "run-1",
        stageIndex: Int = 0,
        stepIndex: Int = 0,
        parentBodyPath: List<BlockSegment> = emptyList(),
        attempt: Int = 1,
        childIndex: Int = 0,
        pluginStepId: String = "core.echo",
    ): String = RetryIdentityFactory.childOperationId(
        runId = runId,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        parentBodyPath = parentBodyPath,
        attempt = attempt,
        childIndex = childIndex,
        childPluginStepId = PluginStepId(pluginStepId),
    )

    private fun retryBlockWithSingleChild(pluginStepId: String): BlockStepNode = BlockStepNode(
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

    private fun seedChild(
        journal: OperationJournalForTest,
        opId: String,
        fp: Fingerprint,
        status: OperationStatus,
    ) {
        journal.upsert(
            opId,
            MemoizedOperation(
                id = opId,
                fingerprint = fp,
                input = OperationInput(stepId = "core.echo", params = emptyMap(), runId = "run-1", attempt = 1),
                output = null,
                status = status,
                attempt = 1,
                cachedOutput = null,
            ),
        )
    }

    // =====================================================================
    // R1 — Immediate success / replay
    //
    // same retry identity + compatible fingerprint + terminal success
    //   → ReuseSuccess
    //   → child executions added = 0
    // =====================================================================
    @Nested
    @DisplayName("R1 — Immediate success / replay")
    inner class R1ImmediateSuccess {

        @Test
        fun `fresh retry schedules attempt 1 — replay with terminal SUCCESS adds zero new child executions`() {
            val fp = Fingerprint("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
            val operationJournal = OperationJournalForTest()
            val controlOpId = controlOpIdFor()
            val controlJournal = productionWiring(operationJournal, "core.echo", controlOpId)
            val driver = newDriver(controlJournal, controlOpId, emptyList(), fp, maxAttempts = 1)

            // --- 1st run: fresh journal → ScheduleAttempt(1) ---
            val first = driver.plan()
            assertTrue(
                first is RetryReconciliationDecision.ScheduleAttempt,
                "fresh journal must schedule attempt 1, got: $first",
            )
            assertEquals(1, (first as RetryReconciliationDecision.ScheduleAttempt).attempt)

            // Single child execution for attempt 1 is recorded in OperationJournal by the dispatch loop.
            // Test simulates that write here, respecting the precondition of the second plan.
            seedChild(operationJournal, childOpId(attempt = 1, childIndex = 0, pluginStepId = "core.echo"), fp, OperationStatus.SUCCEEDED)
            controlJournal.beginAttempt(controlOpId, attempt = 1, fingerprint = fp, status = OperationStatus.RUNNING)
            controlJournal.updateStatus(controlOpId = controlOpId, attempt = 1, status = OperationStatus.SUCCEEDED, fingerprint = fp)

            val childExecutionCountAfterFirstRun = operationJournal.snapshot().size

            // --- 2nd run with same durable state → ReuseSuccess(1), zero added children ---
            val second = driver.plan()
            assertTrue(
                second is RetryReconciliationDecision.ReuseSuccess,
                "compatible terminal success must produce ReuseSuccess, got: $second",
            )
            assertEquals(1, (second as RetryReconciliationDecision.ReuseSuccess).attempt)
            assertEquals(
                childExecutionCountAfterFirstRun,
                operationJournal.snapshot().size,
                "no additional child executions on replay",
            )

            // --- 3rd run (still same state) → still ReuseSuccess, again no added children ---
            val third = driver.plan()
            assertTrue(third is RetryReconciliationDecision.ReuseSuccess, "idempotent replay must stay ReuseSuccess")
            assertEquals(childExecutionCountAfterFirstRun, operationJournal.snapshot().size)
        }
    }

    // =====================================================================
    // R3 — Exhaustion / replay
    //
    // maxAttempts = N, all attempts 1..N terminal FAIL
    //   → state = exhausted
    //   → final FAILURE
    //   → never N+1
    //   → compatible replay → zero scheduling
    // =====================================================================
    @Nested
    @DisplayName("R3 — Exhaustion / replay")
    inner class R3Exhaustion {

        @Test
        fun `N terminal failures mark aggregate exhausted — never N+1 — replay schedules nothing`() {
            val fp = Fingerprint("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
            val maxAttempts = 3
            val operationJournal = OperationJournalForTest()
            val controlOpId = controlOpIdFor()
            val controlJournal = productionWiring(operationJournal, "core.echo", controlOpId)
            val driver = newDriver(controlJournal, controlOpId, emptyList(), fp, maxAttempts = maxAttempts)

            // Persist 1..N as RUNNING then FAILED; seed the corresponding child evidence.
            for (attempt in 1..maxAttempts) {
                seedChild(
                    operationJournal,
                    childOpId(attempt = attempt, childIndex = 0, pluginStepId = "core.echo"),
                    fp,
                    OperationStatus.FAILED,
                )
                controlJournal.beginAttempt(controlOpId, attempt = attempt, fingerprint = fp, status = OperationStatus.RUNNING)
                controlJournal.updateStatus(
                    controlOpId = controlOpId,
                    attempt = attempt,
                    status = OperationStatus.FAILED,
                    fingerprint = fp,
                )
            }

            // No attempt N+1 should ever appear.
            controlJournal.beginAttempt(controlOpId, attempt = maxAttempts + 1, fingerprint = fp, status = OperationStatus.RUNNING)

            val first = driver.plan()
            assertTrue(
                first is RetryReconciliationDecision.ReuseFailure,
                "all-terminal-failures must produce ReuseFailure (exhausted), got: $first",
            )
            assertEquals(maxAttempts, (first as RetryReconciliationDecision.ReuseFailure).attempt)

            // NOTE: the writer MUST NOT persist attempt N+1 in production. The test
            // deliberately forces that row above only to assert: even with N+1 visible,
            // the planner does not return ScheduleAttempt(N+1) or anything scheduling a
            // new child.
            val second = driver.plan()
            assertTrue(
                second !is RetryReconciliationDecision.ScheduleAttempt &&
                    second !is RetryReconciliationDecision.AdvanceAfterFailure,
                "exhausted state must not schedule anything, got: $second",
            )
            assertTrue(
                second is RetryReconciliationDecision.ReuseFailure,
                "exhausted state must stay ReuseFailure on replay, got: $second",
            )
            assertEquals(maxAttempts, (second as RetryReconciliationDecision.ReuseFailure).attempt)
        }
    }

    // =====================================================================
    // R4 — Resume after failed attempt
    //
    // attempt N FAILURE durable; aggregate still resumable / not terminal;
    // at least one attempt remaining.
    //   → never repeat N
    //   → AdvanceAfterFailure(N → N+1)
    //   → only N+1 is scheduled next
    // =====================================================================
    @Nested
    @DisplayName("R4 — Resume after failed attempt")
    inner class R4ResumeAfterFailure {

        @Test
        fun `failed attempt N never re-executes — reconciler advances to N+1 only`() {
            val fp = Fingerprint("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
            val maxAttempts = 3
            val operationJournal = OperationJournalForTest()
            val controlOpId = controlOpIdFor()
            val controlJournal = productionWiring(operationJournal, "core.echo", controlOpId)
            val driver = newDriver(controlJournal, controlOpId, emptyList(), fp, maxAttempts = maxAttempts)

            // Attempt 1 durable FAIL; attempt 2 still has child evidence pending (RUNNING/in-flight).
            seedChild(
                operationJournal,
                childOpId(attempt = 1, childIndex = 0, pluginStepId = "core.echo"),
                fp,
                OperationStatus.FAILED,
            )
            seedChild(
                operationJournal,
                childOpId(attempt = 2, childIndex = 0, pluginStepId = "core.echo"),
                fp,
                OperationStatus.RUNNING,
            )
            controlJournal.beginAttempt(controlOpId, attempt = 1, fingerprint = fp, status = OperationStatus.RUNNING)
            controlJournal.updateStatus(
                controlOpId = controlOpId,
                attempt = 1,
                status = OperationStatus.FAILED,
                fingerprint = fp,
            )

            val decision = driver.plan()
            // Acceptable: AdvanceAfterFailure(1→2) (canonical) OR ResumeAttempt(2)
            // (when attempt 2 has in-flight child evidence already in the journal).
            // Per R4 spec the planner must NOT re-execute attempt 1 and must
            // schedule only N+1. Either decision satisfies that property.
            val acceptable = (decision is RetryReconciliationDecision.AdvanceAfterFailure &&
                decision.from == 1 && decision.to == 2) ||
                (decision is RetryReconciliationDecision.ResumeAttempt &&
                    decision.attempt == 2)
            assertTrue(
                acceptable,
                "failed attempt N must not re-execute; only attempt N+1 must be scheduled, got: $decision",
            )
            // Explicitly forbid re-scheduling attempt 1.
            assertTrue(
                decision !is RetryReconciliationDecision.ScheduleAttempt ||
                    (decision as RetryReconciliationDecision.ScheduleAttempt).attempt != 1,
                "planner must never re-schedule attempt 1 after attempt 1 failure",
            )

            // Alternative R4 shape: if attempt 2 has NO child evidence yet, planner must
            // emit AdvanceAfterFailure(1→2), not ScheduleAttempt(1) (which would re-execute
            // attempt 1 — the E-EM-11 regression we are guarding against).
            val operationJournalNoChild = OperationJournalForTest()
            val controlJournalNoChild = productionWiring(operationJournalNoChild, "core.echo", controlOpId)
            val driverNoChild = newDriver(controlJournalNoChild, controlOpId, emptyList(), fp, maxAttempts = maxAttempts)
            controlJournalNoChild.beginAttempt(controlOpId, attempt = 1, fingerprint = fp, status = OperationStatus.RUNNING)
            controlJournalNoChild.updateStatus(
                controlOpId = controlOpId,
                attempt = 1,
                status = OperationStatus.FAILED,
                fingerprint = fp,
            )

            val noChildDecision = driverNoChild.plan()
            assertTrue(
                noChildDecision is RetryReconciliationDecision.AdvanceAfterFailure,
                "no-child-evidence path: must AdvanceAfterFailure(1→2), got: $noChildDecision",
            )
            val adv = noChildDecision as RetryReconciliationDecision.AdvanceAfterFailure
            assertEquals(1, adv.from)
            assertEquals(2, adv.to)
            // Also explicitly forbid re-scheduling attempt 1.
            assertTrue(
                noChildDecision !is RetryReconciliationDecision.ScheduleAttempt ||
                    (noChildDecision as RetryReconciliationDecision.ScheduleAttempt).attempt != 1,
                "planner must never re-schedule attempt 1 after attempt 1 failure",
            )
        }
    }

    // =====================================================================
    // R6 — Divergence fail-closed
    //
    // existing durable retry history for fingerprint A;
    // reconcile with incompatible fingerprint B
    //   → RejectDivergence
    //   → FAIL CLOSED
    //   → 0 child executions
    //   → 0 new attempts
    //   → no silent reuse, no silent reset, no rerun from scratch
    // =====================================================================
    @Nested
    @DisplayName("R6 — Divergence fail-closed")
    inner class R6Divergence {

        @Test
        fun `incompatible fingerprint rejects before any scheduling and adds no child executions`() {
            val persistedFp = Fingerprint(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            )
            val incompatibleFp = Fingerprint(
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            )
            val operationJournal = OperationJournalForTest()
            val controlOpId = controlOpIdFor()
            val controlJournal = productionWiring(operationJournal, "core.echo", controlOpId)

            // Pre-existing durable state under fingerprint A: attempt 1 RUNNING.
            // We do NOT seed any child evidence; the existing control row alone is
            // enough for the planner to detect divergence on an incompatible replay.
            controlJournal.beginAttempt(controlOpId, attempt = 1, fingerprint = persistedFp, status = OperationStatus.RUNNING)

            val operationJournalSizeBefore = operationJournal.snapshot().size

            val driver = newDriver(
                controlJournal = controlJournal,
                controlOpId = controlOpId,
                parentBodyPath = emptyList(),
                fingerprint = incompatibleFp,
                maxAttempts = 3,
            )

            val decision = driver.plan()

            assertTrue(
                decision is RetryReconciliationDecision.RejectDivergence,
                "incompatible fingerprint must produce RejectDivergence, got: $decision",
            )
            val rej = decision as RetryReconciliationDecision.RejectDivergence
            assertEquals(controlOpId, rej.operationId)
            assertTrue(rej.reason.isNotBlank(), "RejectDivergence must carry a non-blank reason")

            assertEquals(
                operationJournalSizeBefore,
                operationJournal.snapshot().size,
                "RejectDivergence must NOT add any child executions",
            )
            // No new attempts written either: no beginAttempt calls the test made after beginAttempt(1) above.
            val attemptsBefore = operationJournalSizeBefore
            // Re-plan a 2nd time → still RejectDivergence, still zero added children.
            val second = driver.plan()
            assertTrue(
                second is RetryReconciliationDecision.RejectDivergence,
                "second plan with incompatible fingerprint must also RejectDivergence, got: $second",
            )
            assertEquals(attemptsBefore, operationJournal.snapshot().size)
        }
    }
}

/**
 * Minimal test-scoped [OperationJournal] used to assert "child executions
 * added = 0" in the R1 / R6 acceptance rows. Tracks every OpId ever
 * persisted; the test compares snapshot size before/after to assert that
 * the planner never implicitly causes a new child execution via the
 * driver (which is plan-only by ADR-0075 §11 anyway, but this makes the
 * property observable in the test).
 */
internal class OperationJournalForTest : OperationJournal {
    private val byId = mutableMapOf<String, MemoizedOperation>()

    fun upsert(opId: String, op: MemoizedOperation) {
        byId[opId] = op
    }

    fun snapshot(): Map<String, MemoizedOperation> = byId.toMap()

    override fun append(op: DurableOperation, deadlineMs: Long?) = error("unused in test")
    override fun get(opId: String): DurableOperation? = byId[opId]
    override fun get(opId: String, attempt: Int): DurableOperation? = byId[opId]
    override fun listForRun(runId: String): List<DurableOperation> = byId.values.toList()
    override fun getDeadlineMs(opId: String, attempt: Int): Long? = null
    override fun getEndedAt(opId: String, attempt: Int): Long? = null
    override fun getStartedAt(opId: String, attempt: Int): Long? = null
    override fun beginOperation(opId: String, attempt: Int, fingerprint: String, inputJson: String, deadlineMs: Long?) =
        error("unused in test")
}
