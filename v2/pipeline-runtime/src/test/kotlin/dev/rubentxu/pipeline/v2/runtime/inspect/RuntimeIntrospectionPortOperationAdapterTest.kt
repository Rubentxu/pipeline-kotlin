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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M6 — real-store contract test for [RuntimeIntrospectionPort.inspectOperation]
 * backed by [InMemoryOperationJournal] + [InMemoryEventStore] +
 * [InMemoryReplayCursorStore] + [FileBackedRunExecutionLeaseStore] +
 * [SegmentOutputStore], wired against `@TempDir`.
 *
 * The F.2 gap is "per-operation terminality typed query". The M6 design's
 * §C names the composition: `OperationJournal.get(opId, attempt)` +
 * `OperationJournal.getStartedAt / getEndedAt / getDeadlineMs` +
 * `OutputFrameIndex.streamsOfRun`. The audit's PARTIAL closure is the
 * same as the structure test: synthetic stage/step ordinals in the M6
 * first cut.
 *
 * Test cases:
 *  1. inspectOperation on a known operation → typed [OperationInspection]
 *  2. inspectOperation on an unknown operation → typed `Refused(StorageError)`
 *  3. inspectOperation on attempt < 1 → typed `Refused(StorageError)`
 *  4. inspectOperation on a second attempt of the same op → typed
 *     [OperationInspection] with `attempt = 2`
 *  5. inspectOperation never throws — every failure mode returns a typed
 *     refusal, not an exception
 *  6. inspectOperation carries the operation's fingerprint, status, and
 *     timestamps verbatim from the journal
 */
class RuntimeIntrospectionPortOperationAdapterTest {

    private val runId = "m6-operation-run-1"

    private class TestBundle(
        tempDir: Path,
        runId: String,
    ) : AutoCloseable {
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
            val owner = RunOwnerId.of("m6-observer")!!
            val request = LeaseRequest(runId, owner)
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

        fun appendOperation(
            opId: String,
            attempt: Int = 1,
            status: OperationStatus = OperationStatus.SUCCEEDED,
            runId: String,
        ) {
            val input = OperationInput(
                stepId = "step-$opId",
                params = emptyMap(),
                runId = runId,
                attempt = attempt,
            )
            val op = RerunOperation(
                id = opId,
                fingerprint = Fingerprint(SHORT_FINGERPRINT),
                input = input,
                output = null,
                status = status,
                attempt = attempt,
            )
            journal.append(op, deadlineMs = null)
        }

        override fun close() {
            lease.close()
        }

        companion object {
            const val SHORT_FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }
    }

    @Test
    fun `1 inspectOperation on a known operation returns typed OperationInspection`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            bundle.appendOperation(opId = "op-1", runId = runId)
            val result = bundle.port.inspectOperation("op-1", 1)
            val inspection = assertInstanceOf(OperationInspectionResult.Inspection::class.java, result).inspection
            assertEquals("op-1", inspection.operationId)
            assertEquals(1, inspection.attempt)
            assertEquals(runId, inspection.runId)
            assertEquals(OperationStatus.SUCCEEDED, inspection.status)
            assertEquals(TestBundle.SHORT_FINGERPRINT, inspection.fingerprint)
            // Synthetic in M6 first cut; pinned explicitly.
            assertEquals(0, inspection.stageOrdinal)
            assertEquals(0, inspection.stepOrdinal)
            assertNotNull(inspection.startedAtMs)
        }
    }

    @Test
    fun `2 inspectOperation on an unknown operation returns Refused(StorageError)`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            val result = bundle.port.inspectOperation("never-existed-op", 1)
            val refused = assertInstanceOf(OperationInspectionResult.Refused::class.java, result)
            val se = assertInstanceOf(IntrospectionRefusal.StorageError::class.java, refused.refusal)
            assertTrue(se.cause.contains("never-existed-op"))
        }
    }

    @Test
    fun `3 inspectOperation on attempt less than 1 returns Refused(StorageError)`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            val result = bundle.port.inspectOperation("op-1", 0)
            val refused = assertInstanceOf(OperationInspectionResult.Refused::class.java, result)
            val se = assertInstanceOf(IntrospectionRefusal.StorageError::class.java, refused.refusal)
            assertTrue(se.cause.contains("attempt must be >= 1"))
        }
    }

    @Test
    fun `4 inspectOperation on attempt 2 of the same op returns the right attempt`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            bundle.appendOperation(opId = "op-1", attempt = 1, runId = runId)
            bundle.appendOperation(opId = "op-1", attempt = 2, runId = runId)
            val result = bundle.port.inspectOperation("op-1", 2)
            val inspection = assertInstanceOf(OperationInspectionResult.Inspection::class.java, result).inspection
            assertEquals(2, inspection.attempt)
        }
    }

    @Test
    fun `5 inspectOperation on a different attempt than the journal has returns Refused`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            bundle.appendOperation(opId = "op-1", attempt = 1, runId = runId)
            val result = bundle.port.inspectOperation("op-1", 7)
            val refused = assertInstanceOf(OperationInspectionResult.Refused::class.java, result)
            assertInstanceOf(IntrospectionRefusal.StorageError::class.java, refused.refusal)
        }
    }

    @Test
    fun `6 inspectOperation carries the journal's status and fingerprint verbatim`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            bundle.appendOperation(
                opId = "op-failed",
                runId = runId,
                status = OperationStatus.FAILED,
            )
            val result = bundle.port.inspectOperation("op-failed", 1)
            val inspection = assertInstanceOf(OperationInspectionResult.Inspection::class.java, result).inspection
            assertEquals(OperationStatus.FAILED, inspection.status)
            assertEquals(TestBundle.SHORT_FINGERPRINT, inspection.fingerprint)
        }
    }
}
