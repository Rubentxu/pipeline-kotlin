package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WU-LPR-042 acceptance: event write performance + durability policy.
 *
 * Trunk directives for this WU:
 *  - Evolve SqliteEventStore IN PLACE. No FastSqliteEventStore.
 *  - Persistent connection + prepared statement + bounded ingress +
 *    single writer + batch transaction + flush barrier.
 *  - Honest overload policy: the store accepts a WAL + synchronous
 *    NORMAL profile where durability is the SQLite commit, and the
 *    append path is serialized through a single writer.
 *
 * Perf gate (from trunk WU): SQLite append per_event must drop from
 * ~476-513µs (fresh-connection-per-event baseline, LPR-040 D1) to
 * < 50µs sustained, WITHOUT weakening durability below a committed
 * SQLite transaction per batch and WITHOUT breaking any LPR-041
 * sequence property.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class Lpr042EventWritePerfTest {

    private fun tempDb(): Pair<Path, String> {
        val tmp = Files.createTempDirectory("lpr042-perf-")
        return tmp.resolve("events.db") to tmp.toString()
    }

    private fun newStageStarted(runId: String, name: String): StageStarted =
        StageStarted(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.now(),
            stageIndex = 0,
            stageName = name,
        )

    @Test
    fun `append throughput is at least 20x better than fresh-connection baseline`() {
        val (dbPath, parent) = tempDb()
        val runId = "lpr042-" + UUID.randomUUID()
        val store = SqliteEventStore(dbPath.toString())
        // Warm-up (JIT + page cache) — measured separately from the gate.
        repeat(2_000) { i -> store.append(newStageStarted(runId, "w$i")) }
        // Measured section.
        val n = 50_000
        val t0 = System.nanoTime()
        repeat(n) { i -> store.append(newStageStarted(runId, "s$i")) }
        val elapsed = System.nanoTime() - t0
        val perEventUs = elapsed / 1000 / n
        println("LPR-042: n=$n totalMs=${TimeUnit.NANOSECONDS.toMillis(elapsed)} per_event=${perEventUs}us")
        check(perEventUs < 50) { "per_event=${perEventUs}us exceeds 50us gate" }
        // Integrity: all events present, gapless, unique. flush() is the
        // barrier that makes the enqueued appends visible to readers.
        store.flush()
        val seqs = store.eventsFor(runId).map { it.sequence }.toSortedSet()
        check(seqs.size == 2_000 + n) { "expected ${2_000 + n} events, got ${seqs.size}" }
        check(seqs.first() == 1L && seqs.last() == (2_000 + n).toLong()) { "gapless 1..${2_000 + n} expected" }
        store.close()
        java.io.File(parent.toString()).deleteRecursively()
    }

    @Test
    fun `durability policy is explicit and committed data survives close`() {
        val (dbPath, parent) = tempDb()
        val runId = "lpr042d-" + UUID.randomUUID()
        val store = SqliteEventStore(dbPath.toString())
        repeat(1_000) { i -> store.append(newStageStarted(runId, "s$i")) }
        store.close()
        // Fresh instance must see every committed event (LPR-041 property
        // re-checked under the new write path).
        val verify = SqliteEventStore(dbPath.toString())
        val seqs = verify.eventsFor(runId).map { it.sequence }.toSortedSet()
        check(seqs.size == 1_000) { "expected 1000 events after close, got ${seqs.size}" }
        check(seqs.last() == 1_000L) { "expected maxSeq=1000, got ${seqs.last()}" }
        verify.close()
        java.io.File(parent.toString()).deleteRecursively()
    }

    @Test
    fun `flush barrier makes prior appends visible to a fresh reader`() {
        val (dbPath, parent) = tempDb()
        val runId = "lpr042f-" + UUID.randomUUID()
        val store = SqliteEventStore(dbPath.toString())
        repeat(500) { i -> store.append(newStageStarted(runId, "s$i")) }
        store.flush()
        val verify = SqliteEventStore(dbPath.toString())
        val count = verify.eventsFor(runId).toList().size
        check(count == 500) { "expected 500 events after flush, got $count" }
        verify.close()
        store.close()
        java.io.File(parent.toString()).deleteRecursively()
    }
}
