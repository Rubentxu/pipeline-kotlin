package dev.rubentxu.pipeline.v2.domain.directive

/**
 * S2-C — gate composition: the PURE decision layer over an already-admitted
 * set of gate directives.
 *
 * The stage's admitted gates (a pure [StageDirectivePlanner] outcome) carry,
 * per directive, the policy the DEFINITION declared. Decoding each gate's
 * arguments is the interpreter's job (injected decoder, typed failure);
 * composition — how several gates become ONE predicate — is a pure domain
 * decision and lives here. No clock, no filesystem, no events.
 *
 * Composition law (declarative, not discretionary):
 * - Declaration order is composition order.
 * - Several gates compose a single [WhenPredicate.AllOf]; one gate composes to
 *   itself; no gates compose to [GateCompositionDecision.Empty] (the neutral
 *   element: the stage runs unconditionally).
 * - The SAME key declared twice is [GateCompositionDecision.Conflicting]: an
 *   author wrote `when` twice and must be told, silently ANDing the two
 *   declarations would hide one of them.
 * - Identical predicates under DIFFERENT keys are NOT a conflict: AND with
 *   itself is idempotent, so there is nothing to hide.
 */
object GateCompositionPlanner {

    /** One gate directive already decoded by the interpreter's injected decoder. */
    data class DeclaredGate(
        val key: DirectiveKey,
        val predicate: WhenPredicate,
    )

    /**
     * Compose [gates] (in declaration order) into the stage's single gate
     * predicate, or report a typed conflict.
     */
    fun compose(gates: List<DeclaredGate>): GateCompositionDecision {
        val duplicate = gates.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            return GateCompositionDecision.Conflicting(
                duplicate.key,
                "directive '${duplicate.key.value}' is declared ${duplicate.value.size} times on the " +
                    "same stage; a gate key may be declared at most once — merge the conditions " +
                    "into a single declaration (e.g. whenAll { ... })",
            )
        }

        return when {
            gates.isEmpty() -> GateCompositionDecision.Empty
            gates.size == 1 -> GateCompositionDecision.Composite(gates.single().predicate)
            else -> GateCompositionDecision.Composite(WhenPredicate.AllOf(gates.map { it.predicate }))
        }
    }
}

/**
 * The typed outcome of composing one stage's gates. A coordinator reads this
 * value; it MUST NOT re-derive composition itself.
 */
sealed interface GateCompositionDecision {
    /** The single predicate the stage's gates compose to. */
    data class Composite(val predicate: WhenPredicate) : GateCompositionDecision

    /** No gates declared: the neutral element; the stage runs unconditionally. */
    data object Empty : GateCompositionDecision

    /**
     * The declaration conflicts (a key declared twice). Fail-closed: the
     * stage never starts, and [reason] names the offending key.
     */
    data class Conflicting(
        val key: DirectiveKey,
        val reason: String,
    ) : GateCompositionDecision
}
