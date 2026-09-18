package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WU-LPR-105: Event Append Acknowledgement / Sequence Publication.
 *
 * Law: the projection must NEVER discover store-assigned write metadata by
 * racing the read model. The store is the sole sequence authority; the write
 * path must return the assigned event explicitly
 * (`EventStore.appendAssigned`). Read-side fallback (`?: event.sequence`)
 * is forbidden: it silently publishes sequence=0 while the row is still
 * in the async batch queue (the pre-105 race: observed [0,0,0,0,5,6,0]).
 *
 * Acknowledgement semantics: `appendAssigned` returns the ASSIGNED event
 * (sequence decided by the store authority). Durable observers get
 * durability from the existing flush()/cursor machinery; in-process
 * projection never gates on COMMIT (no sync-commit-per-event).
 */
@Timeout(60)
class EventAppendAcknowledgementTest {

    private val at: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun evt(i: Int, runId: String) = RunStarted(
        eventId = "ack-$i", runId = runId, sequence = 0, occurredAt = at, scriptPath = "/p",
    )

    /**
     * PIN-1: the write path must return the store-assigned event.
     * RED before the fix: SqliteEventStore.append returns Unit.
     */
    @Test
    fun `appendAssigned returns store assigned event with nonzero sequence`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("ack.db").toString())
        store.writerDelayMillis = 50 // widen the old race window deterministically
        val publisher = CollectingPublisher()
        val projecting = EnvelopeProjectingEventSink(store, publisher)
        val assigned = projecting.appendAssigned(evt(1, "ack-run"))
        assertEquals(1L, assigned.sequence, "appendAssigned must return ASSIGNED sequence, never the producer's 0")
        assertEquals(1L, publisher.envelopes.single().sequence, "projected envelope must carry the ASSIGNED sequence")
        store.close()
    }

    /**
     * PIN-2 (the historical race, now deterministic): with the writer
     * artificially slow, projection is still immediate and correct. The
     * old implementation re-read eventsFor() before COMMIT and fell back
     * to sequence=0. 1000 events -> projected sequences never 0, exactly
     * monotonic 1..1000.
     */
    @Test
    fun `slow writer projection never publishes zero and stays monotonic`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("ack2.db").toString())
        store.writerDelayMillis = 2
        val publisher = CollectingPublisher()
        val projecting = EnvelopeProjectingEventSink(store, publisher)
        val n = 1000
        for (i in 1..n) projecting.append(evt(i, "ack-slow"))
        val seqs = publisher.envelopes.map { it.sequence }
        assertEquals(n, seqs.size)
        assertTrue(seqs.none { it == 0L }, "projected sequence 0 is the read-side race signature: $seqs")
        assertEquals((1L..n.toLong()).toList(), seqs, "projected sequences must be exactly monotonic")
        store.close()
    }

    /** Thread-safe variant for the concurrency pin (shared CollectingPublisher uses ArrayList). */
    class ConcurrentCollectingPublisher : EventPublisher {
        private val list = java.util.concurrent.ConcurrentLinkedQueue<PipelineEventEnvelope>()
        val envelopes: List<PipelineEventEnvelope> get() = list.toList()
        override fun publish(event: PipelineEventEnvelope) {
            list.add(event)
        }
    }

    /**
     * PIN-3: high-concurrency appends — assigned sequences are unique and
     * dense 1..N per run; projected envelopes carry exactly those.
     */
    @Test
    fun `concurrent appends produce unique dense assigned sequences`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("ack3.db").toString())
        val publisher = ConcurrentCollectingPublisher()
        val projecting = EnvelopeProjectingEventSink(store, publisher)
        val n = 500
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val jobs = (1..n).map { i ->
            pool.submit {
                start.await()
                projecting.append(evt(i, "ack-conc"))
            }
        }
        start.countDown()
        jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        val seqs = publisher.envelopes.map { it.sequence }.sorted()
        assertEquals((1L..n.toLong()).toList(), seqs, "assigned sequences must be unique and dense 1..N")
        store.close()
    }

    /**
     * PIN-4: InMemory/SQLite parity — both stores honour appendAssigned.
     */
    @Test
    fun `appendAssigned parity between InMemory and SQLite`(@TempDir dir: Path) {
        val mem = InMemoryEventStore()
        val sqlite = SqliteEventStore(dir.resolve("ack4.db").toString())
        val a1 = mem.appendAssigned(evt(1, "ack-parity"))
        val a2 = sqlite.appendAssigned(evt(1, "ack-parity"))
        assertEquals(a1.sequence, a2.sequence, "both stores assign identically for identical fresh input")
        assertEquals(1L, a1.sequence)
        sqlite.close()
    }
}
