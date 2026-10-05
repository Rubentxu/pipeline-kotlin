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
            "USER",
            spec.failureKind,
            "error() sin kind es un fallo de USUARIO por autorizacion deliberada. " +
                "UNKNOWN significa 'este runtime no pudo clasificar el fallo', que es otra " +
                "afirmacion sobre el mundo y la mas peligrosa de equivocarse.",
        )
    }

    @Test
    fun `el default declarado sigue siendo un FailureKind del vocabulario`() {
        val scope = StageScope("vocab")
        scope.error("boom")

        val declared = (scope.steps().single() as StepSpec.Error).failureKind

        assertTrue(
            FailureKind.entries.any { it.name == declared },
            "el default '$declared' no pertenece al vocabulario FailureKind. Un token fuera " +
                "del enum hace fallar el codec al ADMITIR el Step, con lo que el fallo aparece " +
                "antes de ejecutar nada en lugar de al declarar el error.",
        )
    }

    @Test
    fun `el kind declarado por el autor se respeta sin transformacion`() {
        val scope = StageScope("explicit")
        scope.error("tarde", "TIMEOUT")

        val spec = scope.steps().single() as StepSpec.Error

        assertEquals(
            "TIMEOUT",
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
        val defaults = mutableListOf<String>()

        PostStepsScope().also { it.error("x") }.steps
            .filterIsInstance<StepSpec.Error>().single().let { defaults += it.failureKind }

        BranchScope().also { it.error("x") }.steps
            .filterIsInstance<StepSpec.Error>().single().let { defaults += it.failureKind }

        // StageScope inherits StageScopeCore.error; its constructor supplies the stage name
        // and runtime config, so it is the public face of that third declaration site.
        StageScope("stage").also { it.error("x") }.steps()
            .filterIsInstance<StepSpec.Error>().single().let { defaults += it.failureKind }

        assertEquals(
            listOf("USER", "USER", "USER"),
            defaults,
            "los tres declaraciones de error() han divergido en el default del kind: " +
                "$defaults",
        )
    }
}
