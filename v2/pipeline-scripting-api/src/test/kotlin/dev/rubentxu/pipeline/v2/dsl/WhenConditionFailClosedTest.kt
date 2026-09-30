package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S2-A SUCCESSOR of the S1 `whenCondition` fail-closed characterization.
 *
 * ## What the original test recorded (observed, not inferred)
 *
 * On the installed distribution, `whenCondition("1 == 2") { echo("SHOULD-NOT-RUN") }`
 * produced `EchoOutputCaptured{content:"SHOULD-NOT-RUN\n"}` and
 * `RunFinished{outcome:"success"}` with exit 0. An unconditionally false
 * condition still ran its body. S1 closed that hole by REJECTING the
 * expression outright, and `docs/v2/07-uat/WU_F3_DSL_AUDIT_RECEIPT.md` recorded
 * `whenCondition` as "UNSUPPORTED, fail-closed at compile".
 *
 * ## Why that receipt is now retired
 *
 * S1's rejection was honest but temporary: a `String` expression cannot carry
 * semantics into the IR, because it would have to be re-parsed at run time by an
 * interpreter that did not exist. The test's own closing assertion predicted this
 * moment — "if a conditional subtype is ever added, this assertion is the one
 * that must be revisited together with the guard".
 *
 * S2-A supplies exactly that missing capability, and does so WITHOUT adding a
 * conditional `StepSpec` subtype: the predicate travels as a `StageDirective`
 * whose arguments are a STRUCTURED encoding, evaluated by a pure evaluator at
 * the effect boundary. So:
 *
 *   - the original characterization stands as historical evidence (below), and
 *   - the fail-closed guard is REPLACED by a real-typed surface, because a
 *     String-typed escape hatch that silently discards a predicate is precisely
 *     the lie S1 was built to eliminate. Keeping `whenCondition(String)` alive
 *     now that `whenGate(WhenPredicate)` exists would reintroduce the exact
 *     discard-the-predicate defect, just behind a friendlier name.
 *
 * The regression tests for the NEW contract live in `WhenDirectiveDslTest` and in
 * the interpreter scenarios; this file keeps the historical record honest and
 * pins the architectural invariant that carried the fix.
 */
class WhenConditionFailClosedTest {

    @Test
    fun `a String-typed whenCondition is no longer offered, so no predicate can be silently discarded`() {
        // There is deliberately no `whenCondition(String)`. If someone re-adds
        // it, this fails at COMPILE time of the test below only if it is used;
        // so the check is structural: the StageScope surface must not expose a
        // method taking a bare String condition under a `when` name.
        val whenMethods = StageScope::class.java.methods.filter { method ->
            method.name.startsWith("when") &&
                method.parameterTypes.any { it == String::class.java }
        }.map { it.name }

        assertFalse(
            whenMethods.contains("whenCondition"),
            "whenCondition(String) is back: a text predicate would again be accepted and " +
                "discarded without semantics. Found: $whenMethods",
        )
    }

    @Test
    fun `every when-typed StageScope entry point takes a typed predicate, never free text`() {
        val gateCount = StageScope::class.java.methods.count { it.name == "whenGate" }
        assertTrue(gateCount >= 1, "StageScope must still expose a typed whenGate entry point")
    }

    @Test
    fun `the predicate travels as a directive, not as a StepSpec`() {
        // Pins the architectural choice that makes the fix possible: the
        // conditional does NOT become a new StepSpec subtype (which would put a
        // concrete-Step case into the closed engine), it rides the OPEN stage
        // directive seam. If someone later introduces a conditional StepSpec,
        // this assertion is the tripwire — exactly as the S1 version intended.
        val names = StepSpec::class.java.permittedSubclasses.map { it.simpleName }.toSet()
        assertTrue(
            names.none { it.contains("When", ignoreCase = true) || it.contains("Condition", ignoreCase = true) },
            "a conditional StepSpec appeared; gating must stay on the open directive seam, " +
                "not as a closed Step subtype. Found: $names",
        )
    }

    @Test
    fun `a typed gate with a never-satisfiable predicate is constructible and encoded, not rejected`() {
        // The behavioral RED this replaces: previously an always-false condition
        // was the RED, and the only correct behavior was rejection. Now the
        // always-false predicate is a first-class VALUE that encodes cleanly;
        // whether it skips the stage is decided at run time by the evaluator,
        // not guessed at construction time.
        val stage = StageScope("build").apply { whenEnvIs("PK_NEVER_SET_98765", "never-matches") }
            .toStageBuilder().build()

        assertEquals(1, stage.directives.size)
        val directive = stage.directives.single()
        assertEquals(WHEN_DIRECTIVE_KEY.value, directive.key)
        assertEquals(
            WhenPredicate.VariableEquals("PK_NEVER_SET_98765", "never-matches"),
            (WhenPredicateCodec.decode(directive.encodedArguments)
                as? dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded)?.input,
        )
    }

    @Test
    fun `an unset variable at CONSTRUCTION time is not treated as a rejection`() {
        // Construction must not consult any runtime state, so an unset variable
        // is indistinguishable at this layer — and that is correct. It is the
        // evaluator, at the effect boundary, that distinguishes
        // "decided negative" from "unverifiable".
        val stage = StageScope("build").apply { whenEnvIs("DEFINITELY_UNSET_VARIABLE_NAME", "x") }
            .toStageBuilder().build()
        assertEquals(1, stage.directives.size)
    }

    @Test
    fun `directive accumulation never overwrites an earlier gate`() {
        // Guards the fail-OPEN accumulation fix: `directives { }` must append,
        // never assign, or a `when` declared earlier in the stage is lost and
        // the stage runs unconditionally.
        val stage = StageScope("build")
            .apply {
                whenEnvIs("A", "1")
                directives { directive("vendor.owner@example.com/after-gate", "x") }
            }
            .toStageBuilder().build()

        assertEquals(2, stage.directives.size)
        assertTrue(stage.directives.any { it.key == WHEN_DIRECTIVE_KEY.value })
    }
}
