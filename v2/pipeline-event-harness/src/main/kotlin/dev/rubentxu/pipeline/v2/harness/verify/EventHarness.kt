package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import dev.rubentxu.pipeline.v2.harness.model.*

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
            when (constraint) {
                is EventConstraint.Exactly -> checkExactly(scoped, constraint, index, violations)
                is EventConstraint.Never -> checkNever(scoped, constraint, index, violations)
                is EventConstraint.Before -> checkBefore(scoped, constraint, index, violations)
                is EventConstraint.TerminalOutcome -> checkTerminal(scoped, constraint, index, violations)
            }
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

    // ---- constraint checks -------------------------------------------------

    private fun matching(history: List<TypedEvent>, s: EventSelector): List<TypedEvent> =
        history.filter { it.kind == s.kind && EventPayloadAccessor.matches(it, s.where) }

    private fun checkExactly(
        history: List<TypedEvent>, c: EventConstraint.Exactly, index: Int,
        out: MutableList<EventViolation>,
    ) {
        val found = matching(history, c.selector)
        if (found.size != c.count) {
            out += EventViolation(
                rule = ViolationRule.EXACTLY_NOT_SATISFIED,
                message = "constraint#$index: expected exactly ${c.count} of ${describe(c.selector)}, found ${found.size}",
                relevantTrace = window(history, found.map { it.sequence }),
                missing = if (found.size < c.count) describe(c.selector) else null,
                unexpected = if (found.size > c.count) describe(c.selector) else null,
            )
        }
    }

    private fun checkNever(
        history: List<TypedEvent>, c: EventConstraint.Never, index: Int,
        out: MutableList<EventViolation>,
    ) {
        val found = matching(history, c.selector)
        if (found.isNotEmpty()) {
            out += EventViolation(
                rule = ViolationRule.NEVER_VIOLATED,
                message = "constraint#$index: forbidden ${describe(c.selector)} occurred ${found.size}x",
                relevantTrace = window(history, found.map { it.sequence }),
                unexpected = describe(c.selector),
            )
        }
    }

    private fun checkBefore(
        history: List<TypedEvent>, c: EventConstraint.Before, index: Int,
        out: MutableList<EventViolation>,
    ) {
        val firsts = matching(history, c.first)
        val seconds = matching(history, c.second)
        if (firsts.isEmpty() || seconds.isEmpty()) {
            out += EventViolation(
                rule = ViolationRule.BEFORE_VIOLATED,
                message = "constraint#$index: missing required events (${describe(c.first)}=${firsts.size}, ${describe(c.second)}=${seconds.size})",
                relevantTrace = window(history, (firsts + seconds).map { it.sequence }),
                missing = when {
                    firsts.isEmpty() -> describe(c.first)
                    seconds.isEmpty() -> describe(c.second)
                    else -> null
                },
            )
            return
        }
        val ok = when (val scope = c.scope) {
            RelationScope.Global -> seconds.minOf { it.sequence } > firsts.minOf { it.sequence }
            RelationScope.SameSubject -> seconds.any { s ->
                firsts.any { f -> f.envelope.subject.canonicalText() == s.envelope.subject.canonicalText() && f.sequence < s.sequence }
            }
            is RelationScope.SameKey -> seconds.any { s ->
                val sk = EventPayloadAccessor.scopeKey(s, scope.key)
                sk != null && firsts.any { f ->
                    EventPayloadAccessor.scopeKey(f, scope.key) == sk && f.sequence < s.sequence
                }
            }
        }
        if (!ok) {
            out += EventViolation(
                rule = ViolationRule.BEFORE_VIOLATED,
                message = "constraint#$index: ${describe(c.first)} must precede ${describe(c.second)} in scope ${c.scope}",
                relevantTrace = window(history, (firsts + seconds).map { it.sequence }),
            )
        }
    }

    private fun checkTerminal(
        history: List<TypedEvent>, c: EventConstraint.TerminalOutcome, index: Int,
        out: MutableList<EventViolation>,
    ) {
        val observed = observedTerminal(history)
        if (observed == null || observed.name != c.expected.name) {
            out += EventViolation(
                rule = ViolationRule.TERMINAL_OUTCOME_MISMATCH,
                message = "constraint#$index: expected terminal outcome ${c.expected}, observed ${observed ?: "none"}",
                relevantTrace = window(history, history.filter { it.kind == "RunFinished" }.map { it.sequence }),
                missing = "RunFinished(outcome=${c.expected})",
            )
        }
    }

    // ---- counterexample window (80/20) -------------------------------------

    /** Violated positions +- small bounded context (2 before, 2 after), capped at 8 lines. */
    private fun window(history: List<TypedEvent>, focus: List<Long>): List<TraceEntry> {
        if (focus.isEmpty()) return emptyList()
        val seqIdx = history.withIndex().associate { (i, e) -> e.sequence to i }
        val positions = focus.mapNotNull { seqIdx[it] }.toSortedSet()
        val selected = sortedSetOf<Int>()
        for (p in positions) {
            for (d in -2..2) {
                val i = p + d
                if (i in history.indices) selected.add(i)
            }
        }
        return selected.take(8).map { i ->
            val e = history[i]
            TraceEntry(e.sequence, e.kind, EventPayloadAccessor.summary(e))
        }
    }

    private fun describe(s: EventSelector): String =
        if (s.where.isEmpty()) s.kind
        else "${s.kind}(${s.where.joinToString(",") { it.shortDesc() }})"

    private fun FieldMatch.shortDesc(): String = when (this) {
        is FieldMatch.CatchBuildResult -> "buildResult=$value"
        is FieldMatch.RetryOutcome -> "outcome=$value"
        is FieldMatch.AttemptNumber -> "attempt=$value"
        is FieldMatch.BranchIndex -> "branch=$value"
        is FieldMatch.Outcome -> "outcome=$value"
        is FieldMatch.MessageContains -> "msg~'$fragment'"
        is FieldMatch.StageIndex -> "stage=$value"
        is FieldMatch.StepIndex -> "step=$value"
    }
}
