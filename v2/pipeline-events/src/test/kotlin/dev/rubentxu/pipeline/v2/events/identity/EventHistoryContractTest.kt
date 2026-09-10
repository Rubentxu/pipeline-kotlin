package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * EVT-2 contract suite. The SAME scenarios run against InMemory and SQLite
 * adapters: identical input MUST produce identical observable envelope
 * sequences (contract parity), including a SQLite close/reopen restart test.
 */
class EventHistoryContractTest {

    companion object {
        private val at: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private val runId = "01987654-3210-fedc-ba98-76543210fedc"

        @JvmStatic
        fun sinks(): List<Named<SearchableSink>> = listOf(
            Named.of("InMemory", SearchableSink("InMemory", InMemoryEventStore(), null)),
        )

        @JvmStatic
        fun sinksWithRestart(@TempDir dir: Path): List<Named<SearchableSink>> = listOf(
            Named.of("InMemory", SearchableSink("InMemory", InMemoryEventStore(), null)),
            Named.of("SQLite", SearchableSink("SQLite", null, dir)),
        )
    }

    /** Factory-style holder: the SQLite adapter needs a fresh instance per phase (restart test). */
    class SearchableSink(
        val name: String,
        private val inMemory: EventSink?,
        private val sqliteDir: Path?,
    ) {
        fun fresh(): EventSink = when {
            inMemory != null -> inMemory
            sqliteDir != null -> SqliteEventStore(sqliteDir.resolve("events.db").toString())
            else -> throw IllegalStateException("unconfigured")
        }
    }

    private fun sampleEvents(): List<dev.rubentxu.pipeline.v2.events.DomainEvent> = listOf(
        RunStarted(eventId = "e1", runId = runId, sequence = 0, occurredAt = at, scriptPath = "/p.pipeline.kts"),
        CompilationStarted(eventId = "e2", runId = runId, sequence = 0, occurredAt = at),
        StageStarted(eventId = "e3", runId = runId, sequence = 0, occurredAt = at, stageIndex = 0, stageName = "build"),
        StepStarted(
            eventId = "e4", runId = runId, sequence = 0, occurredAt = at,
            stageIndex = 0, stepIndex = 2, stepName = "s", stepType = "sh",
        ),
        EchoOutputCaptured(eventId = "e5", runId = runId, sequence = 0, occurredAt = at, stepIndex = 2, content = "hi"),
        StageFinished(eventId = "e6", runId = runId, sequence = 0, occurredAt = at, stageIndex = 0, stageName = "build", outcome = "success"),
        RunFinished(eventId = "e7", runId = runId, sequence = 0, occurredAt = at, outcome = "success", diagnostics = emptyList()),
    )

    private fun appendAll(sink: EventSink) {
        val publisher = CollectingPublisher()
        val projecting = EnvelopeProjectingEventSink(sink, publisher)
        sampleEvents().forEach(projecting::append)
        assertEquals(7, publisher.envelopes.size)
        // Sequences assigned by the store, monotonically 1..7 per run
        assertEquals((1L..7L).toList(), publisher.envelopes.map { it.sequence })
    }

    private fun sampledSinkInstances(): List<Pair<String, EventSink>> = listOf(
        "InMemory" to InMemoryEventStore(),
        "SQLite" to SqliteEventStore(createTempDb()),
    )

    // ---- Parameterized via simple loops inside @Test to avoid JUnit factory plumbing complexity ----

    @org.junit.jupiter.api.Test
    fun `contract parity - identical envelope sequences for identical input`() {
        val results = sampledSinkInstances().map { (name, sink) ->
            appendAll(sink)
            val reader = EventHistoryReader(sink)
            name to reader.history(ResourceRefs.run(runId)).toList()
        }
        val first = results.first().second
        results.forEach { (name, seq) ->
            assertEquals(first.map { it.copy(occurredAt = at) }, seq.map { it.copy(occurredAt = at) }, "parity failed for $name")
            assertEquals(7, seq.size, name)
        }
        // Deterministic identity: same kinds in same store-assigned order
        assertEquals(
            listOf("RunStarted", "CompilationStarted", "StageStarted", "StepStarted", "EchoOutputCaptured", "StageFinished", "RunFinished"),
            first.map { it.kind },
        )
    }

    @org.junit.jupiter.api.Test
    fun `cursor pagination - sequence N then readAfter yields N+1 with no gaps or duplicates`() {
        sampledSinkInstances().forEach { (name, sink) ->
            appendAll(sink)
            val tail: EventTail = EventHistoryReader(sink)
            val run = ResourceRefs.run(runId)

            var cursor: EventCursor? = null
            val seen = mutableListOf<Long>()
            var pages = 0
            do {
                val page = tail.readAfter(run, cursor, limit = 3)
                assertEquals(page.envelopes.map { it.sequence }, page.envelopes.map { it.sequence }.distinct(), "$name duplicates")
                seen += page.envelopes.map { it.sequence }
                cursor = page.nextCursor
                pages++
            } while (page.hasMore && pages < 10)
            assertEquals((1L..7L).toList(), seen, "$name: gap/overlap in pagination")
            assertFalse(pages > 3, "$name: expected 3 pages")
        }
    }

    @org.junit.jupiter.api.Test
    fun `filters - kind, subject, sequence range`() {
        sampledSinkInstances().forEach { (name, sink) ->
            appendAll(sink)
            val h: EventHistory = EventHistoryReader(sink)
            val run = ResourceRefs.run(runId)

            val stages = h.history(run, EventQuery.ByKind("StageStarted")).toList()
            assertEquals(listOf("StageStarted"), stages.map { it.kind }, name)
            assertEquals(ResourceRefs.stage(runId, 0).canonicalText(), stages.single().subject.canonicalText(), name)

            val bySubject = h.history(run, EventQuery.BySubject(ResourceRefs.step(runId, 0, 2))).toList()
            assertEquals(listOf("StepStarted"), bySubject.map { it.kind }, name)

            val bySource = h.history(run, EventQuery.BySource(ResourceRefs.run(runId))).toList()
            assertEquals(7, bySource.size, name)

            val range = h.history(run, EventQuery.BySequenceRange(3, 5)).toList()
            assertEquals(listOf(3L, 4L, 5L), range.map { it.sequence }, name)
        }
    }

    @org.junit.jupiter.api.Test
    fun `restart - sqlite history identical after close and reopen`(@TempDir dir: Path) {
        val db = dir.resolve("restart.db").toString()
        val storeA = SqliteEventStore(db)
        appendAll(storeA)
        storeA.close()

        val storeB = SqliteEventStore(db)
        val reader = EventHistoryReader(storeB)
        val history = reader.history(ResourceRefs.run(runId)).toList()
        assertEquals(7, history.size)
        assertEquals((1L..7L).toList(), history.map { it.sequence })
        assertEquals("RunStarted", history.first().kind)
        assertEquals("RunFinished", history.last().kind)
        // cursor continuation across process boundary
        val page1 = reader.readAfter(ResourceRefs.run(runId), null, 5)
        assertEquals(5, page1.envelopes.size)
        assertTrue(page1.hasMore)
        val page2 = reader.readAfter(ResourceRefs.run(runId), page1.nextCursor, 5)
        assertEquals(listOf(6L, 7L), page2.envelopes.map { it.sequence })
        assertFalse(page2.hasMore)
        storeB.close()
    }

    @org.junit.jupiter.api.Test
    fun `cursor codec - opaque token roundtrip and rejection`() {
        val c = EventCursor(runId, 42)
        assertEquals(c, EventCursor.decode(c.encode()))
        assertEquals(null, EventCursor.decode("nonsense"))
        assertEquals(null, EventCursor.decode("evt-cursor-v1:run:notanumber"))
        // Never timestamp-based: token contains no temporal component
        assertFalse(c.encode().contains("T") || c.encode().contains("2026"))
    }

    @org.junit.jupiter.api.Test
    fun `projection carries STORE-assigned sequence (authority law)`() {
        sampledSinkInstances().forEach { (name, sink) ->
            val publisher = CollectingPublisher()
            val projecting = EnvelopeProjectingEventSink(sink, publisher)
            // producer sends sequence=0: the store ASSIGNS (authority)
            projecting.append(
                RunStarted(eventId = "eZ", runId = "run-auth", sequence = 0, occurredAt = at, scriptPath = "/p"),
            )
            val stored = sink.eventsFor("run-auth").single()
            assertEquals(stored.sequence, publisher.envelopes.single().sequence, "$name: envelope must carry stored sequence")
            assertEquals(1L, stored.sequence, "$name: store assigns 1 for fresh run")
        }
    }

    private fun createTempDb(): String =
        java.nio.file.Files.createTempDirectory("evt2-ct").resolve("events.db").toString()
}

class CollectingPublisher : EventPublisher {
    val envelopes = mutableListOf<PipelineEventEnvelope>()
    override fun publish(event: PipelineEventEnvelope) {
        envelopes += event
    }
}
