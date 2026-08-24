package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * SQLite round-trip test: append 4 events, close, reopen, assert order + kind + cacheKey payload.
 */
class SqliteEventStoreRoundTripTest {

    @Test
    fun `sqlite round-trip preserves order and payload`(@TempDir tempDir: Path) {
        val dbFile = tempDir.resolve("test.db").toString()

        // Append 4 events
        val runId = "round-trip-run"
        val store = SqliteEventStore(dbFile)
        store.append(RunStarted(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 1L,
            occurredAt = Instant.parse("2026-08-23T10:00:00Z"),
            scriptPath = "test.pipeline.kts",
        ))
        store.append(CompilationStarted(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 2L,
            occurredAt = Instant.parse("2026-08-23T10:00:01Z"),
        ))
        val cacheKey = CacheKey("a".repeat(64), "v1")
        store.append(CompilationFinished(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 3L,
            occurredAt = Instant.parse("2026-08-23T10:00:02Z"),
            cacheKey = cacheKey,
            diagnostics = emptyList(),
        ))
        store.append(RunFinished(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 4L,
            occurredAt = Instant.parse("2026-08-23T10:00:03Z"),
            outcome = "success",
            diagnostics = emptyList(),
        ))

        // Close and reopen
        val reopened = SqliteEventStore(dbFile)
        val events = reopened.eventsFor(runId).toList()

        assertEquals(4, events.size)
        assertEquals("RunStarted", events[0].kind)
        assertEquals("CompilationStarted", events[1].kind)
        assertEquals("CompilationFinished", events[2].kind)
        assertEquals("RunFinished", events[3].kind)

        val finished = events[2] as CompilationFinished
        assertEquals("v1", finished.cacheKey.version)
        assertEquals(64, finished.cacheKey.value.length)

        val runFinished = events[3] as RunFinished
        assertEquals("success", runFinished.outcome)
    }
}
