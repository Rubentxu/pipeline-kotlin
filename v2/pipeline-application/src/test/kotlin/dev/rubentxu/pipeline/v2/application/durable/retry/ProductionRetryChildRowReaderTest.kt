package dev.rubentxu.pipeline.v2.application.durable.retry

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
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * RETRY-D D2.6 — focused tests for [ProductionRetryChildRowReader].
 *
 * Validates the *laws* of the reader (per ADR-0075 §7):
 *   - Missing / InFlight / TerminalSuccess / TerminalFailure are distinct.
 *   - Attempt ordinals are isolated (attempt 1/child X ≠ attempt 2/child X).
 *   - Retry-instance identities are isolated (retry A/attempt 1/child X ≠ retry B/attempt 1/child X).
 *   - Multiple body children are enumerated.
 *   - The reader never walks StepSpec; it consumes the canonical body description.
 *   - The reader never branches on StepKey (no `if (step.pluginStepId == "core.sh") ...`).
 *
 * The reader is the single port between OperationJournal and RetryReconciler;
 * the reconciler is not exercised here (already covered by RetryReconcilerTest).
 */
class ProductionRetryChildRowReaderTest {

    private val identityFactory = RetryIdentityFactory
    private val fingerprint: Fingerprint = Fingerprint.compute(
        input = OperationInput(stepId = "core.echo", params = emptyMap(), runId = "test", attempt = 1),
        stepId = "core.echo",
        replayPolicy = ReplayPolicy.MEMOIZED,
        attempt = 1,
    )

    @Nested
    @DisplayName("Missing / InFlight / TerminalSuccess / TerminalFailure")
    inner class StatusMapping {

        @Test
        fun `missing child → PENDING snapshot (not failed, not started)`() {
            val journal = InMemoryOperationJournal()
            val reader = ProductionRetryChildRowReader(journal, identityFactory, retryBodyWith("core.echo"))

            val snapshots = reader.childrenForControlOpId(
                controlOpId = "ctrl-1",
                runId = "run-1",
                stageIndex = 0,
                stepIndex = 0,
                parentBodyPath = emptyList(),
                maxAttempts = 3,
            )

            assertEquals(3, snapshots.size) // 3 attempts × 1 child
            snapshots.forEach {
                assertEquals(OperationStatus.PENDING, it.status, "missing child must surface as PENDING")
                assertNull(it.fingerprint, "PENDING rows must not carry a fingerprint")
            }
        }

        @Test
        fun `in-flight child → RUNNING snapshot`() {
            val journal = InMemoryOperationJournal()
            val body = retryBodyWith("core.echo")
            seedChild(journal, "run-1", 0, 0, emptyList(), 1, 0, "core.echo", OperationStatus.RUNNING, fingerprint)

            val snapshots = ProductionRetryChildRowReader(journal, identityFactory, body)
                .childrenForControlOpId("ctrl-1", "run-1", 0, 0, emptyList(), 3)

            val attempt1Child = snapshots.first { it.attempt == 1 }
            assertEquals(OperationStatus.RUNNING, attempt1Child.status)
            assertEquals(fingerprint, attempt1Child.fingerprint)
        }

        @Test
        fun `terminal success → SUCCEEDED snapshot`() {
            val journal = InMemoryOperationJournal()
            val body = retryBodyWith("core.echo")
            seedChild(journal, "run-1", 0, 0, emptyList(), 2, 0, "core.echo", OperationStatus.SUCCEEDED, fingerprint)

            val snapshots = ProductionRetryChildRowReader(journal, identityFactory, body)
                .childrenForControlOpId("ctrl-1", "run-1", 0, 0, emptyList(), 3)

            val attempt2Child = snapshots.first { it.attempt == 2 }
            assertEquals(OperationStatus.SUCCEEDED, attempt2Child.status)
            assertTrue(attempt2Child.isSuccess)
            assertTrue(attempt2Child.isTerminal)
        }

        @Test
        fun `terminal failure → FAILED snapshot (distinct from missing)`() {
            val journal = InMemoryOperationJournal()
            val body = retryBodyWith("core.echo")
            seedChild(journal, "run-1", 0, 0, emptyList(), 3, 0, "core.echo", OperationStatus.FAILED, fingerprint)

            val snapshots = ProductionRetryChildRowReader(journal, identityFactory, body)
                .childrenForControlOpId("ctrl-1", "run-1", 0, 0, emptyList(), 3)

            val attempt3Child = snapshots.first { it.attempt == 3 }
            assertEquals(OperationStatus.FAILED, attempt3Child.status)
            assertTrue(attempt3Child.isFailure)
            assertTrue(attempt3Child.isTerminal)
        }
    }

    @Nested
    @DisplayName("Identity isolation")
    inner class IdentityIsolation {

        @Test
        fun `attempt 1 child X and attempt 2 child X resolve to different durable OpIds`() {
            val journal = InMemoryOperationJournal()
            val body = retryBodyWith("core.echo")
            seedChild(journal, "run-1", 0, 0, emptyList(), attempt = 2, childIndex = 0,
                pluginStepId = "core.echo", status = OperationStatus.SUCCEEDED, fingerprint = fingerprint)

            val snapshots = ProductionRetryChildRowReader(journal, identityFactory, body)
                .childrenForControlOpId("ctrl-1", "run-1", 0, 0, emptyList(), 3)

            // Attempt 1 child X must NOT see attempt 2's row.
            val attempt1 = snapshots.first { it.attempt == 1 }
            assertEquals(OperationStatus.PENDING, attempt1.status,
                "attempt 1 child X must be Missing even when attempt 2 child X is SUCCEEDED")

            val attempt2 = snapshots.first { it.attempt == 2 }
            assertEquals(OperationStatus.SUCCEEDED, attempt2.status)
        }

        @Test
        fun `retry A and retry B with the same attempt and childIndex resolve to different OpIds`() {
            val journal = InMemoryOperationJournal()
            val body = retryBodyWith("core.echo")
            // Seed a child row for "retry B" using a distinct parentBodyPath so the identity
            // factory produces a different controlOpId (and hence a different childOpId).
            val retryBPath = listOf(BlockSegment(0, PluginStepId("retry-B")))
            seedChild(
                journal = journal,
                runId = "run-1",
                stageIndex = 0,
                stepIndex = 0,
                parentBodyPath = retryBPath,
                attempt = 1,
                childIndex = 0,
                pluginStepId = "core.echo",
                status = OperationStatus.SUCCEEDED,
                fingerprint = fingerprint,
            )

            // Reader for retry A (parentBodyPath = emptyList()) must NOT observe retry B's row.
            val snapshots = ProductionRetryChildRowReader(journal, identityFactory, body)
                .childrenForControlOpId("ctrl-A", "run-1", 0, 0, emptyList(), 1)

            assertEquals(1, snapshots.size)
            assertEquals(OperationStatus.PENDING, snapshots.first().status,
                "retry A must not observe retry B's durable child evidence")
        }

        @Test
        fun `multiple body children all enumerated in (attempt, childIndex) order`() {
            val journal = InMemoryOperationJournal()
            val body = retryBodyWith(
                listOf(
                    atomicNode("core.echo"),
                    atomicNode("core.sh"),
                    atomicNode("core.dir"),
                ),
            )
            seedChild(journal, "run-1", 0, 0, emptyList(), 1, 1, "core.sh", OperationStatus.SUCCEEDED, fingerprint)

            val snapshots = ProductionRetryChildRowReader(journal, identityFactory, body)
                .childrenForControlOpId("ctrl-1", "run-1", 0, 0, emptyList(), 1)

            // 1 attempt × 3 children
            assertEquals(3, snapshots.size)
            assertEquals(listOf(0, 1, 2), snapshots.map { it.childIndex })
            // Only childIndex=1 (core.sh) is seeded.
            assertEquals(OperationStatus.PENDING, snapshots.first { it.childIndex == 0 }.status)
            assertEquals(OperationStatus.SUCCEEDED, snapshots.first { it.childIndex == 1 }.status)
            assertEquals(OperationStatus.PENDING, snapshots.first { it.childIndex == 2 }.status)
        }
    }

    // ---------- helpers ----------

    private fun retryBodyWith(pluginStepId: String): BlockStepNode =
        retryBodyWith(listOf(atomicNode(pluginStepId)))

    private fun retryBodyWith(children: List<OpaqueStepNode>): BlockStepNode = BlockStepNode(
        id = StepId("retry-block"),
        pluginStepId = PluginStepId("core.retry"),
        payload = VersionedStepPayload("dsl-v1", "{}"),
        body = children,
    )

    private fun atomicNode(pluginStepId: String): OpaqueStepNode = OpaqueStepNode(
        id = StepId("step-$pluginStepId"),
        pluginStepId = PluginStepId(pluginStepId),
        payload = VersionedStepPayload("dsl-v1", "{}"),
    )

    private fun seedChild(
        journal: InMemoryOperationJournal,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        attempt: Int,
        childIndex: Int,
        pluginStepId: String,
        status: OperationStatus,
        fingerprint: Fingerprint,
    ) {
        val controlOpId = identityFactory.controlOperationId(runId, stageIndex, stepIndex, parentBodyPath)
        val childOpId = identityFactory.childOperationId(
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            parentBodyPath = parentBodyPath,
            attempt = attempt,
            childIndex = childIndex,
            childPluginStepId = PluginStepId(pluginStepId),
        )
        journal.upsert(childOpId = childOpId, op = memoizedOp(childOpId, fingerprint, status))
    }
}

/** Minimal in-memory OperationJournal for reader tests (point-lookup only). */
private class InMemoryOperationJournal : OperationJournal {
    private val byId = mutableMapOf<String, MemoizedOperation>()

    fun upsert(childOpId: String, op: MemoizedOperation) {
        byId[childOpId] = op
    }

    override fun append(op: DurableOperation, deadlineMs: Long?) = error("not used in tests")

    override fun get(opId: String): DurableOperation? = byId[opId]

    override fun get(opId: String, attempt: Int): DurableOperation? = byId[opId]

    override fun listForRun(runId: String): List<DurableOperation> = byId.values.toList()

    override fun getDeadlineMs(opId: String, attempt: Int): Long? = null

    override fun getEndedAt(opId: String, attempt: Int): Long? = null

    override fun getStartedAt(opId: String, attempt: Int): Long? = null

    override fun beginOperation(
        opId: String,
        attempt: Int,
        fingerprint: String,
        inputJson: String,
        deadlineMs: Long?,
    ) = error("not used in tests")
}

/** Factory for [MemoizedOperation] in tests. */
private fun memoizedOp(
    id: String,
    fingerprint: Fingerprint,
    status: OperationStatus,
): MemoizedOperation = MemoizedOperation(
    id = id,
    fingerprint = fingerprint,
    input = OperationInput(stepId = "test", params = emptyMap(), runId = "test", attempt = 1),
    output = null,
    status = status,
    attempt = 1,
    cachedOutput = null,
)
