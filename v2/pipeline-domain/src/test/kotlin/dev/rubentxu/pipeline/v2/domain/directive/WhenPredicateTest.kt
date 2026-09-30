package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S2-A — the first real predicate on the directive kernel.
 *
 * The point of these tests is not that boolean expressions work. It is that the
 * decision is a PURE function of an explicit context value, that a context it
 * cannot resolve is a typed refusal rather than a silent false, and that the
 * closed ADT has no state that can lie.
 */
class WhenPredicateTest {

    /** Builds a context from alternating key/value arguments. */
    private fun ctx(vararg pairs: Pair<String, String>) =
        GateContext(pairs.toMap())

    /** Convenience for the common "one variable" call site. */
    private fun ctx1(name: String, value: String) = GateContext(mapOf(name to value))

    /** Convenience for the common "two variables" call site. */
    private fun ctx2(
        aName: String, aValue: String, bName: String, bValue: String,
    ) = GateContext(mapOf(aName to aValue, bName to bValue))

    @Test
    fun `an equal variable satisfies the predicate`() {
        val subject = WhenPredicate.VariableEquals("DEPLOY_ENV", "prod")
        assertEquals(
            GateVerdict.Satisfied,
            WhenPredicateEvaluator.evaluate(subject, ctx1("DEPLOY_ENV", "prod")),
        )
    }

    @Test
    fun `a different variable does not satisfy the predicate`() {
        val subject = WhenPredicate.VariableEquals("DEPLOY_ENV", "prod")
        val verdict = WhenPredicateEvaluator.evaluate(subject, ctx1("DEPLOY_ENV", "dev"))
        assertTrue(verdict is GateVerdict.NotSatisfied, "expected NotSatisfied, got $verdict")
    }

    @Test
    fun `a variable no source can resolve is UNVERIFIABLE, never a silent false`() {
        // This is the honesty rule that matters, and it is NOT the same as an
        // unset variable. A local-first runtime can genuinely have no source
        // for CHANGE_ID; that is "I could not tell", while an empty string is
        // "the answer is no". Collapsing them would let a gate on an
        // unavailable fact quietly skip a stage and report success.
        val subject = WhenPredicate.VariableEquals("CHANGE_ID", "42")
        assertEquals(
            GateVerdict.Unverifiable("no source can resolve 'CHANGE_ID'"),
            WhenPredicateEvaluator.evaluate(
                subject,
                GateContext(values = emptyMap(), unresolvable = setOf("CHANGE_ID")),
            ),
        )
    }

    @Test
    fun `an unset variable is a decided no, and the reason names it`() {
        // Distinct from Unverifiable on purpose: unset is a real answer, and
        // it is what makes `when { env.X == 'prod' }` behave as users expect.
        val subject = WhenPredicate.VariableEquals("DEPLOY_ENV", "prod")
        assertEquals(
            GateVerdict.NotSatisfied("variable 'DEPLOY_ENV' is not set"),
            WhenPredicateEvaluator.evaluate(subject, ctx1("OTHER", "1")),
        )
    }

    @Test
    fun `an always-true predicate satisfies without consulting the context`() {
        assertEquals(
            GateVerdict.Satisfied,
            WhenPredicateEvaluator.evaluate(WhenPredicate.AlwaysTrue, GateContext.EMPTY),
        )
    }

    @Test
    fun `an always-false predicate never satisfies`() {
        val verdict = WhenPredicateEvaluator.evaluate(WhenPredicate.AlwaysFalse, ctx1("X", "1"))
        assertTrue(verdict is GateVerdict.NotSatisfied, "expected NotSatisfied, got $verdict")
    }

    @Test
    fun `all-of requires every child`() {
        val subject = WhenPredicate.AllOf(
            listOf(
                WhenPredicate.VariableEquals("A", "1"),
                WhenPredicate.VariableEquals("B", "2"),
            )
        )
        assertEquals(
            GateVerdict.Satisfied,
            WhenPredicateEvaluator.evaluate(subject, ctx2("A", "1", "B", "2")),
        )
        assertFalse(
            WhenPredicateEvaluator.evaluate(subject, ctx2("A", "1", "B", "9"))
                is GateVerdict.Satisfied,
        )
    }

    @Test
    fun `any-of requires at least one child`() {
        val subject = WhenPredicate.AnyOf(
            listOf(
                WhenPredicate.VariableEquals("A", "1"),
                WhenPredicate.VariableEquals("B", "2"),
            )
        )
        assertEquals(
            GateVerdict.Satisfied,
            WhenPredicateEvaluator.evaluate(subject, ctx1("B", "2")),
        )
        assertFalse(
            WhenPredicateEvaluator.evaluate(subject, ctx2("A", "0", "B", "0"))
                is GateVerdict.Satisfied,
        )
    }

    @Test
    fun `not inverts satisfaction but preserves unverifiability`() {
        val subject = WhenPredicate.Not(WhenPredicate.VariableEquals("A", "1"))
        assertTrue(
            WhenPredicateEvaluator.evaluate(subject, ctx1("A", "0")) is GateVerdict.Satisfied,
        )
        // Inverting "I cannot tell" must NOT become "yes": a gate that cannot
        // resolve its input must stay unverifiable, or negation becomes a way
        // to accidentally admit a stage that was never evaluated.
        assertEquals(
            GateVerdict.Unverifiable("no source can resolve 'A'"),
            WhenPredicateEvaluator.evaluate(
                subject,
                GateContext(values = emptyMap(), unresolvable = setOf("A")),
            ),
        )
    }

    @Test
    fun `an empty conjunction is satisfied and an empty disjunction is not`() {
        assertEquals(
            GateVerdict.Satisfied,
            WhenPredicateEvaluator.evaluate(WhenPredicate.AllOf(emptyList()), GateContext.EMPTY),
        )
        val empty = WhenPredicateEvaluator.evaluate(
            WhenPredicate.AnyOf(emptyList()), GateContext.EMPTY)
        assertTrue(empty is GateVerdict.NotSatisfied, "expected NotSatisfied, got $empty")
    }

    @Test
    fun `an unresolvable child makes the whole conjunction unverifiable`() {
        // The compound cases must not launder an unknown into a decided no:
        // otherwise `A and B` with an unresolvable B would read as "false",
        // which is indistinguishable from a real negative.
        val subject = WhenPredicate.AllOf(
            listOf(
                WhenPredicate.VariableEquals("A", "1"),
                WhenPredicate.VariableEquals("B", "2"),
            )
        )
        assertEquals(
            GateVerdict.Unverifiable("no source can resolve 'B'"),
            WhenPredicateEvaluator.evaluate(
                subject,
                GateContext(values = mapOf("A" to "1"), unresolvable = setOf("B")),
            ),
        )
    }
}
