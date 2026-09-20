package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * WU-LPR-050 — Read-side projection tests.
 *
 * The projection is a pure `(envelopes, mode, format) -> List<String>` so
 * tests can construct fixtures directly and assert on the resulting lines.
 * The CLI (`MainEventsCli`) is the effectful boundary that consumes the
 * projection; it is not exercised here.
 */
class WULpr050EventViewProjectionTest {

    private val runId = "01987654-3210-fedc-ba98-76543210fedc"
    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private val runRef = ResourceRefs.run(runId)

    private fun envelope(seq: Long, kind: String): PipelineEventEnvelope =
        PipelineEventEnvelope(
            version = PipelineEventEnvelope.VERSION,
            eventRef = EventRef(runRef, EventId("evt-$seq")),
            kind = kind,
            occurredAt = at,
            sequence = seq,
            subject = runRef,
        )

    private val lifecycle = listOf(
        envelope(1, "CompilationStarted"),
        envelope(2, "CompilationFinished"),
        envelope(3, "RunStarted"),
        envelope(4, "StageStarted"),
        envelope(5, "StepStarted"),
        envelope(6, "StepFinished"),
        envelope(7, "StageFinished"),
        envelope(8, "RunFinished"),
    )
    private val transcript = listOf(
        envelope(9, "EchoOutputCaptured"),
        envelope(10, "UnixDetected"),
        envelope(11, "PwdResolved"),
    )
    private val all = lifecycle + transcript

    @Nested
    @DisplayName("ViewMode filtering")
    inner class ModeFiltering {

        @Test
        fun `events view includes every envelope`() {
            val lines = EventViewProjection.project(all, ViewMode.EVENTS, OutputFormat.TEXT)
            assertEquals(all.size, lines.size)
        }

        @Test
        fun `full view is an alias for events (every envelope)`() {
            assertEquals(
                EventViewProjection.project(all, ViewMode.FULL, OutputFormat.TEXT),
                EventViewProjection.project(all, ViewMode.EVENTS, OutputFormat.TEXT),
            )
        }

        @Test
        fun `normal view includes only lifecycle envelopes`() {
            val lines = EventViewProjection.project(all, ViewMode.NORMAL, OutputFormat.TEXT)
            assertEquals(lifecycle.size, lines.size)
            lifecycle.forEach { e ->
                assertTrue(lines.any { it.endsWith(e.kind) }, "normal view must include $e.kind")
            }
        }

        @Test
        fun `normal view drops EchoOutputCaptured`() {
            val lines = EventViewProjection.project(all, ViewMode.NORMAL, OutputFormat.TEXT)
            assertTrue(lines.none { it.endsWith("EchoOutputCaptured") })
        }

        @Test
        fun `console view includes only EchoOutputCaptured envelopes`() {
            // Only EchoOutputCaptured is part of the console transcript; the
            // UnixDetected / PwdResolved / etc. envelopes are NOT console
            // output and are filtered out of the CONSOLE view.
            val consoleOnly = listOf(envelope(9, "EchoOutputCaptured"))
            val lines = EventViewProjection.project(all, ViewMode.CONSOLE, OutputFormat.TEXT)
            assertEquals(consoleOnly.size, lines.size)
            assertTrue(lines.all { it.endsWith("EchoOutputCaptured") })
        }

        @Test
        fun `quiet view includes only RunFinished`() {
            val lines = EventViewProjection.project(all, ViewMode.QUIET, OutputFormat.TEXT)
            assertEquals(1, lines.size)
            assertTrue(lines[0].endsWith("RunFinished"))
        }
    }

    @Nested
    @DisplayName("OutputFormat rendering")
    inner class FormatRendering {

        @Test
        fun `jsonl format emits one envelope per line`() {
            val lines = EventViewProjection.project(lifecycle, ViewMode.EVENTS, OutputFormat.JSONL)
            assertEquals(lifecycle.size, lines.size)
            lines.forEach { line ->
                assertTrue(line.startsWith("{") && line.endsWith("}"), "jsonl line must be a JSON object: $line")
            }
        }

        @Test
        fun `json format emits one line carrying a JSON array`() {
            val lines = EventViewProjection.project(lifecycle, ViewMode.EVENTS, OutputFormat.JSON)
            assertEquals(1, lines.size)
            val line = lines[0]
            assertTrue(line.startsWith("[") && line.endsWith("]"), "json line must be a JSON array: $line")
        }

        @Test
        fun `text format emits sequence and kind per envelope`() {
            val lines = EventViewProjection.project(lifecycle, ViewMode.EVENTS, OutputFormat.TEXT)
            assertEquals(lifecycle.size, lines.size)
            lines.forEachIndexed { idx, line ->
                val expected = "${lifecycle[idx].sequence} ${lifecycle[idx].kind}"
                assertEquals(expected, line)
            }
        }
    }

    @Nested
    @DisplayName("Combined mode + format")
    inner class Combined {

        @Test
        fun `normal + text yields one text line per lifecycle envelope`() {
            val lines = EventViewProjection.project(all, ViewMode.NORMAL, OutputFormat.TEXT)
            assertEquals(lifecycle.size, lines.size)
        }

        @Test
        fun `console + jsonl yields one JSON line per EchoOutputCaptured envelope`() {
            // Console view emits only EchoOutputCaptured (one of the three
            // transcript envelopes above; the other two are filtered out).
            val consoleOnly = listOf(envelope(9, "EchoOutputCaptured"))
            val lines = EventViewProjection.project(all, ViewMode.CONSOLE, OutputFormat.JSONL)
            assertEquals(consoleOnly.size, lines.size)
            lines.forEach { line ->
                assertTrue(line.contains("EchoOutputCaptured"), "console jsonl must be EchoOutputCaptured: $line")
            }
        }

        @Test
        fun `quiet + json yields one JSON-array line containing only RunFinished`() {
            val lines = EventViewProjection.project(all, ViewMode.QUIET, OutputFormat.JSON)
            assertEquals(1, lines.size)
            assertTrue(lines[0].contains("RunFinished"))
            assertTrue(!lines[0].contains("EchoOutputCaptured"))
        }
    }

    @Nested
    @DisplayName("Empty input")
    inner class EmptyInput {

        @Test
        fun `empty input yields no lines for jsonl and text modes`() {
            // jsonl and text produce one line per envelope; empty input → empty list.
            assertEquals(emptyList<String>(), EventViewProjection.project(emptyList(), ViewMode.NORMAL, OutputFormat.JSONL))
            assertEquals(emptyList<String>(), EventViewProjection.project(emptyList(), ViewMode.EVENTS, OutputFormat.TEXT))
            // json produces a single JSON-array line that may be empty ("[]").
            val jsonLines = EventViewProjection.project(emptyList(), ViewMode.EVENTS, OutputFormat.JSON)
            assertEquals(1, jsonLines.size)
            assertEquals("[]", jsonLines[0])
        }
    }
}
