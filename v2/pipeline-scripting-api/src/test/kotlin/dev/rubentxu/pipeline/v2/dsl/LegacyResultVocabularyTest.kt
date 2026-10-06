package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.FailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P3-E E6 — the STABLE authoring boundary keeps its spelling and loses its permissiveness.
 *
 * ## The claim under test
 *
 * `error(message, failureKind)` and `catchError(buildResult?, stageResult?, message?)` are
 * STABLE in `DSL_SURFACE_MANIFEST.md`. So they keep accepting the tokens the manifest
 * documents, and they stop accepting anything else.
 *
 * Before this change a typo compiled, ran, and was read by an `else` arm as `UNSTABLE` — the
 * reading that suppresses the very failure the scope was installed to catch. The breakage was
 * found by ten UAT scenarios that legitimately wrote `catchError(buildResult = "FAILURE")`
 * and stopped compiling.
 *
 * These are pure-contract tests: they construct the DSL directly and never spawn a process.
 * The behavioural half — that a refused script never reaches `RunStarted` — lives in
 * `LegacyResultBridgeRefusalTest`, which crosses the scripting host.
 */
@DisplayName("P3-E E6 — the legacy String authoring boundary validates instead of defaulting")
class LegacyResultVocabularyTest {

    private fun scope() = StageScope("Build")

    // ---- the STABLE spelling still compiles and still works -------------------------

    @Test
    fun `the legacy catchError spelling produces the same typed state as the typed one`() {
        val legacy = scope().apply { catchError(buildResult = "FAILURE", stageResult = "UNSTABLE") { sh("exit 1") } }
        val typed = scope().apply {
            catchError(CatchErrorBuildResult.Failure, CatchErrorBuildResult.Unstable) { sh("exit 1") }
        }

        val legacySpec = legacy.steps().single() as StepSpec.CatchError
        val typedSpec = typed.steps().single() as StepSpec.CatchError

        assertEquals(typedSpec, legacySpec, "both spellings must build the SAME typed state")
        assertEquals(CatchErrorBuildResult.Failure, legacySpec.buildResult)
        assertEquals(CatchErrorBuildResult.Unstable, legacySpec.stageResult)
    }

    @Test
    fun `the legacy error spelling produces the same typed state as the typed one`() {
        val legacy = scope().apply { error("boom", "SCRIPT") }
        val typed = scope().apply { error("boom", FailureKind.SCRIPT) }

        assertEquals(
            typed.steps().single(),
            legacy.steps().single(),
            "both spellings must build the same StepSpec.Error",
        )
    }

    // ---- and refuses everything else, AT CONSTRUCTION --------------------------------

    @Test
    fun `a misspelled legacy catchError result is refused before the pipeline exists`() {
        for (token in listOf("FALURE", "failure", "Stable", "", "FAILURE ", "WAT")) {
            val thrown = assertThrows(IllegalArgumentException::class.java) {
                scope().catchError(buildResult = token) { sh("exit 1") }
            }
            assertTrue(
                thrown.message!!.contains(token.ifEmpty { "''" }),
                "the refusal must name the offending token, said: ${thrown.message}",
            )
            assertTrue(
                thrown.message!!.contains("FAILURE") && thrown.message!!.contains("UNSTABLE"),
                "the refusal must state the vocabulary so the author can fix it: ${thrown.message}",
            )
        }
    }

    @Test
    fun `a misspelled legacy error kind is refused before the pipeline exists`() {
        assertThrows(IllegalArgumentException::class.java) { scope().error("boom", "USR") }
    }

    @Test
    fun `the refusal happens while building, not while running`() {
        // The distinction that matters: a builder throw is a COMPILATION refusal. If any of
        // these produced a StepSpec, the refusal would have moved into the run — which is
        // where the original defect lived.
        val scope = scope()
        assertThrows(IllegalArgumentException::class.java) {
            scope.catchError(buildResult = "FALURE") { sh("exit 1") }
        }
        assertTrue(
            scope.steps().isEmpty(),
            "a refused catchError must leave no step behind, found ${scope.steps()}",
        )
    }

    // ---- and the absence cases stay absences ----------------------------------------

    @Test
    fun `a declared absence is still an absence, on both results`() {
        val spec = scope().apply { catchError { sh("exit 1") } }.steps().single() as StepSpec.CatchError

        assertNull(spec.buildResult, "no declared buildResult must stay null, not default to UNSTABLE")
        assertNull(spec.stageResult, "no declared stageResult must stay null")
    }

    // ---- and the overloads are not ambiguous ----------------------------------------

    @Test
    fun `the two catchError spellings do not collide`() {
        // Two overloads with all parameters defaulted would make `catchError { }` ambiguous
        // and break every bare call — measured as mutation D3-M1, which failed to compile
        // rather than merely misbehaving. The typed overload requires buildResult, so:
        //   catchError { }                              -> legacy (absence is a legal answer)
        //   catchError(buildResult = "FAILURE")         -> legacy (a String is not the type)
        //   catchError(buildResult = CatchErrorBuildResult.Failure) -> typed
        // and each spelling has exactly one candidate.
        val bare = scope().apply { catchError { sh("exit 1") } }
        val legacy = scope().apply { catchError(buildResult = "FAILURE") { sh("exit 1") } }
        val typed = scope().apply { catchError(CatchErrorBuildResult.Failure) { sh("exit 1") } }

        assertNull((bare.steps().single() as StepSpec.CatchError).buildResult)
        assertEquals(CatchErrorBuildResult.Failure, (legacy.steps().single() as StepSpec.CatchError).buildResult)
        assertEquals(CatchErrorBuildResult.Failure, (typed.steps().single() as StepSpec.CatchError).buildResult)
    }

    @Test
    fun `the two error spellings do not collide`() {
        // Same reason: the legacy overload's failureKind has no default, so `error("boom")`
        // has a single candidate.
        assertEquals(FailureKind.USER, (scope().apply { error("boom") }.steps().single() as StepSpec.Error).failureKind)
        assertEquals(FailureKind.SCRIPT, (scope().apply { error("boom", "SCRIPT") }.steps().single() as StepSpec.Error).failureKind)
        assertEquals(FailureKind.SCRIPT, (scope().apply { error("boom", FailureKind.SCRIPT) }.steps().single() as StepSpec.Error).failureKind)
    }

    // ---- and the vocabulary is closed on both sides ----------------------------------

    @Test
    fun `both vocabularies are derived, not hand-written twice`() {
        assertEquals(
            FailureKind.entries.map { it.name }.toSet(),
            FailureKind.supportedTokens,
            "the parse map must be derived from the enum, not a second list of its constants",
        )
        assertEquals(
            setOf("SUCCESS", "UNSTABLE", "FAILURE"),
            CatchErrorBuildResult.supportedTokens,
            "widening the catchError vocabulary needs a decision, not a typo",
        )
    }

    @Test
    fun `neither vocabulary defaults an unrecognised token`() {
        // A default would reproduce the original defect one layer up, which is why these two
        // return null rather than falling back to UNKNOWN or Unstable.
        assertNull(FailureKind.parse("USR"), "FailureKind must not fall back to UNKNOWN")
        assertNull(CatchErrorBuildResult.parse("UNSTABL"), "CatchErrorBuildResult must not fall back to Unstable")
    }
}
