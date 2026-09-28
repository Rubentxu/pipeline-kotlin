package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import dev.rubentxu.pipeline.v2.harness.model.AcceptanceOutcome
import dev.rubentxu.pipeline.v2.harness.model.EventConstraint
import dev.rubentxu.pipeline.v2.harness.model.EventContract
import dev.rubentxu.pipeline.v2.harness.model.EventViolation
import dev.rubentxu.pipeline.v2.harness.model.PipelineOutcome
import dev.rubentxu.pipeline.v2.harness.model.RelationScope
import dev.rubentxu.pipeline.v2.harness.model.VerificationReport
import dev.rubentxu.pipeline.v2.harness.model.VerificationResult

/**
 * Pure, deterministic verifier of typed protocol contracts over an ordered
 * observable history. Reads only; never mutates run state, journal, outcomes,
 * and never emits DomainEvents (no verifier loops).
 */
object EventHarness {

    /** History scope: whole run, or the last execution segment (after the LAST RunStarted). */
    enum class Scope { WHOLE, AFTER_LAST_RUN_STARTED }

    private fun scoped(history: List<TypedEvent>, scope: Scope): List<TypedEvent> =
        if (scope == Scope.WHOLE) history
        else {
            val lastStart = history.indexOfLast { it.kind == "RunStarted" }
            if (lastStart < 0) history else history.subList(lastStart, history.size)
        }

    /**
     * Adapter-side construction. Order = (store insertion order, sequence).
     * Under BASELINE DEBT INC-EVT3-1 durable reuse re-appends skeleton events with
     * restarting sequences; insertion order keeps the last execution segment a
     * contiguous suffix, so AFTER_LAST_RUN_STARTED scope stays deterministic
     * WITHOUT timestamps (INC-021d law honored).
     */
    fun typedHistory(sink: EventSink, runId: String, fromSequence: Long = 0L): List<TypedEvent> =
        sink.eventsFor(runId)
            .map { TypedEvent(envelope = EnvelopeProjector.project(it), event = it) }
            .filter { it.sequence > fromSequence }
            .toList()

    fun typedHistory(events: List<DomainEvent>): List<TypedEvent> =
        events.map { TypedEvent(EnvelopeProjector.project(it), it) }

    /**
     * Verify one contract. Pure: same inputs, same result, forever.
     */
    /**
     * Last execution segment: insertion-order suffix starting at the LAST RunStarted.
     * Deterministic under INC-EVT3-1 duplicate-sequence debt; never timestamp-based.
     */
    fun lastSegment(history: List<TypedEvent>): List<TypedEvent> = scoped(history, Scope.AFTER_LAST_RUN_STARTED)

    fun verify(history: List<TypedEvent>, contract: EventContract, scope: Scope = Scope.WHOLE): VerificationResult {
        val scoped = scoped(history, scope)
        val violations = mutableListOf<EventViolation>()
        for ((index, constraint) in contract.constraints.withIndex()) {
            ConstraintChecks.check(scoped, constraint, index, violations)
        }
        violations.addAll(ProtocolGrammar.check(scoped))
        return if (violations.isEmpty()) VerificationResult.Valid
        else VerificationResult.Invalid(violations)
    }

    /**
     * Full acceptance: contract verification + expected terminal outcome law.
     * PipelineOutcome (RunFinished.outcome) is OBSERVED here, never mutated.
     */
    fun accept(history: List<TypedEvent>, contract: EventContract, scope: Scope = Scope.WHOLE): AcceptanceOutcome =
        when (val r = verify(history, contract, scope)) {
            is VerificationResult.Invalid -> AcceptanceOutcome.FAILED
            VerificationResult.Valid -> AcceptanceOutcome.PASSED
        }

    fun report(history: List<TypedEvent>, contract: EventContract, scope: Scope = Scope.WHOLE): VerificationReport {
        val scoped = scoped(history, scope)
        return VerificationReport(
            contract = contract.name,
            result = verify(scoped, contract),
            observedTerminalOutcome = observedTerminal(scoped),
        )
    }

    fun observedTerminal(history: List<TypedEvent>): PipelineOutcome? =
        history.filter { it.kind == "RunFinished" }
            .lastOrNull()
            ?.let { (it.event as? dev.rubentxu.pipeline.v2.events.RunFinished)?.outcome }
            ?.let { o ->
                PipelineOutcome.entries.firstOrNull { it.name.equals(o, ignoreCase = true) }
            }
}
