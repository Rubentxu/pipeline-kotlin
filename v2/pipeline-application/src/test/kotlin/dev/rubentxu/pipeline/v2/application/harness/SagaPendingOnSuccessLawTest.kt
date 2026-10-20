package dev.rubentxu.pipeline.v2.application.harness

import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaTerminalState
import dev.rubentxu.pipeline.v2.events.CompensationArmed
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
 * The law that a green run and an open Saga cannot both be true.
 *
 * ## Why this test lives here and not in the harness
 *
 * `:pipeline-event-harness` carries no unit tests on purpose: it is the runtime behind the
 * `pipeline events verify` verb, and its proof was declared to be that verb. The verb is
 * product functionality, so its proof belongs to the module that ships it — which is this one.
 *
 * ## Why the law matters
 *
 * A Saga that needs a human is work nobody has done. A run that reports success while such a
 * Saga is open tells the operator that everything is fine while a resource may be left applied.
 * The two facts contradict each other, and a plane that carries both is a broken plane.
 */
class SagaPendingOnSuccessLawTest {

    @Test
    fun `a green run with a Saga that needs a human is a violation`() {
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("success"),
                sagaTerminal(state = SagaTerminalState.NEEDS_INTERVENTION.name, reason = "UNKNOWN_EFFECT"),
            ),
        )

        assertEquals(
            listOf(ViolationRule.LAW_SAGA_PENDING_ON_SUCCESS),
            violations.map { it.rule },
        )
    }

    @Test
    fun `a green run with a Saga that armed and never announced is a violation`() {
        // The absence of an announcement is the evidence: a Saga that owes something and
        // never said how it ended is still open.
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("success"),
                compensationArmed(),
            ),
        )

        assertEquals(
            listOf(ViolationRule.LAW_SAGA_PENDING_ON_SUCCESS),
            violations.map { it.rule },
        )
    }

    @Test
    fun `a green run whose Saga settled is not a violation`() {
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("success"),
                compensationArmed(),
                sagaTerminal(state = SagaTerminalState.SETTLED.name),
            ),
        )

        assertTrue(violations.none { it.rule == ViolationRule.LAW_SAGA_PENDING_ON_SUCCESS })
    }

    @Test
    fun `a failed run with a Saga that needs a human is not a violation`() {
        // The run already says what happened. The law is about the run that says nothing
        // is wrong while something IS wrong.
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("failure"),
                sagaTerminal(state = SagaTerminalState.NEEDS_INTERVENTION.name, reason = "UNKNOWN_EFFECT"),
            ),
        )

        assertTrue(violations.none { it.rule == ViolationRule.LAW_SAGA_PENDING_ON_SUCCESS })
    }

    @Test
    fun `the newest announcement of a Saga is the one that counts`() {
        // A resumed run reuses the run id, so a Saga may be announced twice. The newer word
        // is where the Saga stands now.
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("success"),
                sagaTerminal(state = SagaTerminalState.NEEDS_INTERVENTION.name, reason = "UNKNOWN_EFFECT"),
                sagaTerminal(state = SagaTerminalState.SETTLED.name),
            ),
        )

        assertTrue(violations.none { it.rule == ViolationRule.LAW_SAGA_PENDING_ON_SUCCESS })
    }

    @Test
    fun `a green run whose Saga says SETTLED with a reason is a violation`() {
        // The terminal CONTRADICTS ITSELF: it announces SETTLED and carries a reason for not
        // settling. The published projection reads that as "a human has to decide"
        // (TERMINAL_UNREADABLE) — a Saga that cannot be read is never read as settled — and
        // the law has to read it the same way, because a second reader is a second rule and two
        // rules about the same fact drift apart. This one looked at the WORD and missed the
        // reason, so a broken Saga passed as a finished one.
        val violations = ProtocolGrammar.check(
            historyOf(
                runFinished("success"),
                sagaTerminal(state = SagaTerminalState.SETTLED.name, reason = "UNKNOWN_EFFECT"),
            ),
        )

        assertEquals(
            listOf(ViolationRule.LAW_SAGA_PENDING_ON_SUCCESS),
            violations.map { it.rule },
        )
    }

    /**
     * A well-formed plane, plus the events the test is about.
     *
     * The [RunStarted] is always there because a plane without one breaks a DIFFERENT law,
     * and this file is about one law: a plane that is broken in two ways at once cannot say
     * which one the test proved.
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

    private fun sagaTerminal(state: String, reason: String? = null): SagaTerminal = SagaTerminal(
        eventId = "event-saga-terminal",
        runId = RUN,
        sequence = 2L,
        occurredAt = Instant.EPOCH,
        sagaId = SAGA,
        state = state,
        reason = reason,
        compensatedActions = emptyList(),
    )

    private fun compensationArmed(): CompensationArmed = CompensationArmed(
        eventId = "event-compensation-armed",
        runId = RUN,
        sequence = 0L,
        occurredAt = Instant.EPOCH,
        sagaId = SAGA,
        actionId = "reserve",
        compensationId = "release",
        planFingerprint = "fingerprint",
        intentVersion = 1,
        resourceRef = "stock/42",
        compensationOpId = "saga-v1|compensate|release",
        leaseEpoch = 1L,
    )

    private companion object {
        const val RUN = "run-1"
        const val SAGA = "checkout"
    }
}
