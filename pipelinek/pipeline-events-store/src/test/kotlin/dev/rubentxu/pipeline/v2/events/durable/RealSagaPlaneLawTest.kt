package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.events.identity.EventId
import dev.rubentxu.pipeline.v2.events.identity.EventRef
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import dev.rubentxu.pipeline.v2.harness.model.ViolationRule
import dev.rubentxu.pipeline.v2.harness.verify.ProtocolGrammar
import dev.rubentxu.pipeline.v2.harness.verify.TypedEvent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The plane laws, read over a REAL run's plane.
 *
 * ## Why this test is here and not in `:pipeline-events`
 *
 * Because the part that can lose information is not the law but the DECODER. The wire format
 * writes an absent optional string as the empty string, and a decoder that forgot to map ""
 * back to `null` — or that read a `compensatedActions` list as always empty — would hand the
 * grammar a plane that cannot fail, and a law that cannot fail is a comment.
 *
 * The decoder lives in this module, so this is where the two halves meet: the real bytes, the
 * real decoder, and the laws a consumer of the SDK would run.
 *
 * ## Where the bytes come from
 *
 * They are the captured plane of a REAL run — the scenario whose chain SUCCEEDED, the path that
 * did not exist before the closing verb — read out of the durable store of that run. Nothing
 * here is written by hand: the file is what the engine wrote.
 */
class RealSagaPlaneLawTest {

    @Test
    fun `a real run whose chain worked did not compensate anything`() {
        // The law: a run that reports success cannot have undone its own work. The real plane of
        // a successful chain is the shape that satisfies it — a Saga that settled, compensating
        // nothing, because there was nothing to undo.
        val violations = ProtocolGrammar.check(typedHistory(plane(SUCCESS_PLANE)))

        assertTrue(
            violations.none { it.rule == ViolationRule.LAW_SAGA_COMPENSATED_ON_SUCCESS },
            "a successful chain must not have compensated: $violations",
        )
    }

    @Test
    fun `a real run whose chain worked left no Saga pending`() {
        // The sister law, over the same bytes: the Saga settled, so nothing is open. A plane
        // that armed an obligation and never settled would be a run that says "success" while
        // work nobody has done is still waiting.
        val violations = ProtocolGrammar.check(typedHistory(plane(SUCCESS_PLANE)))

        assertTrue(
            violations.none { it.rule == ViolationRule.LAW_SAGA_PENDING_ON_SUCCESS },
            "a successful chain must not leave a Saga pending: $violations",
        )
    }

    /**
     * The captured bytes, read by the PRODUCTION decoder and given to the grammar.
     *
     * The envelope is rebuilt from the event's own facts — the run it belongs to and its own
     * sequence — because the typed decoder does not keep the wire envelope. The sequences are
     * the run's own, so the grammar's ordering and its bounded window see the real order.
     */
    private fun typedHistory(payload: String): List<TypedEvent> =
        JsonEventLog.decode(payload).map { event ->
            TypedEvent(
                envelope = PipelineEventEnvelope(
                    version = PipelineEventEnvelope.VERSION,
                    eventRef = EventRef(
                        ResourceRef(ResourceKind.RUN, listOf(event.runId)),
                        EventId(event.eventId),
                    ),
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    sequence = event.sequence,
                    subject = ResourceRef(ResourceKind.RUN, listOf(event.runId)),
                ),
                event = event,
            )
        }

    private fun plane(name: String): String =
        checkNotNull(javaClass.getResource("/$name")) {
            "the captured plane '$name' is missing from the test resources"
        }.readText()

    private companion object {
        const val SUCCESS_PLANE = "real-saga-plane-success.json"
    }
}
