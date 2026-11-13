package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The verdict of a gate, in its three values.
 *
 * ## The guarantee under trial
 *
 * That the evaluation of a predicate is a TOTAL function with three answers,
 * and that each answer says something different:
 *
 * ```
 * Satisfied    -> the stage runs
 * NotSatisfied -> the stage does not run, and that is a decided answer
 * Unverifiable -> nobody could decide it, so the gate fails closed
 * ```
 *
 * ## Why the third value matters
 *
 * Because the difference between "the answer is no" and "nobody can tell" is
 * the difference between a pipeline that skips a stage and a pipeline that
 * reports success for work that never ran. The rule is published — a gate that
 * names a fact the runtime cannot resolve has to fail closed — and a rule that
 * no row exercises is a rule that can be broken without anybody noticing.
 *
 * ## What this instrument does not freeze
 *
 * The wording of the reasons, beyond the name of the variable they carry: the
 * reason is what a reader of the run sees, and the row says that it names the
 * variable it is about.
 */
class WhenPredicateEvaluatorTest {

    /**
     * A variable that nobody declares is a decided negative.
     *
     * Not an error and not an unverifiable: the gate knows the name and the
     * name carries no value, so the answer is "no", and the stage does not
     * run.
     */
    @Test
    @DisplayName("a gate that names a variable nobody declares is a decided negative")
    fun aGateThatNamesAVariableNobodyDeclaresIsADecidedNegative() {
        val verdict = WhenPredicateEvaluator.evaluate(
            WhenPredicate.VariableEquals("BRANCH_NAME", "main"),
            GateContext(values = emptyMap()),
        )

        val negative = assertInstanceOf(
            GateVerdict.NotSatisfied::class.java,
            verdict,
            "a variable that nobody declares is a decided negative, not an error",
        )
        assertTrue(
            negative.reason.contains("BRANCH_NAME"),
            "the reason names the variable the gate is about: ${negative.reason}",
        )
        assertTrue(
            negative.reason.contains("not set"),
            "the reason says that the variable carries no value: ${negative.reason}",
        )
    }

    /**
     * A variable that no source can resolve is unverifiable.
     *
     * This is the third value, and it is the one that fails the run closed: a
     * stage that nobody could decide must not be reported as a stage that was
     * decided not to run.
     */
    @Test
    @DisplayName("a gate that names a variable no source can resolve is unverifiable")
    fun aGateThatNamesAVariableNoSourceCanResolveIsUnverifiable() {
        val verdict = WhenPredicateEvaluator.evaluate(
            WhenPredicate.VariableEquals("BRANCH_NAME", "main"),
            GateContext(values = emptyMap(), unresolvable = setOf("BRANCH_NAME")),
        )

        val unverifiable = assertInstanceOf(
            GateVerdict.Unverifiable::class.java,
            verdict,
            "a variable that no source can resolve leaves the gate unverifiable",
        )
        assertTrue(
            unverifiable.reason.contains("BRANCH_NAME"),
            "the reason names the variable the gate could not resolve: ${unverifiable.reason}",
        )
    }

    /**
     * A declared variable that matches satisfies the gate.
     *
     * The other side of the rule: a gate that can be satisfied is satisfied,
     * and the answer is the plain one.
     */
    @Test
    @DisplayName("a declared variable that matches satisfies the gate")
    fun aDeclaredVariableThatMatchesSatisfiesTheGate() {
        val verdict = WhenPredicateEvaluator.evaluate(
            WhenPredicate.VariableEquals("BRANCH_NAME", "main"),
            GateContext(values = mapOf("BRANCH_NAME" to "main")),
        )

        assertEquals(
            GateVerdict.Satisfied,
            verdict,
            "a declared variable that matches satisfies the gate",
        )
    }
}
