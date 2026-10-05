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
 * P3-E E4 — `catchError` fails CLOSED on a `buildResult` it cannot read.
 *
 * ## The defect
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
 * ## Why these are decisions and not assertions about a string
 *
 * Each row below asserts a *classification* the runtime reaches. The negative rows are the
 * ones that matter: they are the behaviour that used to be wrong, and a test that only pins
 * the happy path would have stayed green through the whole defect.
 */
@DisplayName("catchError buildResult is classified, and fails closed when unreadable")
class CatchErrorBuildResultClassificationTest {

    private val originalFailure = PipelineFailure(FailureKind.SCRIPT, "the real failure")

    private fun contextWith(buildResult: String, stageResult: String = "UNSTABLE") =
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
        buildResult: String,
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
            val decision = decide("FAILURE")

            assertEquals(
                CanonicalContinuation.Abort(originalFailure),
                decision,
                "FAILURE must re-throw to the enclosing scope, not swallow the failure",
            )
        }

        @Test
        fun `SUCCESS suppresses the failure and continues clean`() {
            assertEquals(CanonicalContinuation.Continue, decide("SUCCESS"))
        }

        @Test
        fun `UNSTABLE suppresses the failure but marks the run unstable`() {
            assertEquals(CanonicalContinuation.ContinueUnstable, decide("UNSTABLE"))
        }
    }

    @Nested
    @DisplayName("unreadable tokens fail closed and never suppress")
    inner class FailClosed {

        // Each of these used to return ContinueUnstable.
        private val garbage = listOf(
            "FALURE",      // the most likely typo, and the most damaging
            "success",     // right token, wrong case
            "Stable",
            "",
            "SUCCESS ",
            "WAT",
        )

        @Test
        fun `an unreadable buildResult aborts instead of degrading to unstable`() {
            for (token in garbage) {
                val decision = decide(token)

                assertTrue(
                    decision is CanonicalContinuation.Abort,
                    "'$token' must fail closed, but produced $decision — a suppression " +
                        "decision on an unreadable token is exactly the defect this pins",
                )
            }
        }

        @Test
        fun `the abort names the schema failure and the offending token`() {
            val decision = decide("FALURE") as CanonicalContinuation.Abort

            assertEquals(
                FailureKind.SCHEMA,
                decision.failure.kind,
                "an unreadable buildResult is a pipeline-authoring defect, not a script failure",
            )
            assertTrue(
                decision.failure.message.contains("FALURE"),
                "the failure must name the token it could not read: ${decision.failure.message}",
            )
            assertTrue(
                decision.failure.message.contains("UNSTABLE"),
                "the failure must state the supported vocabulary so the author can fix it: " +
                    decision.failure.message,
            )
        }

        @Test
        fun `an unreadable buildResult is published as absent, not echoed back`() {
            // The event field is nullable for exactly this case. Echoing an unrecognised
            // token would put a value into the durable stream that no consumer can classify,
            // which is the same defect one layer down.
            val store = InMemoryEventStore()
            decide("FALURE", store)

            val emitted = store.readSlice("catch-error-classify", after = null, limit = 10)
                .events.filterIsInstance<CatchErrorTriggered>()

            assertEquals(1, emitted.size, "the scope DID fire, so it stays observable")
            assertNull(
                emitted.single().buildResult,
                "an unreadable token must not be echoed into the durable stream",
            )
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
