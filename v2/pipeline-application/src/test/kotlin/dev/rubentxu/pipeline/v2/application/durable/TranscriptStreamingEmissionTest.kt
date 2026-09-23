package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * WU-RP-044 (M5 RSS debt): streaming transcript emission. Contract identical
 * to the in-memory [TranscriptChunkingTest]: chunks are contiguous, ordered,
 * lossless (concatenation reproduces the transcript exactly) and bounded by
 * MAX_TRANSCRIPT_CHUNK_CHARS. The streaming emitter never materialises the
 * full transcript: it reads the source incrementally.
 */
class TranscriptStreamingEmissionTest {

    private fun captured(sink: InMemoryEventStore, runId: String): List<EchoOutputCaptured> =
        sink.eventsFor(runId).filterIsInstance<EchoOutputCaptured>().toList()

    @Test
    fun `small transcript emits exactly one event identical to content`() {
        val sink = InMemoryEventStore()
        val content = "hello streaming\n"

        ShExecution.emitTranscriptStreaming(
            eventSink = sink,
            runId = "r1",
            stepIndex = 2,
            source = { ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) },
            secretPatternRegistry = null,
        )

        val events = captured(sink, "r1")
        assertEquals(1, events.size)
        assertEquals(content, events.single().content)
        assertEquals(2, events.single().stepIndex)
    }

    @Test
    fun `missing source emits nothing`() {
        val sink = InMemoryEventStore()
        ShExecution.emitTranscriptStreaming(sink, "r2", 0, { null }, null)
        assertTrue(captured(sink, "r2").isEmpty())
    }

    @Test
    fun `empty stream emits nothing`() {
        val sink = InMemoryEventStore()
        ShExecution.emitTranscriptStreaming(sink, "r3", 0, { ByteArrayInputStream(ByteArray(0)) }, null)
        assertTrue(captured(sink, "r3").isEmpty())
    }

    @Test
    fun `oversized stream chunks are lossless ordered and bounded`() {
        val sink = InMemoryEventStore()
        // A few full windows plus a remainder — kept small relative to the 64M
        // window by stubbing the chunk bound through a narrow stream: we cannot
        // shrink the constant, so use 2 windows + remainder of 'y' (128 MiB+).
        // To keep the test fast we instead verify boundary semantics with a
        // stream that reports EOF mid-window: exactly one bounded chunk.
        val payload = "y".repeat(ShExecution.MAX_TRANSCRIPT_CHUNK_CHARS + 12345)
        ShExecution.emitTranscriptStreaming(
            sink, "r4", 0,
            { ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)) },
            null,
        )

        val events = captured(sink, "r4")
        assertEquals(2, events.size)
        assertTrue(events.all { it.content.length <= ShExecution.MAX_TRANSCRIPT_CHUNK_CHARS })
        // Lossless + ordered
        assertEquals(payload.length, events.sumOf { it.content.length })
        assertEquals(payload, events[0].content + events[1].content)
    }
}
