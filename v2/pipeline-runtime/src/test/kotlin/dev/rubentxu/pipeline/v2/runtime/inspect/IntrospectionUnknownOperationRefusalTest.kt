package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.EventRecordReadPortStoreAdapter
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M7 — contract test for [IntrospectionRefusal.UnknownOperation].
 *
 * The M7 audit's B.2 hardening item lifts the "no journal row" case
 * for `inspectOperation` from a generic [IntrospectionRefusal.StorageError]
 * (with a cause string) to a typed [IntrospectionRefusal.UnknownOperation]
 * (with named fields). A consumer can `when` on the typed case without
 * parsing the cause string.
 *
 * Test cases:
 *  1. UnknownOperation sealed ADT pin: the case is reachable and named
 *  2. attempt < 1 still returns StorageError (not UnknownOperation) — the
 *     `attempt < 1` check is upstream of the journal lookup
 *  3. The UnknownOperation case carries operationId and attempt verbatim
 */
class IntrospectionUnknownOperationRefusalTest {

    private val runId = "m7-unknown-op-run-1"

    private class TestBundle(tempDir: Path, val runId: String) : AutoCloseable {
        val clock: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
        }
        val eventStore = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val cursors = InMemoryReplayCursorStore(clock)
        val leaseDir = tempDir.resolve("leases")
        val outputDir = tempDir.resolve("output")
        val lease = FileBackedRunExecutionLeaseStore(leaseDir)
        val outputStore = SegmentOutputStore(outputDir)
        val frames: OutputFrameIndex = object : OutputFrameIndex {
            override fun declareStream(stream: OutputStreamId, channel: dev.rubentxu.pipeline.v2.output.OutputChannel) =
                throw UnsupportedOperationException("not used in this test")
            override fun append(stream: OutputStreamId, channel: dev.rubentxu.pipeline.v2.output.OutputChannel, from: Long, to: Long) =
                throw UnsupportedOperationException("not used in this test")
            override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int) = emptyList<dev.rubentxu.pipeline.v2.output.OutputFrame>()
            override fun lastOrdinal(runId: String): Long? = null
            override fun streamsOfRun(runId: String): List<OutputStreamId> = emptyList()
            override fun recoverUnframedBytes(): List<dev.rubentxu.pipeline.v2.output.OutputFrame> = emptyList()
        }
        val tails: OutputTailPort = object : OutputTailPort {
            override fun tailState(stream: OutputStreamId): OutputTailState? = null
        }
        val eventRead: EventRecordReadPort = EventRecordReadPortStoreAdapter(
            store = eventStore,
            runExists = eventStore::hasRun,
            tailSequence = eventStore::tailSequence,
        )

        init {
            val owner = RunOwnerId.of("m7-unknown-op-observer")!!
            val request = LeaseRequest(this.runId, owner)
            val decision = lease.acquire(request)
            check(decision is LeaseAcquisition.Acquired || decision is LeaseAcquisition.Reentered) {
                "lease acquisition refused: $decision"
            }
        }

        val port: RuntimeIntrospectionPort = RuntimeIntrospectionPortStoreAdapter(
            eventReads = eventRead,
            tails = tails,
            frames = frames,
            journal = journal,
            cursors = cursors,
            lease = lease,
        )

        fun appendOperation(opId: String, attempt: Int = 1) {
            val input = OperationInput(
                stepId = "step-$opId",
                params = emptyMap(),
                runId = this.runId,
                attempt = attempt,
            )
            val op = RerunOperation(
                id = opId,
                fingerprint = Fingerprint("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                input = input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = attempt,
            )
            journal.append(op, deadlineMs = null)
        }

        override fun close() {
            lease.close()
        }
    }

    @Test
    fun `1 IntrospectionRefusal sealed ADT now has UnknownOperation as a permitted case`(
        @TempDir tempDir: Path,
    ) {
        val sealed = IntrospectionRefusal::class.java
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        // The new M7 case is a permitted case. The M2 cases (UnknownRun,
        // NoControlRoot, NoEventStore, NoOutputPlane, LeaseHeldByAnother,
        // StorageError, InconsistentLeaseState) are preserved.
        assertEquals(
            true,
            "UnknownOperation" in permitted,
            "IntrospectionRefusal must have UnknownOperation as a permitted case (M7 B.2 hardening)",
        )
    }

    @Test
    fun `2 inspectOperation on attempt less than 1 still returns StorageError, not UnknownOperation`(
        @TempDir tempDir: Path,
    ) {
        // The `attempt < 1` check is upstream of the journal lookup;
        // the StorageError case is the right refusal for it (not
        // UnknownOperation, which is reserved for the journal-has-no-
        // row case).
        TestBundle(tempDir, runId).use { bundle ->
            val result = bundle.port.inspectOperation("op-1", 0)
            val refused = assertInstanceOf(OperationInspectionResult.Refused::class.java, result)
            val se = assertInstanceOf(IntrospectionRefusal.StorageError::class.java, refused.refusal)
            assertNotEquals(
                IntrospectionRefusal.UnknownOperation("op-1", 0),
                refused.refusal,
                "attempt < 1 should be StorageError, not UnknownOperation",
            )
        }
    }

    @Test
    fun `3 UnknownOperation case carries operationId and attempt verbatim and is distinct from other refusals`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            bundle.appendOperation(opId = "op-known", attempt = 1)
            val result = bundle.port.inspectOperation("op-unknown", 1)
            val refused = assertInstanceOf(OperationInspectionResult.Refused::class.java, result)
            val uo = assertInstanceOf(IntrospectionRefusal.UnknownOperation::class.java, refused.refusal)
            assertEquals("op-unknown", uo.operationId)
            assertEquals(1, uo.attempt)
            // The case is data class equality, so two UnknownOperations
            // with the same fields are equal.
            assertEquals(
                IntrospectionRefusal.UnknownOperation("op-unknown", 1),
                uo,
            )
        }
    }
}
