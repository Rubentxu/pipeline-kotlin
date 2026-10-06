package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.FailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P3-E E4 — the failure kind an un-annotated `error()` declares.
 *
 * ## Why this is a test and not a comment
 *
 * The value in question was `"UNKNOWN"` since `core.error` first shipped, and no test
 * anywhere asserted it. A silent default is the easiest thing in a codebase to change by
 * accident and the hardest thing to notice: nothing fails when it moves, because the value
 * it moved to was equally valid as a token. The `decodeEvent` miss earlier in this same
 * block had the same shape — a place where a behaviour existed that nothing was watching.
 *
 * ## What `USER` means, and why not `UNKNOWN`
 *
 * `error("msg")` is authored. Somebody wrote it, deliberately, in a pipeline, with a
 * message, to stop the run. That is a user-level failure and nothing else. `UNKNOWN` is
 * what a decoder returns for a token it cannot classify — "this runtime does not know" —
 * which is a different claim about the world, and the more dangerous one to get wrong,
 * because a consumer reading `UNKNOWN` is being told the run has no attributable cause.
 *
 * That distinction is only worth anything if it survives into the durable stream, which is
 * where the observation API of S5.4 will read it from.
 *
 * `StepSpec.Error.failureKind` stays a [String] on purpose: `pipeline-scripting-api` is
 * published ABI and typing it against [FailureKind] is the E6 migration governed by E5.
 * This test pins vocabulary membership now, so the later migration has a floor to fall on.
 */
class ErrorStepDefaultFailureKindTest {

    @Test
    fun `un error sin kind declarado es USER y no UNKNOWN`() {
        val scope = StageScope("default-kind")
        scope.error("boom")

        val spec = scope.steps().single() as StepSpec.Error

        assertEquals(
            FailureKind.USER,
            spec.failureKind,
            "error() sin kind es un fallo de USUARIO por autorizacion deliberada. " +
                "UNKNOWN significa 'este runtime no pudo clasificar el fallo', que es otra " +
                "afirmacion sobre el mundo y la mas peligrosa de equivocarse.",
        )
    }

    /**
 * REMOVED in E6, deliberately, and the removal is the evidence.
 *
 * This used to be:
 *
 * ```
 * val declared = (scope.steps().single() as StepSpec.Error).failureKind
 * assertTrue(FailureKind.entries.any { it.name == declared })
 * ```
 *
 * It guarded a real defect: `failureKind` was a `String`, so a token outside the vocabulary
 * could reach `StepSpec.Error` and only be caught by the decoder at Step admission — a failure
 * discovered mid-run about a decision the author had already made. The test could only assert
 * the value happened to be in the vocabulary; it could not stop an author writing `"USR"`.
 *
 * E6 typed the field as [FailureKind], so the property is now guaranteed by the compiler. The
 * assertion became vacuous: `FailureKind.entries.any { it.name == <FailureKind> }` is a question
 * with no failure mode. Rewriting it to assert the same thing against a typed value would be
 * theatre — a test whose verdict cannot change.
 *
 * So it is gone rather than re-pointed. What replaced it is not a test in this file: the
 * surviving risk after the migration is no longer vocabulary membership, it is that the IR
 * projection stops emitting `failureKind.name` and moves the wire. That is pinned where the
 * projection lives, in `ErrorFailureKindWireCompatibilityTest`.
 */

    @Test
    fun `el kind declarado por el autor se respeta sin transformacion`() {
        val scope = StageScope("explicit")
        scope.error("tarde", FailureKind.TIMEOUT)

        val spec = scope.steps().single() as StepSpec.Error

        assertEquals(
            FailureKind.TIMEOUT,
            spec.failureKind,
            "un kind explicito viaja tal cual: el Step lo proyecta al stream y el autor debe " +
                "poder distinguir su fallo de uno clasificado por el runtime.",
        )
    }

    @Test
    fun `los tres puntos de entrada del DSL declaran el mismo default`() {
        // `error()` is declared three times over three receivers — PostStepsScope,
        // BranchScope and StageScopeCore — and three copies of a default are three places to
        // forget. This test exists so that moving one without the others is a RED rather than
        // a divergence nobody reads.
        val defaults = mutableListOf<FailureKind>()

        PostStepsScope().also { it.error("x") }.steps
            .filterIsInstance<StepSpec.Error>().single().let { defaults += it.failureKind }

        BranchScope().also { it.error("x") }.steps
            .filterIsInstance<StepSpec.Error>().single().let { defaults += it.failureKind }

        // StageScope inherits StageScopeCore.error; its constructor supplies the stage name
        // and runtime config, so it is the public face of that third declaration site.
        StageScope("stage").also { it.error("x") }.steps()
            .filterIsInstance<StepSpec.Error>().single().let { defaults += it.failureKind }

        assertEquals(
            listOf(FailureKind.USER, FailureKind.USER, FailureKind.USER),
            defaults,
            "los tres declaraciones de error() han divergido en el default del kind: " +
                "$defaults",
        )
    }
}
