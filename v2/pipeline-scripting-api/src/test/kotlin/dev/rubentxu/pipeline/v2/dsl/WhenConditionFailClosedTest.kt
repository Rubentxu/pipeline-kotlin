package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `whenCondition(expression) { }` is accepted DSL surface with NO execution
 * semantics. It builds `WhenCondition(expression)`, binds it to a local
 * variable, and then discards it: the block's steps are appended to the stage
 * unconditionally.
 *
 * Observed on the installed distribution, not inferred:
 *
 * ```kotlin
 * whenCondition("1 == 2") { echo("SHOULD-NOT-RUN") }
 * ```
 *
 * produced `EchoOutputCaptured{content:"SHOULD-NOT-RUN\n"}` and
 * `RunFinished{outcome:"success"}` with exit 0. A condition that is
 * unconditionally false still ran its body.
 *
 * This is the worst shape of the defect: a script declares conditional logic,
 * the engine ignores it, and the run reports success. Nothing downstream
 * inspects the expression — there is no conditional `StepSpec` subtype at all,
 * so the IR has nowhere to carry the predicate.
 *
 * The false claim this test retires: `docs/v2/07-uat/WU_F3_DSL_AUDIT_RECEIPT.md`
 * records `whenCondition` as "UNSUPPORTED, fail-closed at compile", citing
 * `CliNonCanonicalInMemoryExitsTwoTest` as evidence. That test exercises
 * `ansiColor`, not `whenCondition`, and `whenCondition` compiles down to plain
 * `echo`, which IS canonical, so the bridge never rejects it. The receipt
 * documents a fail-closed that does not happen.
 *
 * Same law as `PostDslFailClosedTest` and `RetryConditionsFailClosedTest`:
 * declared-but-unimplemented is a lie, so it must be rejected at construction
 * rather than silently executed.
 */
class WhenConditionFailClosedTest {

    @Test
    fun `whenCondition is rejected with a diagnostic naming the dropped expression`() {
        val ex = assertThrows<IllegalArgumentException> {
            StageScope("build").whenCondition("env.BRANCH == 'main'") { echo("x") }
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("whenCondition") && msg.contains("env.BRANCH == 'main'") && msg.contains("not supported"),
            "diagnostic must name whenCondition, echo the offending expression and state " +
                "non-support, got: $msg",
        )
    }

    @Test
    fun `a blank expression is rejected too rather than treated as always-true`() {
        assertThrows<IllegalArgumentException> {
            StageScope("build").whenCondition("   ") { echo("x") }
        }
    }

    @Test
    fun `an empty expression is rejected rather than becoming an unconditional block`() {
        assertThrows<IllegalArgumentException> {
            StageScope("build").whenCondition("") { echo("x") }
        }
    }

    @Test
    fun `rejection happens before the body runs so no step is silently appended`() {
        val scope = StageScope("build")
        assertThrows<IllegalArgumentException> {
            scope.whenCondition("false") { echo("must not appear") }
        }
        assertTrue(
            scope.steps().isEmpty(),
            "a rejected whenCondition must not append its body, got ${scope.steps()}",
        )
    }

    @Test
    fun `the StepSpec hierarchy has no conditional subtype to carry the predicate`() {
        // Pins WHY this is unfixable by wiring alone: with no conditional
        // StepSpec and no IR field for the expression, the predicate has
        // nowhere to travel. If a conditional subtype is ever added, this
        // assertion is the one that must be revisited together with the guard.
        val names = StepSpec::class.java.permittedSubclasses.map { it.simpleName }.toSet()
        assertTrue(
            names.none { it.contains("When", ignoreCase = true) || it.contains("Condition", ignoreCase = true) },
            "a conditional StepSpec appeared; whenCondition can then be implemented instead of rejected, " +
                "and this guard plus the audit receipt must change. Found: $names",
        )
    }
}
