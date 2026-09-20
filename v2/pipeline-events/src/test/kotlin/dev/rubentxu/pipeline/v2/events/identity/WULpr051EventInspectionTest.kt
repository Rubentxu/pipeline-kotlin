package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * WU-LPR-051 — Agent-efficient inspection tests.
 *
 * The inspection is a pure transformation over envelopes: field
 * selection, context window, tail, follow. The contract is closed — no
 * query language, no arbitrary predicates; agents pass typed values.
 */
class WULpr051EventInspectionTest {

    private val runId = "01987654-3210-fedc-ba98-76543210fedc"
    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private val runRef = ResourceRefs.run(runId)

    private fun envelope(seq: Long, kind: String): PipelineEventEnvelope =
        PipelineEventEnvelope(
            version = PipelineEventEnvelope.VERSION,
            eventRef = EventRef(source = runRef, id = EventId("evt-$seq")),
            kind = kind,
            occurredAt = at,
            sequence = seq,
            subject = runRef,
        )

    private val sample = listOf(
        envelope(1, "CompilationStarted"),
        envelope(2, "CompilationFinished"),
        envelope(3, "RunStarted"),
        envelope(4, "StageStarted"),
        envelope(5, "StepStarted"),
        envelope(6, "EchoOutputCaptured"),
        envelope(7, "StepFinished"),
        envelope(8, "StageFinished"),
        envelope(9, "RunFinished"),
    )

    @Nested
    @DisplayName("Field selection (projectFields)")
    inner class FieldSelection {

        @Test
        fun `emits header line plus one line per envelope`() {
            val lines = EventInspection.projectFields(sample, listOf(EventField.Sequence, EventField.Kind))
            assertEquals(1 + sample.size, lines.size)
            assertEquals("seq kind", lines[0])
        }

        @Test
        fun `sequence field renders the integer sequence`() {
            val lines = EventInspection.projectFields(sample, listOf(EventField.Sequence))
            assertEquals("seq", lines[0])
            assertEquals("1", lines[1])
            assertEquals("9", lines[9])
        }

        @Test
        fun `kind field renders the kind string`() {
            val lines = EventInspection.projectFields(sample, listOf(EventField.Kind))
            assertEquals("kind", lines[0])
            assertEquals("RunStarted", lines[3])
            assertEquals("RunFinished", lines[9])
        }

        @Test
        fun `eventId field renders the EventRef id value`() {
            val lines = EventInspection.projectFields(sample, listOf(EventField.EventId))
            assertEquals("eventId", lines[0])
            assertEquals("evt-5", lines[5])
        }

        @Test
        fun `subject field renders the canonical text`() {
            val lines = EventInspection.projectFields(sample, listOf(EventField.Subject))
            assertEquals("subject", lines[0])
            assertTrue(lines.drop(1).all { it.startsWith("v1:run:") })
        }

        @Test
        fun `empty field list yields empty output`() {
            assertEquals(emptyList<String>(), EventInspection.projectFields(sample, emptyList()))
        }
    }

    @Nested
    @DisplayName("Context window (contextWindow)")
    inner class ContextWindow {

        @Test
        fun `matches RunFinished with before=2, after=0 yields 3 envelopes (RunFinished + 2 before)`() {
            val lines = EventInspection.contextWindow(sample, kindFilter = "RunFinished", before = 2, after = 0)
            assertEquals(3, lines.size)
            assertEquals(7L, lines[0].sequence) // StepFinished
            assertEquals(8L, lines[1].sequence) // StageFinished
            assertEquals(9L, lines[2].sequence) // RunFinished
        }

        @Test
        fun `matches StepStarted with before=1, after=2 yields 4 envelopes`() {
            val lines = EventInspection.contextWindow(sample, kindFilter = "StepStarted", before = 1, after = 2)
            assertEquals(4, lines.size)
            assertEquals(4L, lines[0].sequence) // StageStarted
            assertEquals(5L, lines[1].sequence) // StepStarted
            assertEquals(6L, lines[2].sequence) // EchoOutputCaptured
            assertEquals(7L, lines[3].sequence) // StepFinished
        }

        @Test
        fun `matches nothing when kindFilter has no hits`() {
            val lines = EventInspection.contextWindow(sample, kindFilter = "NonExistent", before = 5, after = 5)
            val empty: List<PipelineEventEnvelope> = emptyList()
            assertEquals(empty, lines)
        }

        @Test
        fun `before=0, after=0 yields only the matched envelope`() {
            val lines = EventInspection.contextWindow(sample, kindFilter = "RunStarted", before = 0, after = 0)
            assertEquals(1, lines.size)
            assertEquals("RunStarted", lines[0].kind)
        }

        @Test
        fun `before beyond start is coerced to 0`() {
            val lines = EventInspection.contextWindow(sample, kindFilter = "RunStarted", before = 999, after = 0)
            // RunStarted is at index 2; before clamps to 0; yields indices 0..2
            assertEquals(3, lines.size)
            assertEquals(1L, lines[0].sequence)
            assertEquals(3L, lines[2].sequence)
        }

        @Test
        fun `after beyond end is coerced to size-1`() {
            val lines = EventInspection.contextWindow(sample, kindFilter = "RunFinished", before = 0, after = 999)
            assertEquals(1, lines.size)
            assertEquals("RunFinished", lines[0].kind)
        }

        @Test
        fun `multiple matches dedupe (overlap collapses)`() {
            // Two matches (RunStarted at idx 2, StepStarted at idx 4) with
            // generous before/after windows produce overlapping output that
            // dedupes to a contiguous range.
            val lines = EventInspection.contextWindow(sample, kindFilter = "RunStarted", before = 0, after = 5)
            assertEquals(6, lines.size)
            // No duplicate entries
            val ids = lines.map { it.eventRef.id.value }
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    @Nested
    @DisplayName("Tail (tail)")
    inner class Tail {

        @Test
        fun `tail with count=3 yields the last 3 envelopes`() {
            val lines = EventInspection.tail(sample, count = 3)
            assertEquals(3, lines.size)
            assertEquals(7L, lines[0].sequence)
            assertEquals(9L, lines[2].sequence)
        }

        @Test
        fun `tail with count larger than size yields the whole list`() {
            val lines = EventInspection.tail(sample, count = 999)
            assertEquals(sample.size, lines.size)
        }

        @Test
        fun `tail with count=0 yields empty list`() {
            val empty: List<PipelineEventEnvelope> = emptyList()
            assertEquals(empty, EventInspection.tail(sample, count = 0))
        }
    }

    @Nested
    @DisplayName("Follow (follow)")
    inner class Follow {

        @Test
        fun `follow after sequence equals 4 yields envelopes with sequence greater than 4`() {
            val lines = EventInspection.follow(sample, afterSequence = 4)
            assertEquals(5, lines.size)
            assertEquals(5L, lines[0].sequence)
            assertEquals(9L, lines.last().sequence)
        }

        @Test
        fun `follow after the last sequence yields empty list`() {
            val empty: List<PipelineEventEnvelope> = emptyList()
            assertEquals(empty, EventInspection.follow(sample, afterSequence = 9))
        }

        @Test
        fun `follow after sequence=-1 yields the whole list`() {
            val lines = EventInspection.follow(sample, afterSequence = -1)
            assertEquals(sample.size, lines.size)
        }
    }

    @Nested
    @DisplayName("Invariants")
    inner class Invariants {

        @Test
        fun `contextWindow rejects negative before`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                EventInspection.contextWindow(sample, "RunFinished", before = -1, after = 0)
            }
            assertTrue(ex.message!!.contains("before"))
        }

        @Test
        fun `contextWindow rejects negative after`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                EventInspection.contextWindow(sample, "RunFinished", before = 0, after = -1)
            }
            assertTrue(ex.message!!.contains("after"))
        }

        @Test
        fun `tail rejects negative count`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                EventInspection.tail(sample, count = -1)
            }
            assertTrue(ex.message!!.contains("count"))
        }
    }
}
