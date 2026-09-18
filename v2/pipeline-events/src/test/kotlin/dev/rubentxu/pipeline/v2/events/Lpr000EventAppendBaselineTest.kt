package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.UUID as JavaUUID
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

/**
 * LPR-000 baseline characterization.
 *
 * Measures raw append() cost for [SqliteEventStore] and [InMemoryEventStore]
 * over 1000 events with sequential sequence assignment.
 *
 * NOT a gate. Just a number.
 */
class Lpr000EventAppendBaselineTest {

    private fun newRunId() = "lpr000-" + JavaUUID.randomUUID().toString()

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun baselineAppend1000Sqlite() {
        val tmp = Files.createTempDirectory("lpr000-sqlite-")
        val dbPath = tmp.resolve("events.db")
        val store = SqliteEventStore(dbPath.toString())
        val runId = newRunId()
        val events = (1..1000).map { i ->
            StageStarted(
                eventId = JavaUUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                stageIndex = i,
                stageName = "stage-$i",
            )
        }
        val elapsedNs = measureNanoTime {
            events.forEach { store.append(it) }
        }
        val perEventUs = elapsedNs / 1000 / 1000.0
        println("LPR-000 SqliteEventStore.append x1000: total=${elapsedNs / 1_000_000}ms, per_event=${perEventUs}us")
        store.close()
        tmp.toFile().deleteRecursively()
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun baselineAppend1000InMemory() {
        val store = InMemoryEventStore()
        val runId = newRunId()
        val events = (1..1000).map { i ->
            StageStarted(
                eventId = JavaUUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                stageIndex = i,
                stageName = "stage-$i",
            )
        }
        val elapsedNs = measureNanoTime {
            events.forEach { store.append(it) }
        }
        val perEventUs = elapsedNs / 1000 / 1000.0
        println("LPR-000 InMemoryEventStore.append x1000: total=${elapsedNs / 1_000_000}ms, per_event=${perEventUs}us")
    }
}