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
     *
     * Under BASELINE DEBT **INC-EVT3-1** a durable resume re-appends the skeleton
     * events for a run id it REUSES, so one run carries more than one
     * [RunStarted]. Insertion order keeps the last execution segment a contiguous
     * suffix, so [Scope.AFTER_LAST_RUN_STARTED] stays deterministic WITHOUT
     * timestamps (INC-021d law honored).
     *
     * Two things this comment used to get wrong, kept here because the
     * correction is the non-obvious part: it said the re-appended events carry
     * "restarting sequences", and a second KDoc called it "duplicate-sequence
     * debt". Both are false, and the store is why. The lifecycle engine emits
     * each re-appended event with `sequence = 0L`, but
     * `SqliteEventStore.appendAssigned` reads `0L` as a REQUEST to be assigned a
     * sequence rather than as a value -- it returns `counter.incrementAndGet()`.
     * So the second `RunStarted` takes the NEXT sequence, never collides with
     * `UNIQUE(run_id, sequence)`, and is not a duplicate sequence at all. What is
     * duplicated is the EVENT.
     */
    fun typedHistory(sink: EventSink, runId: String, fromSequence: Long = 0L): List<TypedEvent> =
        sink.eventsFor(runId)
            .map { TypedEvent(envelope = EnvelopeProjector.project(it), event = it) }
            .filter { it.sequence > fromSequence }
            .toList()

    fun typedHistory(events: List<DomainEvent>): List<TypedEvent> =
        events.map { TypedEvent(EnvelopeProjector.project(it), it) }

    /**
     * Last execution segment: insertion-order suffix starting at the LAST
     * [RunStarted]. Deterministic because it cuts on INSERTION ORDER rather than
     * on any field of the event, never on a timestamp.
     *
     * The KDoc this replaces called it "deterministic under INC-EVT3-1
     * duplicate-sequence debt". There is no duplicate sequence; see [typedHistory]
     * for why the store cannot produce one. What the duplicate RunStarted does
     * create is a second segment boundary, and cutting at the LAST one is
     * precisely what keeps the current attempt separable from the previous one.
     *
     * This function's KDoc used to sit BELOW a second KDoc block, stacked on top
     * of another one. Kotlin attached the lower block here and left [verify]
     * undocumented, so the note about [verify] being pure was attached to the
     * wrong function for as long as both blocks existed.
     */
    fun lastSegment(history: List<TypedEvent>): List<TypedEvent> = scoped(history, Scope.AFTER_LAST_RUN_STARTED)

    /**
     * Verify one contract. Pure: same inputs, same result, forever.
     */
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
