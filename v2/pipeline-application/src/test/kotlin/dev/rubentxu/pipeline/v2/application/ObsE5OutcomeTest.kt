package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservedOutcome
import dev.rubentxu.pipeline.v2.application.observation.ObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

/**
 * OBS-E5f — `--outcome`: one vocabulary across three producers that spelled it three ways.
 *
 * ## Why this needed typing before it needed exposing
 *
 * The dimension existed and compiled and no caller could reach it, the same shape `--channel` was in
 * before OBS-E5a. Looking at WHY turned up the reason it had survived: the producers disagree.
 * Measured, not assumed:
 *
 * ```text
 * RunFinished          "failure"      from RunOutcome.Failure
 * StageFinished        "failed"       from StageOutcomeWire.FAILED
 * StepFailed           no token       its TYPE is the claim
 * StageSkipped         no token       its TYPE is the claim
 * StageMarkedUnstable  no token       its TYPE is the claim
 * ```
 *
 * A filter over those strings answers "which spelling did this producer use". `--outcome FAILURE`
 * would return every failed step and no failed run. `ObservedOutcome` collapses the spelling and
 * names the set the producers already agree on semantically.
 *
 * ```text
 * OUTCOME-1 a run that failed and a step that failed are the SAME outcome
 * OUTCOME-2 a spelling this build does not name is carried, not dropped, and matches no filter
 * OUTCOME-3 `--outcome` reaches the query and selects
 * OUTCOME-4 an unknown name is refused, never a wildcard
 * OUTCOME-5 `run --outcome` is refused
 * OUTCOME-6 the filter never decides anything: a run with `--outcome failure` still exits 0
 * ```
 *
 * Mutations, with the rows each was MEASURED to flip:
 *
 * - `M-OC1` (the vocabulary reopens) → map `failed` back to its own spelling instead of to
 *   [ObservedOutcome.Failure]. REDS OUTCOME-1 and OUTCOME-4: with the spellings apart again, a
 *   failed stage is an unknown token and `--outcome failed` is a name nobody accepts.
 * - `M-OC2` (an unknown spelling is dropped) → answer `null` instead of [ObservedOutcome.Other].
 *   REDS OUTCOME-2 alone.
 * - `M-OC3` (an unknown name is a wildcard) → accept it as `Success` instead of refusing.
 *   REDS OUTCOME-4 alone.
 *
 * OUTCOME-5 and OUTCOME-6 are refusals and an identity, and OUTCOME-3 is what the wiring looks like
 * once the vocabulary is right; none of them is killed by a wrong turn in the vocabulary itself, so
 * they are named here as pinning rather than defending.
 */
class ObsE5OutcomeTest {

    private val runId = "run-1"
    private val at: Instant = Instant.parse("2026-10-07T10:00:00Z")

    private fun compiled(query: ObservationQuery) =
        (compileQuery(query) as SelectorCompileResult.Ok).value

    private fun accepts(event: DomainEvent, vararg outcomes: ObservedOutcome) =
        compiled(ObservationQuery(outcomes = outcomes.toSet()))
            .accepts(dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Event(event))

    @Test
    fun `OUTCOME-1 a failed run and a failed step are the same outcome`() {
        val runFailed = RunFinished("e1", runId, 1, at, "failure", emptyList())
        val stepFailed = StepFailed("e2", runId, 2, at, 0, "sh-0", "sh", FailureKind.SCRIPT, "AssertionError")
        val stageFailed = StageFinished("e3", runId, 3, at, 0, "build", "failed")

        assertTrue(accepts(runFailed, ObservedOutcome.Failure), "the run wrote 'failure'")
        assertTrue(accepts(stepFailed, ObservedOutcome.Failure), "the step wrote no token at all")
        assertTrue(accepts(stageFailed, ObservedOutcome.Failure), "the stage wrote 'failed'")

        assertFalse(accepts(runFailed, ObservedOutcome.Success))
        assertTrue(
            !accepts(stepFailed, ObservedOutcome.Success),
            "a StepFailed is not a success, whatever spelling its neighbours use",
        )
    }

    @Test
    fun `OUTCOME-2 an unnamed spelling is carried and matches no filter`() {
        val odd = RunFinished("e1", runId, 1, at, "catastrophically-odd", emptyList())

        assertEquals(
            ObservedOutcome.Other("catastrophically-odd"),
            dev.rubentxu.pipeline.v2.application.observation.outcomeOf(odd),
            "the evidence is KEPT, not deleted: the token is what makes this case different from null",
        )
        val named = listOf(
            ObservedOutcome.Success,
            ObservedOutcome.Unstable,
            ObservedOutcome.Failure,
            ObservedOutcome.Skipped,
            ObservedOutcome.Aborted,
        )
        for (named in named) {
            assertFalse(
                accepts(odd, named),
                "a caller who asked for ${named.wireToken} must not be shown an unknown failure",
            )
        }
    }

    @Test
    fun `OUTCOME-3 --outcome reaches the query and selects`() {
        val events = listOf(
            RunFinished("e1", runId, 1, at, "failure", emptyList()),
            RunFinished("e2", runId, 2, at, "success", emptyList()),
            StepFinished("e3", runId, 3, at, 0, 0, "sh-0", "sh"),
            StageSkipped("e4", runId, 4, at, 1, "docs", "only-docs-changed"),
            StageMarkedUnstable("e5", runId, 5, at, "build", "flaky"),
        )
        val sink = Sink()

        MainObserveCli.replay(
            Lanes(events),
            runId,
            parsed("--view", "events", "--outcome", "failure"),
            sink.out,
            sink.diagnostics,
        )

        val out = sink.stdout()
        assertTrue(out.contains("Finished: failure"), "got:\n$out")
        assertTrue(!out.contains("Finished: success"), "got:\n$out")
        assertTrue(
            !out.contains("[step: build] sh-0"),
            "a successful step carries NO outcome token, so an outcome filter excludes it rather " +
                "than guessing one. got:\n$out",
        )
    }

    @Test
    fun `OUTCOME-4 an unknown name is refused, never a wildcard`() {
        // `failed` is deliberately NOT here: it is the spelling StageFinished writes, and accepting
        // both it and `failure` is the collapse that stops `--outcome` asking which producer wrote
        // the event. The first version of this row listed it as invalid, which contradicted the
        // design it was supposed to pin.
        for (bad in listOf("FAILURE!", "", "outcome", "not-an-outcome")) {
            val result = CliParser.parseObservation(arrayOf("--view", "events", "--outcome", bad))
            assertTrue(
                result is ObservationParseResult.Rejected && result.error is CliError.InvalidOutcome,
                "--outcome '$bad' must be refused; a wildcard would return the opposite of the ask. " +
                    "got: $result",
            )
        }
        assertEquals(
            setOf(ObservedOutcome.Failure, ObservedOutcome.Skipped),
            parsed("--view", "events", "--outcome", "failure", "--outcome", "skipped").query.outcomes,
            "OR within the dimension, like every other one",
        )
        assertEquals(
            setOf(ObservedOutcome.Failure),
            parsed("--view", "events", "--outcome", "failed").query.outcomes,
            "both spellings of the same fact collapse into one outcome, so a stage that failed and a " +
                "run that failed are the same answer",
        )
    }

    @Test
    fun `OUTCOME-5 run refuses --outcome`() {
        val result = CliParser.parse(arrayOf("run", "--outcome", "failure", "pipeline.kts"))

        assertTrue(
            result is CliParseResult.Rejected && result.error is CliError.OptionBelongsToObserve,
            "got: $result",
        )
    }

    @Test
    fun `OUTCOME-6 the filter decides what is printed and nothing else`() {
        // The exit code is the READER's, and stays 0 whatever was selected. A filter that could
        // change it would make `observe` a second authority on whether the run succeeded, which is
        // the shape FArchE4b4WaitUntilTerminalAuthorityTest exists to prevent elsewhere.
        val events = listOf(RunFinished("e1", runId, 1, at, "failure", emptyList()))
        val sink = Sink()

        val outcome = MainObserveCli.replay(
            Lanes(events),
            runId,
            parsed("--view", "events", "--outcome", "success"),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(ObserveOutcome.Replayed, outcome, "a read that matched nothing still succeeded")
        assertEquals("", sink.stdout())
    }

    private fun parsed(vararg args: String): ObservationParseResult.Parsed {
        val result = CliParser.parseObservation(arrayOf(*args))
        assertTrue(result is ObservationParseResult.Parsed, "expected admission, got: $result")
        return result as ObservationParseResult.Parsed
    }

    private class Lanes(private val events: List<DomainEvent>) : ObserveLanes {
        override val hasEventStore = true
        override val hasOutputPlane = false
        override fun eventsOf(runId: String): Sequence<DomainEvent> = events.asSequence()
        override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int) = null
        override fun tailOf(runId: String, tailBytes: Long) = null
        override fun eventSliceOf(runId: String, after: EventCursor?, limit: Int): EventSlice? = null
        override fun outputTailsOf(runId: String) = null
    }

    private class Sink {
        val bytes = ByteArrayOutputStream()
        val out = PrintStream(bytes, true, "UTF-8")
        val diagnostics = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
        fun stdout(): String = bytes.toString("UTF-8")
    }
}
