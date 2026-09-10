package dev.rubentxu.pipeline.v2.events.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * EVT-1 deterministic-identity laws (spec R1/R2). All refs derive from logical
 * identity only: no timestamps, no occurrence UUIDs, no scheduler ordering.
 */
class ResourceRefDeterminismTest {

    private val runId = "01987654-3210-fedc-ba98-76543210fedc"
    private val definitionId = "my-pipeline"

    @Nested
    inner class DeterministicConstruction {
        @Test
        fun `R1 - same logical entity yields equal refs`() {
            val a = ResourceRefs.pipeline(definitionId)
            val b = ResourceRefs.pipeline(definitionId)
            assertEquals(a, b)
            assertEquals(a.canonicalText(), b.canonicalText())

            val ra = ResourceRefs.run(runId)
            val rb = ResourceRefs.run(runId)
            assertEquals(ra, rb)
            assertEquals(ra.canonicalText(), rb.canonicalText())

            val sa = ResourceRefs.stage(runId, 0)
            val sb = ResourceRefs.stage(runId, 0)
            assertEquals(sa, sb)

            val ta = ResourceRefs.step(runId, 0, 2)
            val tb = ResourceRefs.step(runId, 0, 2)
            assertEquals(ta, tb)

            val oa = ResourceRefs.operation(runId, 0, 2, "sh-0")
            val ob = ResourceRefs.operation(runId, 0, 2, "sh-0")
            assertEquals(oa, ob)
        }

        @Test
        fun `R1 - distinct operations yield distinct refs`() {
            val o1 = ResourceRefs.operation(runId, 0, 2, "sh-0")
            val o2 = ResourceRefs.operation(runId, 0, 2, "sh-1")
            val o3 = ResourceRefs.operation(runId, 0, 3, "sh-0")
            val o4 = ResourceRefs.operation(runId, 1, 2, "sh-0")
            assertNotEquals(o1, o2)
            assertNotEquals(o1, o3)
            assertNotEquals(o1, o4)
        }

        @Test
        fun `R1 kinds are hierarchical parent segment is prefix of child path`() {
            val run = ResourceRefs.run(runId)
            val stage = ResourceRefs.stage(runId, 0)
            val step = ResourceRefs.step(runId, 0, 1)
            assertTrue(stage.segments.take(run.segments.size) == run.segments)
            assertTrue(step.segments.take(stage.segments.size) == stage.segments)
            assertEquals(ResourceKind.RUN, run.kind)
            assertEquals(ResourceKind.STAGE, stage.kind)
            assertEquals(ResourceKind.STEP, step.kind)
        }
    }

    @Nested
    inner class FailClosedConstruction {
        @Test
        fun `R1 - empty segment is rejected`() {
            assertThrows(InvalidResourceRefException::class.java) {
                ResourceRefs.run("")
            }
        }

        @Test
        fun `R1 - path separators are rejected, not sanitized`() {
            assertThrows(InvalidResourceRefException::class.java) {
                ResourceRefs.run("../../etc/passwd")
            }
            assertThrows(InvalidResourceRefException::class.java) {
                ResourceRefs.pipeline("a/b")
            }
        }

        @Test
        fun `R1 - control characters and spaces are rejected`() {
            assertThrows(InvalidResourceRefException::class.java) {
                ResourceRefs.pipeline("a\nb")
            }
            assertThrows(InvalidResourceRefException::class.java) {
                ResourceRefs.pipeline("my pipeline")
            }
        }

        @Test
        fun `R1 canonical text is versioned`() {
            assertTrue(ResourceRefs.run(runId).canonicalText().startsWith("v1:run:"))
        }
    }

    @Nested
    inner class EventRefLaws {
        @Test
        fun `R2 - EventRef uniqueness is (source, eventId)`() {
            val source = ResourceRefs.run(runId)
            val other = ResourceRefs.run("another-run")
            val e1 = EventRef(source, EventId("evt-1"))
            val e2 = EventRef(source, EventId("evt-2"))
            val e3 = EventRef(other, EventId("evt-1"))
            val e1bis = EventRef(source, EventId("evt-1"))
            assertNotEquals(e1, e2)
            assertNotEquals(e1, e3)
            assertEquals(e1, e1bis)
        }

        @Test
        fun `R2 - blank EventId is rejected`() {
            assertThrows(IllegalArgumentException::class.java) { EventId(" ") }
        }
    }
}
