package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs

import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * EVT-1 envelope round-trip, projection determinism and versioning laws
 * (spec R3/R4/R5). JSON codec is exercised on every roundtrip scenario.
 */
class EnvelopeProjectionTest {

    private val runId = "01987654-3210-fedc-ba98-76543210fedc"
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    @Nested
    inner class Roundtrip {
        @Test
        fun `R3 - envelope JSON roundtrip preserves identity`() {
            val e = PipelineEventEnvelope(
                version = PipelineEventEnvelope.VERSION,
                eventRef = EventRef(ResourceRefs.run(runId), EventId("evt-42")),
                kind = "RunStarted",
                occurredAt = at,
                sequence = 7,
                subject = ResourceRefs.run(runId),
            )
            val json = EnvelopeCodec.encode(e)
            val back = EnvelopeCodec.decode(json)
            assertEquals(e, back)
            // stable encoding, byte-identical for identical envelope
            assertEquals(json, EnvelopeCodec.encode(back))
        }

        @Test
        fun `R3 - list roundtrip preserves order`() {
            val es = (0..4).map { i ->
                PipelineEventEnvelope(
                    version = 1,
                    eventRef = EventRef(ResourceRefs.run(runId), EventId("evt-$i")),
                    kind = "K$i",
                    occurredAt = at,
                    sequence = i.toLong(),
                    subject = ResourceRefs.run(runId),
                )
            }
            val back = EnvelopeCodec.decodeAll(EnvelopeCodec.encodeAll(es))
            assertEquals(es, back)
        }

        @Test
        fun `R3 - unsupported version fails typed`() {
            val json = """{"version":99,"eventRefSource":{"kind":"RUN","segments":["r1"]},
                "eventRefId":"e1","kind":"K","occurredAt":"2026-01-01T00:00:00Z","sequence":1,
                "subject":{"kind":"RUN","segments":["r1"]}}""".replace("\n", "")
            assertThrows(UnsupportedEnvelopeVersionException::class.java) {
                EnvelopeCodec.decode(json)
            }
        }
    }

    @Nested
    inner class Projection {
        @Test
        fun `R4 - projection is deterministic across repeated calls`() {
            val ev = StepStarted(
                eventId = "evt-1", runId = runId, sequence = 3, occurredAt = at,
                stageIndex = 0, stepIndex = 2, stepName = "build", stepType = "sh",
            )
            val a = EnvelopeProjector.project(ev)
            val b = EnvelopeProjector.project(ev)
            assertEquals(a, b)
            assertEquals(ResourceRefs.step(runId, 0, 2), a.subject)
        }

        @Test
        fun `R4 - subject resolution per scope`() {
            val run = EnvelopeProjector.project(
                RunStarted(eventId = "e1", runId = runId, sequence = 1, occurredAt = at, scriptPath = "/x.pipeline.kts"),
            )
            assertEquals(ResourceRefs.run(runId), run.subject)

            val stage = EnvelopeProjector.project(
                StageStarted(eventId = "e2", runId = runId, sequence = 2, occurredAt = at, stageIndex = 1, stageName = "build"),
            )
            assertEquals(ResourceRefs.stage(runId, 1), stage.subject)
        }

        @Test
        fun `R4 - parallel branch completion order does not change refs`() {
            val s1 = EnvelopeProjector.project(
                StageStarted(eventId = "e1", runId = runId, sequence = 1, occurredAt = at, stageIndex = 0, stageName = "par"),
            )
            val s2 = EnvelopeProjector.project(
                StageStarted(eventId = "e2", runId = runId, sequence = 2, occurredAt = at, stageIndex = 0, stageName = "par"),
            )
            assertEquals(s1.subject, s2.subject)
        }

        @Test
        fun `R4 - stepIndex-only events resolve to RUN subject (frozen law)`() {
            val env = EnvelopeProjector.project(
                dev.rubentxu.pipeline.v2.events.EchoOutputCaptured(
                    eventId = "e8", runId = runId, sequence = 8, occurredAt = at,
                    stepIndex = 0, content = "hi",
                ),
            )
            assertEquals(ResourceRefs.run(runId), env.subject)
        }

        @Test
        fun `R4 - sequence is projected, not invented`() {
            val ev = StepFailed(
                eventId = "e9", runId = runId, sequence = 99, occurredAt = at,
                stepIndex = 4, stepName = "deploy", stepType = "sh",
                failureKind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT, message = "boom",
            )
            val env = EnvelopeProjector.project(ev)
            assertEquals(99L, env.sequence)
        }

        @Test
        fun `R5 - CloudEvents characterization mapping`() {
            val env = EnvelopeProjector.project(
                RunStarted(eventId = "evt-77", runId = runId, sequence = 1, occurredAt = at, scriptPath = "/x.pipeline.kts"),
            )
            val ce = env.toCloudEventsCharacterization()
            assertEquals("evt-77", ce.id)
            assertEquals(env.eventRef.source.canonicalText(), ce.source)
            assertEquals("RunStarted.v1", ce.type)
            assertEquals(env.subject.canonicalText(), ce.subject)
        }
    }
}
