package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WU-LPR-041 acceptance: SqliteEventStore durable sequence.
 *
 * The property under repair (per trunk WU directive):
 *
 *   per-run monotonic sequence + restart/reopen continuity +
 *   concurrent append correctness + cursor compatibility.
 *
 * The sequence counter MUST come from SQLite (durable truth), not from
 * per-instance memory. InMemoryEventStore may keep its local counter;
 * SqliteEventStore may not.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Lpr041DurableSequenceRepairTest {

    private fun tempDb(): Pair<Path, String> {
        val tmp = Files.createTempDirectory("lpr041-repair-")
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

    private fun cleanup(parent: String) {
        java.io.File(parent.toString()).deleteRecursively()
    }

    @Test
    fun `sequence survives store instance reopen - WU-LPR-041 acceptance`() {
        val (dbPath, parent) = tempDb()
        val runId = "lpr041-" + UUID.randomUUID()
        val s1 = SqliteEventStore(dbPath.toString())
        repeat(50) { i -> s1.append(newStageStarted(runId, "s$i")) }
        s1.close()
        // Fresh instance, same DB: must continue at 51, not restart at 1.
        val s2 = SqliteEventStore(dbPath.toString())
        s2.append(newStageStarted(runId, "after-reopen"))
        // WU-RP-002.1: SqliteEventStore writer is async/batched since WU-LPR-042;
        // eventsFor() reads via a fresh connection and may observe the pre-COMMIT
        // state if we don't wait for the durable barrier. flush() blocks until
        // every enqueued append is COMMITted (the durable unit per LPR-042).
        s2.flush()
        val events = s2.eventsFor(runId).toList()
        val seqs = events.map { it.sequence }.sorted()
        println("LPR-041: count=${seqs.size} maxSeq=${seqs.maxOrNull()} minSeq=${seqs.minOrNull()}")
        check(seqs.size == 51) { "expected 51 events, got ${seqs.size}" }
        check(seqs.maxOrNull() == 51L) { "expected maxSeq=51, got ${seqs.maxOrNull()}" }
        check(seqs.toSet().size == 51) { "expected 51 unique sequences, got ${seqs.toSet().size}" }
        s2.close()
        cleanup(parent)
    }

    @Test
    fun `concurrent appends produce gapless unique sequences across instances`() {
        val (dbPath, parent) = tempDb()
        val runId = "lpr041c-" + UUID.randomUUID()
        val store = SqliteEventStore(dbPath.toString())
        val n = 400
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val t1 = Thread {
            ready.countDown(); go.await()
            repeat(n) { i -> store.append(newStageStarted(runId, "a$i")) }
        }
        val t2 = Thread {
            ready.countDown(); go.await()
            repeat(n) { i -> store.append(newStageStarted(runId, "b$i")) }
        }
        t1.start(); t2.start()
        ready.await(); go.countDown()
        t1.join(); t2.join()
        store.close()
        val verify = SqliteEventStore(dbPath.toString())
        val seqs = verify.eventsFor(runId).map { it.sequence }.toSortedSet()
        check(seqs.size == 2 * n) { "expected ${2 * n} events, got ${seqs.size}" }
        check(seqs.size == 2 * n) { "duplicate sequences detected" }
        check(seqs.first() == 1L && seqs.last() == (2 * n).toLong()) {
            "expected 1..${2 * n}, got ${seqs.first()}..${seqs.last()}"
        }
        verify.close()
        cleanup(parent)
    }
}
