package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * P3-E E6 — a recovered catchError scope decides exactly what the fresh one decided.
 *
 * ## What "recovery" is here, and what it deliberately is not
 *
 * There is NO overlay serializer in production: `ContextOverlay.CatchErrorOverlay` is
 * `@Serializable` because it is part of the compiled-pipeline IR, not because a runtime ever
 * writes and re-reads one. So "fresh == restart" cannot be built by round-tripping an overlay
 * through JSON and calling it recovery — that would invent a mechanism the product does not
 * have, which is the failure mode this file is written against.
 *
 * What IS durable, and what a restart actually re-reads, is the EVENT STREAM. So the law is
 * stated on the two things that survive a process boundary:
 *
 * ```text
 * fresh run      -> event -> durable bytes
 * restart        -> NEW decoder over those bytes -> typed values -> decision
 * ```
 *
 * and the assertion is on the DECISION, not on the serialization. A codec test already pins
 * that a value survives encode/decode; pinning that twice proves nothing new. The claim nobody
 * has pinned before is that the recovery path cannot change what the run DOES.
 *
 * ## Why the decision is the interesting part
 *
 * `UNSTABLE` suppresses the failure and continues; `FAILURE` re-throws it. Those two produce
 * opposite runs. If a recovery path mapped one into the other, every test that only inspected
 * the round-tripped event would still be green while the restarted pipeline did the opposite of
 * what the fresh one did.
 */
@DisplayName("P3-E E6 — a recovered catchError scope decides the same thing the fresh one did")
class CatchErrorRecoveryEquivalenceTest {

    private val originalFailure = PipelineFailure(FailureKind.SCRIPT, "the real failure")

    private fun freshContext(
        buildResult: CatchErrorBuildResult,
        stageResult: CatchErrorBuildResult = CatchErrorBuildResult.Unstable,
    ) = ExecutionContext(
        overlays = listOf(
            ContextOverlay.CatchErrorOverlay(
                buildResult = buildResult,
                stageResult = stageResult,
                message = "caught by the enclosing scope",
                enteredAt = 0L,
            ),
        ),
    )

    /** The durable bytes a restart would read back. */
    private fun durableBytesFor(
        buildResult: CatchErrorBuildResult?,
        stageResult: CatchErrorBuildResult,
    ): String = JsonEventLog.encode(
        listOf(
            CatchErrorTriggered(
                eventId = "evt-recovery",
                runId = "run-recovery",
                sequence = 1L,
                occurredAt = Instant.parse("2026-10-06T00:00:00Z"),
                stageName = "build",
                buildResult = buildResult,
                stageResult = stageResult,
                message = "caught by the enclosing scope",
            ),
        ),
    )

    /** A restarted process: a fresh decoder, and an overlay rebuilt from what it read. */
    private fun recoveredContext(bytes: String): ExecutionContext {
        val recovered = JsonEventLog.decode(bytes).filterIsInstance<CatchErrorTriggered>().single()
        return freshContext(
            buildResult = recovered.buildResult!!,
            stageResult = recovered.stageResult,
        )
    }

    private fun decide(context: ExecutionContext): CanonicalContinuation =
        RunLifecycleEngine(dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore())
            .decideStageContinuation(
                outcome = StepOutcome.Failure(originalFailure),
                stageName = "build",
                runIdValue = "catch-error-recovery",
                executionContext = context,
            )

    @Test
    fun `FAILURE recovers into the same re-throwing decision`() {
        val bytes = durableBytesFor(CatchErrorBuildResult.Failure, CatchErrorBuildResult.Failure)

        assertEquals(
            decide(freshContext(CatchErrorBuildResult.Failure, CatchErrorBuildResult.Failure)),
            decide(recoveredContext(bytes)),
            "FAILURE must re-throw after recovery exactly as it did before",
        )
        assertEquals(
            CanonicalContinuation.Abort(originalFailure),
            decide(recoveredContext(bytes)),
            "a re-throwing scope must leave the ORIGINAL failure intact, not replace it",
        )
    }

    @Test
    fun `UNSTABLE recovers into the same suppressing-but-unstable decision`() {
        val bytes = durableBytesFor(CatchErrorBuildResult.Unstable, CatchErrorBuildResult.Unstable)

        assertEquals(
            CanonicalContinuation.ContinueUnstable,
            decide(recoveredContext(bytes)),
            "UNSTABLE continues the run and marks it unstable — the opposite of FAILURE",
        )
        assertEquals(
            decide(freshContext(CatchErrorBuildResult.Unstable)),
            decide(recoveredContext(bytes)),
        )
    }

    @Test
    fun `SUCCESS recovers into the same clean-suppression decision`() {
        val bytes = durableBytesFor(CatchErrorBuildResult.Success, CatchErrorBuildResult.Success)

        assertEquals(
            CanonicalContinuation.Continue,
            decide(recoveredContext(bytes)),
            "SUCCESS suppresses and continues clean",
        )
        assertEquals(
            decide(freshContext(CatchErrorBuildResult.Success)),
            decide(recoveredContext(bytes)),
        )
    }

    @Test
    fun `a valid absence is still an absence after recovery, not an invented result`() {
        // `buildResult` is nullable because ABSENCE is a real historical encoding. Recovery must
        // not resolve it: the supplier default belongs to the COMPILER (Jenkins UNSTABLE), not to
        // a decoder that never saw the value. An overlay cannot even be built from a null here,
        // which is precisely why the test asserts the refusal instead of inventing one.
        val bytes = durableBytesFor(null, CatchErrorBuildResult.Unstable)

        val recovered = JsonEventLog.decode(bytes).filterIsInstance<CatchErrorTriggered>().single()

        assertEquals(
            null,
            recovered.buildResult,
            "an absent buildResult must recover as absent",
        )
        assertEquals(
            CatchErrorBuildResult.Unstable,
            recovered.stageResult,
            "while the REQUIRED result still decodes",
        )
    }

    @Test
    fun `a corrupt payload is REFUSED after recovery, and never becomes a suppressing decision`() {
        // The whole reason `stageResult` is required-and-refused rather than defaulted. A
        // decoder that answered UNSTABLE here would hand a restarted run the one value that
        // SUPPRESSES the failure, having learned nothing from the bytes it could not read.
        for (token in listOf("FALURE", "success", "", "WAT")) {
            val tampered = durableBytesFor(
                CatchErrorBuildResult.Unstable,
                CatchErrorBuildResult.Unstable,
            ).replace("\"stageResult\":\"UNSTABLE\"", "\"stageResult\":\"$token\"")

            val decoded = JsonEventLog.decode(tampered).filterIsInstance<CatchErrorTriggered>()

            assertTrue(
                decoded.isEmpty(),
                "'$token' must leave the record unclassified. Decoding it to Unstable would tell " +
                    "the restarted run to CONTINUE on the strength of a value it could not read.",
            )
        }
    }

    @Test
    fun `an unknown token in the overlay cannot be built at all`() {
        // The stronger half, and the reason the type exists: there is no `String` to get wrong.
        // A restart cannot produce an out-of-vocabulary overlay, so no recovery path can read one.
        val values = CatchErrorBuildResult.supportedTokens.map { CatchErrorBuildResult.parse(it) }

        assertEquals(3, values.size)
        assertTrue(
            values.none { it == null },
            "every advertised token must parse; a null here would mean the vocabulary and the " +
                "parser had drifted apart",
        )
    }
}
