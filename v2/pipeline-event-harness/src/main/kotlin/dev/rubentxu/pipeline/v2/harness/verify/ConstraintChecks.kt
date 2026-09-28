package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.harness.model.EventConstraint
import dev.rubentxu.pipeline.v2.harness.model.EventSelector
import dev.rubentxu.pipeline.v2.harness.model.EventViolation
import dev.rubentxu.pipeline.v2.harness.model.FieldMatch
import dev.rubentxu.pipeline.v2.harness.model.RelationScope
import dev.rubentxu.pipeline.v2.harness.model.TraceEntry
import dev.rubentxu.pipeline.v2.harness.model.ViolationRule

/**
 * The four constraint checkers of [EventHarness], with the selector matching
 * and selector description they share.
 *
 * Extracted because [EventHarness] is an `object`, so detekt's function budget
 * for it is 11 (allowedFunctionsPerObject) rather than the 25 that applies to
 * classes. Only [EventHarness] calls this; every entry point here was private.
 */
internal object ConstraintChecks {

    fun check(
        scoped: List<TypedEvent>,
        constraint: EventConstraint,
        index: Int,
        out: MutableList<EventViolation>,
    ) {
        when (constraint) {
            is EventConstraint.Exactly -> checkExactly(scoped, constraint, index, out)
            is EventConstraint.Never -> checkNever(scoped, constraint, index, out)
            is EventConstraint.Before -> checkBefore(scoped, constraint, index, out)
            is EventConstraint.TerminalOutcome -> checkTerminal(scoped, constraint, index, out)
        }
    }

    fun matching(history: List<TypedEvent>, s: EventSelector): List<TypedEvent> =
        history.filter { it.kind == s.kind && EventPayloadAccessor.matches(it, s.where) }

    fun describe(s: EventSelector): String =
        if (s.where.isEmpty()) s.kind
        else "${s.kind}(${s.where.joinToString(",") { it.shortDesc() }})"

    fun FieldMatch.shortDesc(): String = when (this) {
        is FieldMatch.CatchBuildResult -> "buildResult=$value"
        is FieldMatch.RetryOutcome -> "outcome=$value"
        is FieldMatch.AttemptNumber -> "attempt=$value"
        is FieldMatch.BranchIndex -> "branch=$value"
        is FieldMatch.Outcome -> "outcome=$value"
        is FieldMatch.MessageContains -> "msg~'$fragment'"
        is FieldMatch.StageIndex -> "stage=$value"
        is FieldMatch.StepIndex -> "step=$value"
    }

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
        val observed = EventHarness.observedTerminal(history)
        if (observed == null || observed.name != c.expected.name) {
            out += EventViolation(
                rule = ViolationRule.TERMINAL_OUTCOME_MISMATCH,
                message = "constraint#$index: expected terminal outcome ${c.expected}, observed ${observed ?: "none"}",
                relevantTrace = window(history, history.filter { it.kind == "RunFinished" }.map { it.sequence }),
                missing = "RunFinished(outcome=${c.expected})",
            )
        }
    }
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
}
