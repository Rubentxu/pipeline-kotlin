package dev.rubentxu.pipeline.v2.application.harness

import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaTerminalState
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.identity.EventId
import dev.rubentxu.pipeline.v2.events.identity.EventRef
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import dev.rubentxu.pipeline.v2.harness.model.ViolationRule
import dev.rubentxu.pipeline.v2.harness.verify.ProtocolGrammar
import dev.rubentxu.pipeline.v2.harness.verify.TypedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The law that a green run and a compensation cannot both be true.
 *
 * ## Why this test lives here and not in the harness
 *
 * `:pipeline-event-harness` carries no unit tests on purpose: it is the runtime behind the
 * `pipeline events verify` verb, and its proof was declared to be that verb. The verb is
 * product functionality, so its proof belongs to the module that ships it — which is this one.
 *
 * ## Why the law matters
 *
 * A compensated Saga undid what it did: the resource was released, the payment refunded. A run
 * that reports success after that tells the operator that the world is as it should be, and the
 * world is not. The two facts contradict each other, and a plane that carries both is a broken
 * plane.
 *
 * ## Why the law became writable only now
 *
 * Until a Saga could be closed WELL there was only one way to end one — compensate — so a chain
 * that succeeded used to undo itself and the run still said "success". The law was therefore
 * FALSE of the product, and a law the product breaks is a comment.
 */
class SagaCompensatedOnSuccessLawTest {

    @Test
    fun `a green run whose Saga compensated is a violation`() {
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("success"),
                sagaTerminal(compensatedActions = listOf("charge")),
            ),
        )

        assertEquals(
            listOf(ViolationRule.LAW_SAGA_COMPENSATED_ON_SUCCESS),
            violations.map { it.rule },
        )
    }

    @Test
    fun `a green run whose Saga kept its effects is not a violation`() {
        // The shape a real run of a successful chain has: the Saga settled and compensated
        // nothing, because there was nothing to undo.
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("success"),
                sagaTerminal(compensatedActions = emptyList()),
            ),
        )

        assertTrue(violations.none { it.rule == ViolationRule.LAW_SAGA_COMPENSATED_ON_SUCCESS })
    }

    @Test
    fun `a failed run whose Saga compensated is not a violation`() {
        // A Saga that failed IS undone, and the run says so. The law is about the run that says
        // everything is fine while it undid its own work.
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("failure"),
                sagaTerminal(compensatedActions = listOf("charge")),
            ),
        )

        assertTrue(violations.none { it.rule == ViolationRule.LAW_SAGA_COMPENSATED_ON_SUCCESS })
    }

    @Test
    fun `a compensation in an earlier attempt is still a compensation`() {
        // A resumed run reuses the run id, so a plane may carry a Saga that compensated in the
        // first attempt and settled empty in the second. A compensation is not a state: it is
        // something that HAPPENED, and the newest terminal cannot un-happen it.
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("success"),
                sagaTerminal(compensatedActions = listOf("charge")),
                sagaTerminal(compensatedActions = emptyList()),
            ),
        )

        assertEquals(
            listOf(ViolationRule.LAW_SAGA_COMPENSATED_ON_SUCCESS),
            violations.map { it.rule },
        )
    }

    /**
     * A well-formed plane, plus the events the test is about.
     *
     * The [RunStarted] is always there because a plane without one breaks a DIFFERENT law, and
     * this file is about one law: a plane that is broken in two ways at once cannot say which
     * one the test proved.
     */
    private fun historyOf(vararg events: DomainEvent): List<TypedEvent> =
        (listOf(runStarted()) + events).mapIndexed { index, event -> typed(event, index.toLong() + 1L) }

    private fun typed(event: DomainEvent, sequence: Long): TypedEvent = TypedEvent(
        envelope = PipelineEventEnvelope(
            version = PipelineEventEnvelope.VERSION,
            eventRef = EventRef(ResourceRef(ResourceKind.RUN, listOf(RUN)), EventId(event.eventId)),
            kind = event.kind,
            occurredAt = Instant.EPOCH,
            sequence = sequence,
            subject = ResourceRef(ResourceKind.RUN, listOf(RUN)),
        ),
        event = event,
    )

    private fun runFinished(outcome: String): RunFinished = RunFinished(
        eventId = "event-run-finished",
        runId = RUN,
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        outcome = outcome,
        diagnostics = emptyList(),
    )

    private fun runStarted(): RunStarted = RunStarted(
        eventId = "event-run-started",
        runId = RUN,
        sequence = 0L,
        occurredAt = Instant.EPOCH,
        scriptPath = "pipeline.kts",
    )

    private fun sagaTerminal(compensatedActions: List<String>): SagaTerminal = SagaTerminal(
        eventId = "event-saga-terminal",
        runId = RUN,
        sequence = 2L,
        occurredAt = Instant.EPOCH,
        sagaId = SAGA,
        state = SagaTerminalState.SETTLED.name,
        reason = null,
        compensatedActions = compensatedActions,
    )

    private companion object {
        const val RUN = "run-1"
        const val SAGA = "checkout"
    }
}
