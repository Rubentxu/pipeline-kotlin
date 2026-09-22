package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-RP-022 (finding P2): transcripts larger than SQLITE_MAX_LENGTH killed the
 * durable event writer with SQLITE_TOOBIG. The transcript emission therefore
 * chunks content so no single EchoOutputCaptured payload can exceed a size far
 * below the SQLite limit. Contract under test:
 *  - lossless: concatenating chunk contents reproduces the original transcript;
 *  - ordered: chunk i+1 continues exactly where chunk i ended;
 *  - bounded: no chunk exceeds MAX_TRANSCRIPT_CHUNK_CHARS;
 *  - single-chunk behaviour is unchanged for the common (small) case.
 */
class TranscriptChunkingTest {

    private fun captured(sink: InMemoryEventStore, runId: String): List<EchoOutputCaptured> =
        sink.eventsFor(runId).filterIsInstance<EchoOutputCaptured>().toList()

    @Test
    fun `small transcript emits exactly one event identical to content`() {
        val sink = InMemoryEventStore()
        val content = "hello world\n"
        ShExecution.emitTranscriptChunked(sink, runId = "r1", stepIndex = 3, content = content)

        val events = captured(sink, "r1")
        assertEquals(1, events.size)
        assertEquals(content, events.single().content)
        assertEquals(3, events.single().stepIndex)
        assertEquals("r1", events.single().runId)
    }

    @Test
    fun `oversized transcript chunks are lossless ordered and bounded`() {
        val sink = InMemoryEventStore()
        // Just over 2 full chunks plus a remainder: exercises multiple splits
        // and a final partial chunk.
        val content = "y".repeat(ShExecution.MAX_TRANSCRIPT_CHUNK_CHARS * 2 + 12345)
        ShExecution.emitTranscriptChunked(sink, runId = "r2", stepIndex = 0, content = content)

        val events = captured(sink, "r2")
        assertEquals(3, events.size)
        // Lossless + ordered: verify chunk boundaries without materialising a
        // 128M-char concatenation (would OOM the test JVM).
        var expectedOffset = 0
        events.forEach { chunk ->
            assertEquals(expectedOffset, content.indexOf(chunk.content, expectedOffset))
            expectedOffset += chunk.content.length
        }
        assertEquals(content.length, expectedOffset)
    }

    @Test
    fun `content of exactly one chunk size emits a single event`() {
        val sink = InMemoryEventStore()
        val content = "x".repeat(ShExecution.MAX_TRANSCRIPT_CHUNK_CHARS)
        ShExecution.emitTranscriptChunked(sink, runId = "r3", stepIndex = 1, content = content)

        val events = captured(sink, "r3")
        assertEquals(1, events.size)
        assertEquals(content, events.single().content)
    }
}
