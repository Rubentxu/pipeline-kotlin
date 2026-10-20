package dev.rubentxu.pipeline.v2.domain.directive

/**
 * S2-A — the first real directive: a typed `when` predicate.
 *
 * ## Why an ADT and not a String
 *
 * `whenCondition("1 == 2")` was rejected outright because a `String` cannot
 * carry semantics into the IR: the expression would have to be re-parsed at
 * runtime by an interpreter that does not exist, and the only honest outcome
 * was to refuse the call. This file replaces that refusal with a value.
 *
 * The state space here is FINITE, so it is a sealed hierarchy. Each case
 * carries only the payload meaningful for it, and there is no stringly-typed
 * escape hatch: an expression that cannot be one of these cases does not
 * compile. That is the whole point — a predicate that must be interpreted
 * cannot be smuggled in as opaque text.
 *
 * ## The three-valued rule
 *
 * The verdict is deliberately THREE-valued, not boolean:
 *
 * ```
 * Satisfied       -> run the stage
 * NotSatisfied    -> skip the stage (a real, decided negative)
 * Unverifiable    -> fail closed; nobody can prove the predicate
 * ```
 *
 * Collapsing the last two would reintroduce the exact defect this replaces:
 * a gate naming a fact the runtime cannot resolve would read as "false", the
 * stage would silently skip, and the pipeline would report success for work
 * that never ran.
 *
 * This is a pure inner-domain value: no clock, no filesystem, no ambient
 * state, no I/O. [WhenPredicateEvaluator] is a total function over it.
 */

/**
 * A predicate a stage gate can evaluate.
 *
 * Closed by structure. The engine matches these cases; it never inspects a
 * predicate name, and it cannot grow a new case without changing every
 * interpreter, which is the intent.
 */
sealed interface WhenPredicate {

    /** Unconditionally satisfied. Useful as a neutral element for `AnyOf`. */
    data object AlwaysTrue : WhenPredicate

    /** Unconditionally not satisfied. */
    data object AlwaysFalse : WhenPredicate

    /**
     * The named variable equals [expected].
     *
     * An unset variable is a decided negative, NOT an error: that is what
     * makes `when { env.X == 'prod' }` behave the way users expect when X is
     * absent. Only a variable that no source can resolve at all is
     * unverifiable, and that distinction is carried by [GateContext].
     */
    data class VariableEquals(val name: String, val expected: String) : WhenPredicate {
        init {
            require(name.isNotBlank()) { "WhenPredicate.VariableEquals.name must not be blank" }
        }
    }

    /** The named variable is present and non-empty. */
    data class VariablePresent(val name: String) : WhenPredicate {
        init {
            require(name.isNotBlank()) { "WhenPredicate.VariablePresent.name must not be blank" }
        }
    }

    /** Every child must be satisfied. Empty conjunction is satisfied. */
    data class AllOf(val children: List<WhenPredicate>) : WhenPredicate

    /** At least one child must be satisfied. Empty disjunction is not satisfied. */
    data class AnyOf(val children: List<WhenPredicate>) : WhenPredicate

    /**
     * Negation.
     *
     * Note it inverts satisfaction but PRESERVES unverifiability. Negating an
     * unknown into "satisfied" would make `not` a way to accidentally admit a
     * stage that was never evaluated.
     */
    data class Not(val child: WhenPredicate) : WhenPredicate
}

/**
 * The facts a gate may be evaluated against.
 *
 * Immutable and explicit: the evaluator receives this value rather than
 * discovering ambient state, which is what makes the decision testable without
 * constructing a coordinator, a process, or a filesystem.
 *
 * [unresolvable] names variables that no configured source can supply. It is a
 * separate field rather than a sentinel inside [values] because "set to
 * nothing" and "no source exists" are different facts with different operator
 * responses, and collapsing them is the defect this design exists to prevent.
 */
data class GateContext(
    val values: Map<String, String> = emptyMap(),
    val unresolvable: Set<String> = emptySet(),
) {
    init {
        require(values.keys.none(String::isBlank)) { "GateContext value keys must not be blank" }
        require(unresolvable.none(String::isBlank)) {
            "GateContext unresolvable names must not be blank"
        }
    }

    /** Resolve one variable into a three-valued answer, or null when unknown. */
    fun lookup(name: String): Resolution = when {
        name in unresolvable -> Resolution.Unresolvable
        values.containsKey(name) -> Resolution.Present(values.getValue(name))
        else -> Resolution.Unset
    }

    sealed interface Resolution {
        data class Present(val value: String) : Resolution
        data object Unset : Resolution
        data object Unresolvable : Resolution
    }

    companion object {
        val EMPTY = GateContext()
    }
}

/**
 * The typed outcome of evaluating a [WhenPredicate].
 *
 * [reason] is carried on the negative cases because "why did my stage not
 * run" is the first question an operator asks, and reconstructing it from the
 * program text is exactly the work this type exists to save.
 */
sealed interface GateVerdict {
    data object Satisfied : GateVerdict

    data class NotSatisfied(val reason: String) : GateVerdict

    data class Unverifiable(val reason: String) : GateVerdict
}

/**
 * Total, pure evaluation of a [WhenPredicate] against a [GateContext].
 *
 * Exhaustive `when` over the closed ADT: adding a predicate case forces this
 * function to handle it, so a new case cannot ship uninterpreted. There is no
 * `else` branch hiding an unhandled situation.
 */
object WhenPredicateEvaluator {

    fun evaluate(predicate: WhenPredicate, context: GateContext): GateVerdict =
        when (predicate) {
            WhenPredicate.AlwaysTrue -> GateVerdict.Satisfied
            WhenPredicate.AlwaysFalse ->
                GateVerdict.NotSatisfied("predicate is the constant false")

            is WhenPredicate.VariableEquals -> evaluateEquals(predicate, context)
            is WhenPredicate.VariablePresent -> evaluatePresent(predicate, context)

            is WhenPredicate.AllOf -> evaluateAllOf(predicate, context)
            is WhenPredicate.AnyOf -> evaluateAnyOf(predicate, context)

            is WhenPredicate.Not -> when (val inner = evaluate(predicate.child, context)) {
                is GateVerdict.Satisfied ->
                    GateVerdict.NotSatisfied("negated predicate was satisfied")
                is GateVerdict.NotSatisfied -> GateVerdict.Satisfied
                is GateVerdict.Unverifiable -> inner
            }
        }

    private fun evaluateEquals(
        predicate: WhenPredicate.VariableEquals,
        context: GateContext,
    ): GateVerdict = when (val resolution = context.lookup(predicate.name)) {
        is GateContext.Resolution.Unresolvable ->
            GateVerdict.Unverifiable("no source can resolve '${predicate.name}'")

        GateContext.Resolution.Unset ->
            GateVerdict.NotSatisfied("variable '${predicate.name}' is not set")

        is GateContext.Resolution.Present ->
            if (resolution.value == predicate.expected) {
                GateVerdict.Satisfied
            } else {
                GateVerdict.NotSatisfied(
                    "variable '${predicate.name}' is '${resolution.value}', " +
                        "not '${predicate.expected}'",
                )
            }
    }

    private fun evaluatePresent(
        predicate: WhenPredicate.VariablePresent,
        context: GateContext,
    ): GateVerdict = when (val resolution = context.lookup(predicate.name)) {
        is GateContext.Resolution.Unresolvable ->
            GateVerdict.Unverifiable("no source can resolve '${predicate.name}'")

        GateContext.Resolution.Unset ->
            GateVerdict.NotSatisfied("variable '${predicate.name}' is not set")

        is GateContext.Resolution.Present ->
            if (resolution.value.isNotEmpty()) {
                GateVerdict.Satisfied
            } else {
                GateVerdict.NotSatisfied("variable '${predicate.name}' is empty")
            }
    }

    private fun evaluateAllOf(
        predicate: WhenPredicate.AllOf,
        context: GateContext,
    ): GateVerdict {
        // Short-circuit on the first decided negative, but an unverifiable
        // child must win over a later negative: "I cannot tell" is stronger
        // than "no", because only the former demands a human.
        var negative: GateVerdict.NotSatisfied? = null
        for (child in predicate.children) {
            when (val verdict = evaluate(child, context)) {
                GateVerdict.Satisfied -> Unit
                is GateVerdict.Unverifiable -> return verdict
                is GateVerdict.NotSatisfied -> if (negative == null) negative = verdict
            }
        }
        return negative ?: GateVerdict.Satisfied
    }

    private fun evaluateAnyOf(
        predicate: WhenPredicate.AnyOf,
        context: GateContext,
    ): GateVerdict {
        var unverifiable: GateVerdict.Unverifiable? = null
        for (child in predicate.children) {
            when (val verdict = evaluate(child, context)) {
                GateVerdict.Satisfied -> return verdict
                is GateVerdict.Unverifiable -> if (unverifiable == null) unverifiable = verdict
                is GateVerdict.NotSatisfied -> Unit
            }
        }
        // No child satisfied. If any child was unverifiable we cannot claim a
        // negative either, so the whole disjunction stays unverifiable.
        return unverifiable ?: GateVerdict.NotSatisfied("no alternative was satisfied")
    }
}
