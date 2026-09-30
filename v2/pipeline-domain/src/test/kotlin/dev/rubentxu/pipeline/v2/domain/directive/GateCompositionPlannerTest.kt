package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S2-C — gate composition planning: the PURE decision over an already-admitted
 * set of gate directives.
 *
 * RED-first expectations:
 *
 * - No gates -> Empty (the neutral element; the stage runs unconditionally).
 * - One gate -> Composite of that gate's predicate, unchanged.
 * - Several gates, distinct keys -> Composite AllOf in DECLARATION order.
 * - The SAME key declared twice -> Conflicting (an author error, never a
 *   discretionary AND).
 * - Identical predicates under DIFFERENT keys -> still Composite AllOf (AND
 *   with itself is idempotent; the domain invents no extra policy).
 * - The planner works on (key, predicate) pairs; it never decodes payloads.
 */
class GateCompositionPlannerTest {

    private fun gate(key: String, predicate: WhenPredicate): GateCompositionPlanner.DeclaredGate =
        GateCompositionPlanner.DeclaredGate(DirectiveKey(key), predicate)

    @Test
    fun `no gates is the explicit Empty composition`() {
        val decision = GateCompositionPlanner.compose(emptyList())

        assertEquals(GateCompositionDecision.Empty, decision)
    }

    @Test
    fun `one gate composes to its own predicate`() {
        val predicate = WhenPredicate.VariableEquals("ENV", "prod")
        val decision = GateCompositionPlanner.compose(listOf(gate("core.when", predicate)))

        assertEquals(GateCompositionDecision.Composite(predicate), decision)
    }

    @Test
    fun `two gates compose an AllOf in declaration order`() {
        val first = WhenPredicate.VariableEquals("ENV", "prod")
        val second = WhenPredicate.VariablePresent("APPROVAL")
        val decision = GateCompositionPlanner.compose(
            listOf(gate("core.when", first), gate("acme.lock", second)),
        )

        assertEquals(GateCompositionDecision.Composite(WhenPredicate.AllOf(listOf(first, second))), decision)
    }

    @Test
    fun `a duplicate key is a conflict, never a discretionary AND`() {
        val decision = GateCompositionPlanner.compose(
            listOf(
                gate("core.when", WhenPredicate.VariableEquals("ENV", "prod")),
                gate("core.when", WhenPredicate.VariableEquals("ENV", "dev")),
            ),
        )

        assertTrue(decision is GateCompositionDecision.Conflicting, "got $decision")
        decision as GateCompositionDecision.Conflicting
        assertEquals("core.when", decision.key.value)
        assertTrue("core.when" in decision.reason, "reason must name the key: ${decision.reason}")
    }

    @Test
    fun `identical predicates under different keys are not a conflict`() {
        val same = WhenPredicate.VariableEquals("ENV", "prod")
        val decision = GateCompositionPlanner.compose(
            listOf(gate("core.when", same), gate("acme.when", same)),
        )

        assertEquals(GateCompositionDecision.Composite(WhenPredicate.AllOf(listOf(same, same))), decision)
    }

    @Test
    fun `composition is deterministic across repeated invocations`() {
        val gates = listOf(
            gate("core.when", WhenPredicate.VariableEquals("ENV", "prod")),
            gate("acme.lock", WhenPredicate.VariablePresent("APPROVAL")),
        )
        assertEquals(GateCompositionPlanner.compose(gates), GateCompositionPlanner.compose(gates))
    }
}
