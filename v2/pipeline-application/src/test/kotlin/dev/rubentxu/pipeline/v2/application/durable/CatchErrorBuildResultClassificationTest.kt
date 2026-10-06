package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * P3-E E4 — `catchError` classified its `buildResult` over a closed vocabulary.
 *
 * ## The defect E4 fixed
 *
 * The walk over enclosing `catchError` scopes ended in
 *
 * ```kotlin
 * when (overlay.buildResult) {
 *     "FAILURE" -> Unit
 *     "SUCCESS" -> return Continue
 *     else      -> return ContinueUnstable
 * }
 * ```
 *
 * `UNSTABLE` is the correct reading of exactly one token. As `else` it became the reading of
 * everything else, and **every one of those tokens suppressed the failure the scope had been
 * installed to catch**. A pipeline that misspelled its own error handling went unstable and
 * carried on, which is the worst outcome available because nothing is reported.
 *
 * ## What D3 removed from THIS file, and why
 *
 * This class used to carry a `FailClosed` block that fed `"FALURE"`, `"success"`, `""`,
 * `"SUCCESS "` and `"WAT"` into the decision and asserted each one aborts. D3 typed the
 * overlay, so **`decide()` no longer accepts a token that is not one of the three cases**,
 * and those three tests are gone rather than rewritten.
 *
 * They were not rewritten into something tautological on purpose. A test that asserts
 * `CatchErrorBuildResult.parse("FALURE") == null` while the compiler already refuses to
 * build the input proves the type system, not the runtime, and it would have kept the class
 * looking covered.
 *
 * The property they stood for is still real — an unrecognised result must never suppress a
 * failure — it simply moved to the three seams where untrusted text still enters, and is
 * pinned there by `FArchE6CatchErrorResultTypedTest`:
 *
 * - `StructuralOverlayProjection.project` — a garbage token in the IR payload installs NO
 *   overlay, so the scope never catches;
 * - `CatchErrorBuildResult.Serializer` — a garbage token throws rather than decoding;
 * - `JsonEventLog.decode` — a garbage token refuses the record instead of echoing it.
 */
@DisplayName("catchError buildResult is classified over a closed vocabulary")
class CatchErrorBuildResultClassificationTest {

    private val originalFailure = PipelineFailure(FailureKind.SCRIPT, "the real failure")

    // P3-E D3: the helper takes the closed type, so a test can no longer express the very
    // situation this class exists to rule out — an unrecognised token reaching the decision.
    // The refusal cases now live at the decode boundaries instead; see
    // FArchE6CatchErrorResultTypedTest.
    private fun contextWith(
        buildResult: CatchErrorBuildResult,
        stageResult: CatchErrorBuildResult = CatchErrorBuildResult.Unstable,
    ) =
        ExecutionContext(
            overlays = listOf(
                ContextOverlay.CatchErrorOverlay(
                    buildResult = buildResult,
                    stageResult = stageResult,
                    message = "caught by the enclosing scope",
                    enteredAt = 0L,
                ),
            ),
        )

    private fun engineWith(store: InMemoryEventStore) = RunLifecycleEngine(store)

    private fun decide(
        buildResult: CatchErrorBuildResult,
        store: InMemoryEventStore = InMemoryEventStore(),
    ): CanonicalContinuation {
        val engine = engineWith(store)
        return engine.decideStageContinuation(
            outcome = StepOutcome.Failure(originalFailure),
            stageName = "build",
            runIdValue = "catch-error-classify",
            executionContext = contextWith(buildResult),
        )
    }

    @Nested
    @DisplayName("supported tokens keep their documented meaning")
    inner class Supported {

        @Test
        fun `FAILURE lets the failure propagate outward`() {
            // FAILURE means "do NOT catch me here": with a single scope in the chain and no
            // suppressor, the walk is exhausted and the original failure aborts the run.
            val decision = decide(CatchErrorBuildResult.Failure)

            assertEquals(
                CanonicalContinuation.Abort(originalFailure),
                decision,
                "FAILURE must re-throw to the enclosing scope, not swallow the failure",
            )
        }

        @Test
        fun `SUCCESS suppresses the failure and continues clean`() {
            assertEquals(CanonicalContinuation.Continue, decide(CatchErrorBuildResult.Success))
        }

        @Test
        fun `UNSTABLE suppresses the failure but marks the run unstable`() {
            assertEquals(CanonicalContinuation.ContinueUnstable, decide(CatchErrorBuildResult.Unstable))
        }
    }

    @Nested
    @DisplayName("the parser itself")
    inner class Parser {

        @Test
        fun `it is total over the vocabulary and returns null outside it`() {
            for (token in CatchErrorBuildResult.supportedTokens) {
                assertTrue(
                    CatchErrorBuildResult.parse(token) != null,
                    "'$token' is advertised as supported but does not parse",
                )
            }
            assertNull(
                CatchErrorBuildResult.parse("FALURE"),
                "parse MUST return null rather than defaulting — a default here would " +
                    "reproduce the original defect exactly",
            )
        }

        @Test
        fun `the supported set is exactly the three Jenkins results`() {
            assertEquals(
                setOf("SUCCESS", "UNSTABLE", "FAILURE"),
                CatchErrorBuildResult.supportedTokens,
                "the vocabulary is closed; widening it needs a decision, not a typo",
            )
        }

        @Test
        fun `each token maps to the case its name describes`() {
            assertEquals(CatchErrorBuildResult.Success, CatchErrorBuildResult.parse("SUCCESS"))
            assertEquals(CatchErrorBuildResult.Unstable, CatchErrorBuildResult.parse("UNSTABLE"))
            assertEquals(CatchErrorBuildResult.Failure, CatchErrorBuildResult.parse("FAILURE"))
        }
    }
}
