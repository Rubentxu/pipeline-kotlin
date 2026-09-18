package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * LPR-000 characterization of SqliteEventStore durable sequence semantics.
 *
 * Probes three scenarios that the duplicated InMemory/SQLite sequence counters
 * may or may not handle correctly:
 *
 *  1. reopen with new store, append with sequence=0 (should resume)
 *  2. concurrent appends to the same runId from two threads
 *  3. reopen after the SQLite file is physically deleted (does it recreate?)
 *
 * NOT a gate. Just characterizes what happens today.
 */
class Lpr000SqliteSequenceDurabilityTest {

    private fun tempDb(): Pair<Path, String> {
        val tmp = Files.createTempDirectory("lpr000-sqlite-durability-")
        return tmp.resolve("events.db") to tmp.toString()
    }

    private fun runId() = "lpr000-durability-" + UUID.randomUUID()

    private fun newStore(dbPath: Path): SqliteEventStore = SqliteEventStore(dbPath.toString())

    private fun newStageStarted(runId: String, seq: Long = 0L, name: String = "s"): StageStarted =
        StageStarted(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = seq,
            occurredAt = java.time.Instant.now(),
            stageIndex = 0,
            stageName = name,
        )

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun reopenResumesSequence() {
        val (dbPath, _) = tempDb()
        val runId = runId()
        // first store: append N events with sequence=0 (auto-assigned)
        val s1 = newStore(dbPath)
        val events = (1..50).map { newStageStarted(runId, name = "s$it") }
        events.forEach { s1.append(it) }
        s1.close()
        // read back to confirm sequence goes 1..50
        val firstRun = s1.eventsFor(runId).toList()
        val maxSeqBefore = firstRun.maxOf { it.sequence }
        println("LPR-000 reopenResumesSequence.beforeReopen: maxSeq=$maxSeqBefore")
        // second store: open same DB
        val s2 = newStore(dbPath)
        val next = newStageStarted(runId, name = "after-reopen")
        s2.append(next)
        s2.close()
        val secondRun = s2.eventsFor(runId).toList()
        val maxSeqAfter = secondRun.maxOf { it.sequence }
        println("LPR-000 reopenResumesSequence.afterReopen: maxSeq=$maxSeqAfter")
        if (maxSeqAfter != maxSeqBefore + 1L) {
            println("  OBSERVATION: maxSeq went $maxSeqBefore -> $maxSeqAfter (expected ${maxSeqBefore + 1})")
        }
        java.io.File(dbPath.parent.toString()).deleteRecursively()
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun concurrentAppendSameRun() {
        val (dbPath, _) = tempDb()
        val runId = runId()
        val store = newStore(dbPath)
        val n = 200
        val t1 = Thread {
            (1..n).forEach { i -> store.append(newStageStarted(runId, name = "a$i")) }
        }
        val t2 = Thread {
            (1..n).forEach { i -> store.append(newStageStarted(runId, name = "b$i")) }
        }
        t1.start(); t2.start()
        t1.join(); t2.join()
        store.close()
        val s2 = newStore(dbPath)
        val all = s2.eventsFor(runId).toList()
        val sequences = all.map { it.sequence }.sorted()
        val unique = sequences.toSet()
        println("LPR-000 concurrentAppendSameRun: events=${sequences.size} unique=${unique.size} min=${sequences.min()} max=${sequences.max()}")
        val duplicates = sequences.size - unique.size
        if (duplicates > 0) println("  OBSERVATION: $duplicates duplicate sequences detected")
        java.io.File(dbPath.parent.toString()).deleteRecursively()
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun reopenAfterFileDeleted() {
        val (dbPath, parent) = tempDb()
        val runId = runId()
        val s1 = newStore(dbPath)
        s1.append(newStageStarted(runId, name = "before"))
        s1.close()
        val sizeBefore = Files.size(dbPath)
        Files.deleteIfExists(dbPath)
        println("LPR-000 reopenAfterFileDeleted: deleted db of size $sizeBefore bytes")
        val s2 = newStore(dbPath)
        val recreatedSize = if (Files.exists(dbPath)) Files.size(dbPath) else -1L
        println("LPR-000 reopenAfterFileDeleted: after reopen db exists=${Files.exists(dbPath)} size=$recreatedSize")
        s2.append(newStageStarted(runId, name = "after"))
        val events = s2.eventsFor(runId).toList()
        println("LPR-000 reopenAfterFileDeleted: eventsFor($runId) returned ${events.size}")
        s2.close()
        java.io.File(parent.toString()).deleteRecursively()
    }
}