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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M6 — pagination + cursor contract test for
 * [RuntimeIntrospectionPort.runStructure]. Pairs with
 * [RuntimeIntrospectionPortStructureAdapterTest]; the pagination test
 * focuses on the F.3 properties the audit names:
 *
 *  - bounded per-call (a single call never returns more than `pageSize`)
 *  - deterministic order (journal execution order; pagination walks it)
 *  - resume cursor (the result includes a `StructureCursor` when more
 *    pages exist; the next call resumes from it)
 *  - no backpressure on the writer (a reader that pages slowly does not
 *    block the writer; concurrent writer + reader succeed)
 *
 * The audit's F.3 also names "lectura eficiente con presupuestos
 * explícitos" — the `pageSize` cap is the budget; the test pins it.
 */
class RuntimeIntrospectionPortStructurePaginationTest {

    private val runId = "m6-pagination-run-1"

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
    fun `1 single call with pageSize larger than the run returns all ops in one page with no cursor`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            for (i in 1..5) {
                bundle.appendOperation(opId = "op-$i", runId = runId)
            }
            val result = bundle.port.runStructure(
                runId,
                StructureRunOptions(pageSize = 100),
            )
            val structure = assertInstanceOf(RunStructureResult.Structure::class.java, result)
            val stages = (structure.structure as RunStructure.Stages).stages
            assertEquals(1, stages.size)
            assertEquals(5, stages[0].steps[0].operations.size)
            assertEquals(null, structure.cursor)
        }
    }

    @Test
    fun `2 paginating the entire run via successive cursors visits every operation exactly once`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            val totalOps = 7
            for (i in 1..totalOps) {
                bundle.appendOperation(opId = "op-$i", runId = runId)
            }
            val visited = mutableSetOf<String>()
            var cursor: StructureCursor? = null
            var pages = 0
            val maxPages = totalOps + 1 // safety
            do {
                val result = bundle.port.runStructure(
                    runId,
                    StructureRunOptions(pageSize = 2, cursor = cursor),
                ) as RunStructureResult.Structure
                val stages = (result.structure as RunStructure.Stages).stages
                for (stage in stages) {
                    for (step in stage.steps) {
                        for (op in step.operations) {
                            assertTrue(visited.add(op.operationId), "op ${op.operationId} visited twice")
                        }
                    }
                }
                cursor = result.cursor
                pages += 1
                check(pages <= maxPages) { "pagination did not terminate after $maxPages pages" }
            } while (cursor != null)
            assertEquals(totalOps, visited.size)
            assertTrue(pages >= 4, "expected at least 4 pages for 7 ops with pageSize=2; got $pages")
        }
    }

    @Test
    fun `3 a cursor past the end of the journal returns an empty stages list with a null next cursor`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            bundle.appendOperation(opId = "op-1", runId = runId)
            val pastEnd = StructureCursor(
                stageOrdinal = 0,
                stepOrdinal = 0,
                operationOrdinal = 100,
            )
            val result = bundle.port.runStructure(
                runId,
                StructureRunOptions(pageSize = 64, cursor = pastEnd),
            ) as RunStructureResult.Structure
            val stages = (result.structure as RunStructure.Stages).stages
            assertEquals(0, stages.size)
            assertEquals(null, result.cursor)
        }
    }

    @Test
    fun `4 concurrent reader (paginating) does not block the writer (append succeeds)`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            // Seed the journal with 4 operations so the reader has something to paginate.
            for (i in 1..4) {
                bundle.appendOperation(opId = "op-$i", runId = runId)
            }
            val started = CountDownLatch(1)
            val readerDone = CountDownLatch(1)
            val writesSucceeded = AtomicInteger(0)
            val writerThread = Thread {
                started.await()
                for (i in 5..20) {
                    bundle.appendOperation(opId = "op-$i", runId = runId)
                    writesSucceeded.incrementAndGet()
                }
            }
            val readerThread = Thread {
                started.await()
                var cursor: StructureCursor? = null
                var pages = 0
                do {
                    val result = bundle.port.runStructure(
                        runId,
                        StructureRunOptions(pageSize = 2, cursor = cursor),
                    ) as RunStructureResult.Structure
                    val stages = (result.structure as RunStructure.Stages).stages
                    val opsOnPage = stages.sumOf { it.steps.sumOf { s -> s.operations.size } }
                    assertTrue(opsOnPage <= 2, "pageSize cap violated: $opsOnPage ops on page")
                    cursor = result.cursor
                    pages += 1
                } while (cursor != null && pages < 100)
                readerDone.countDown()
            }
            writerThread.start()
            readerThread.start()
            started.countDown()
            assertTrue(readerDone.await(10, TimeUnit.SECONDS), "reader did not finish in 10s")
            writerThread.join(10_000)
            assertEquals(16, writesSucceeded.get(), "writer should have appended 16 ops without blocking")
        }
    }

    @Test
    fun `5 pageSize 1 returns one operation per page and the cursor advances by one`(
        @TempDir tempDir: Path,
    ) {
        TestBundle(tempDir, runId).use { bundle ->
            for (i in 1..3) {
                bundle.appendOperation(opId = "op-$i", runId = runId)
            }
            val result = bundle.port.runStructure(
                runId,
                StructureRunOptions(pageSize = 1),
            ) as RunStructureResult.Structure
            val stages = (result.structure as RunStructure.Stages).stages
            assertEquals(1, stages[0].steps[0].operations.size)
            assertEquals("op-1", stages[0].steps[0].operations[0].operationId)
            assertNotNull(result.cursor)
            assertEquals(1, result.cursor!!.operationOrdinal)
        }
    }
}
