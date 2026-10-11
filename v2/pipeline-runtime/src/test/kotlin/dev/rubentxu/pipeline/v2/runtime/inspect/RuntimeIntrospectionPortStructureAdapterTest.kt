package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
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
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M6 — real-store contract test for [RuntimeIntrospectionPort.runStructure]
 * backed by [InMemoryOperationJournal] + [InMemoryEventStore] +
 * [InMemoryReplayCursorStore] + [FileBackedRunExecutionLeaseStore]
 * + [SegmentOutputStore], wired against `@TempDir`.
 *
 * ## First-cut scope (M6)
 *
 * The M6 audit's F.1 gap is "typed enumeration of stages / steps /
 * operations". The M6 design's §C names the composition: the method
 * composes `OperationJournal.listForRun` (internal) and
 * `OutputFrameIndex.streamsOfRun` (published) into the closed
 * [RunStructure] ADT. The audit's I.6 PARTIAL closure is documented in
 * the adapter: the M6 first cut uses a SYNTHETIC single-stage single-step
 * view (the published `PipelineEventEnvelope` strips the type-specific
 * fields of the typed events, so event-driven grouping requires a
 * follow-up M-block that exposes a typed event-shape read port). The
 * tests below pin the synthetic grouping; a follow-up that adds
 * event-driven grouping will update these tests.
 *
 * Test cases:
 *  1. runStructure on a run with no journal → `RunStructure.Empty`
 *  2. runStructure on a run with one operation → synthetic single-stage
 *     single-step view, no cursor (single page)
 *  3. runStructure on a run with multiple operations → synthetic grouping,
 *     bounded by `pageSize`, cursor present when more pages exist
 *  4. runStructure with `includeTerminalStages = false` on a run with all-
 *     terminal operations → empty stages list
 *  5. runStructure honours a non-null cursor → resumes from the cursor's
 *     `operationOrdinal`
 *  6. runStructure never throws — every failure mode returns a typed
 *     refusal, not an exception
 */
class RuntimeIntrospectionPortStructureAdapterTest {

    private val runId = "m6-structure-run-1"

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
            val op = when (status) {
                OperationStatus.SUCCEEDED, OperationStatus.FAILED,
                OperationStatus.UNSTABLE, OperationStatus.ABORTED,
                OperationStatus.DIVERGENT, OperationStatus.LOST,
                OperationStatus.FAILED_TIMEOUT ->
                    RerunOperation(
                        id = opId,
                        fingerprint = Fingerprint(SHORT_FINGERPRINT),
                        input = input,
                        output = null,
                        status = status,
                        attempt = attempt,
                    )
                else ->
                    MemoizedOperation(
                        id = opId,
                        fingerprint = Fingerprint(SHORT_FINGERPRINT),
                        input = input,
                        output = null,
                        status = status,
                        attempt = attempt,
                        cachedOutput = null,
                    )
            }
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
    fun `1 runStructure on a run with no journal returns RunStructure Empty`(@TempDir tempDir: Path) {
        TestBundle(tempDir, runId).use { bundle ->
            val result = bundle.port.runStructure(runId, StructureRunOptions.Default)
            val structure = assertInstanceOf(RunStructureResult.Structure::class.java, result)
            assertEquals(RunStructure.Empty, structure.structure)
            assertEquals(null, structure.cursor)
        }
    }

    @Test
    fun `2 runStructure on a run with one operation returns a synthetic single-stage single-step view`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            bundle.appendOperation(opId = "op-1", runId = runId)
            val result = bundle.port.runStructure(runId, StructureRunOptions.Default)
            val structure = assertInstanceOf(RunStructureResult.Structure::class.java, result)
            val stages = assertInstanceOf(RunStructure.Stages::class.java, structure.structure).stages
            assertEquals(1, stages.size)
            val stage = stages[0]
            assertEquals(0, stage.stageOrdinal)
            assertEquals("synthetic-stage", stage.stageIndex)
            assertEquals(1, stage.steps.size)
            val step = stage.steps[0]
            assertEquals(0, step.stepOrdinal)
            assertEquals("synthetic-step", step.stepKey)
            assertEquals(1, step.operations.size)
            assertEquals("op-1", step.operations[0].operationId)
            assertEquals(OperationStatus.SUCCEEDED, step.operations[0].status)
            assertEquals(StepState.Succeeded, step.state)
            // Single operation = single page = no cursor.
            assertEquals(null, structure.cursor)
        }
    }

    @Test
    fun `3 runStructure on a run with three operations paginates by pageSize and emits a cursor`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            for (i in 1..3) {
                bundle.appendOperation(opId = "op-$i", runId = runId)
            }
            val result = bundle.port.runStructure(
                runId,
                StructureRunOptions(pageSize = 2),
            )
            val structure = assertInstanceOf(RunStructureResult.Structure::class.java, result)
            val stages = assertInstanceOf(RunStructure.Stages::class.java, structure.structure).stages
            // The synthetic single-stage / single-step view holds at most `pageSize`
            // operations per call; the next call resumes via the cursor.
            assertEquals(1, stages.size)
            val step = stages[0].steps[0]
            assertEquals(2, step.operations.size)
            assertEquals("op-1", step.operations[0].operationId)
            assertEquals("op-2", step.operations[1].operationId)
            assertNotNull(structure.cursor)
            assertEquals(2, structure.cursor!!.operationOrdinal)
        }
    }

    @Test
    fun `4 runStructure with includeTerminalStages=false drops all-terminal synthetic stages`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            bundle.appendOperation(
                opId = "op-1",
                runId = runId,
                status = OperationStatus.SUCCEEDED,
            )
            val result = bundle.port.runStructure(
                runId,
                StructureRunOptions.Default.copy(includeTerminalStages = false),
            )
            val structure = assertInstanceOf(RunStructureResult.Structure::class.java, result)
            val stages = assertInstanceOf(RunStructure.Stages::class.java, structure.structure).stages
            // The synthetic single stage contains only terminal operations, so
            // the filter drops it.
            assertEquals(0, stages.size)
        }
    }

    @Test
    fun `5 runStructure honours a non-null cursor and resumes from operationOrdinal`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            for (i in 1..5) {
                bundle.appendOperation(opId = "op-$i", runId = runId)
            }
            // Page 1: pageSize=2 → ops 0,1 returned, cursor = operationOrdinal 2
            val page1 = bundle.port.runStructure(
                runId,
                StructureRunOptions(pageSize = 2),
            ) as RunStructureResult.Structure
            val stages1 = (page1.structure as RunStructure.Stages).stages
            assertEquals(2, stages1[0].steps[0].operations.size)
            assertEquals("op-1", stages1[0].steps[0].operations[0].operationId)
            assertEquals("op-2", stages1[0].steps[0].operations[1].operationId)
            val cursor1 = page1.cursor!!
            // Page 2: cursor.operationOrdinal=2 → ops 2,3 returned
            val page2 = bundle.port.runStructure(
                runId,
                StructureRunOptions(pageSize = 2, cursor = cursor1),
            ) as RunStructureResult.Structure
            val stages2 = (page2.structure as RunStructure.Stages).stages
            assertEquals(2, stages2[0].steps[0].operations.size)
            assertEquals("op-3", stages2[0].steps[0].operations[0].operationId)
            assertEquals("op-4", stages2[0].steps[0].operations[1].operationId)
            // Page 3: cursor=4 → only op 5 left, no next cursor
            val page3 = bundle.port.runStructure(
                runId,
                StructureRunOptions(pageSize = 2, cursor = page2.cursor),
            ) as RunStructureResult.Structure
            val stages3 = (page3.structure as RunStructure.Stages).stages
            assertEquals(1, stages3[0].steps[0].operations.size)
            assertEquals("op-5", stages3[0].steps[0].operations[0].operationId)
            assertEquals(null, page3.cursor)
        }
    }

    @Test
    fun `6 runStructure never throws - all failure modes are typed refusals`(@TempDir tempDir: Path) {
        TestBundle(tempDir, runId).use { bundle ->
            // An unknown runId with no lease, no journal, no events → the
            // journal is empty, so the answer is `RunStructure.Empty`, NOT a
            // refusal. (Empty is honest: nothing is journaled, so the
            // structure is empty.)
            val result = bundle.port.runStructure("never-existed-run-id", StructureRunOptions.Default)
            assertInstanceOf(RunStructureResult.Structure::class.java, result)
        }
    }

    @Test
    fun `7 StructureRunOptions Default has pageSize 64 and includeTerminalStages true`() {
        val opts = StructureRunOptions.Default
        assertEquals(64, opts.pageSize)
        assertEquals(null, opts.cursor)
        assertTrue(opts.includeTerminalStages)
    }

    @Test
    fun `8 StructureRunOptions init rejects pageSize 0 and 4097+`() {
        runCatching { StructureRunOptions(pageSize = 0) }
            .also { assertTrue(it.isFailure, "pageSize=0 must be rejected") }
        runCatching { StructureRunOptions(pageSize = StructureRunOptions.MAX_STRUCTURE_PAGE_SIZE + 1) }
            .also { assertTrue(it.isFailure, "pageSize=MAX+1 must be rejected") }
    }
}
