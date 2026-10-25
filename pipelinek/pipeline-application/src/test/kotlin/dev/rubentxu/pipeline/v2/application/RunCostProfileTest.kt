package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.runtime.profile.CostSamples
import dev.rubentxu.pipeline.v2.runtime.profile.PartialReason
import dev.rubentxu.pipeline.v2.runtime.profile.ProfileOutcome
import dev.rubentxu.pipeline.v2.runtime.profile.ResolvedEvidence
import dev.rubentxu.pipeline.v2.runtime.profile.RunEvidence
import dev.rubentxu.pipeline.v2.runtime.profile.RuntimeCost
import dev.rubentxu.pipeline.v2.runtime.profile.summarize
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The cost profile is read from events the run wrote anyway. These tests pin
 * the projection's pairing rules, the report's refusal to leak run payloads
 * through its own output, and the parser's admission of the new mode — the
 * three places where a silent lie could enter an otherwise honest report.
 */
class RunCostProfileTest {

    private var seq = 0L
    private val cacheKey = CacheKey("hash", CacheKey.V2)

    private fun run(scriptPath: String = "/workspace/secret/script.kts", at: Long) =
        RunStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), scriptPath)

    private fun finished(outcome: String, at: Long) =
        RunFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), outcome, emptyList())

    private fun compileStart(at: Long) =
        CompilationStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at))

    private fun compileFinish(at: Long) =
        CompilationFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), cacheKey, emptyList())

    private fun stepStart(at: Long, stage: Int = 0, step: Int = 0, name: String = "deploy") =
        StepStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, name, "sh")

    private fun stepFinish(at: Long, stage: Int = 0, step: Int = 0) =
        StepFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, "deploy", "sh", "success")

    private fun stepFail(at: Long, stage: Int? = 0, step: Int = 0) =
        StepFailed("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, "deploy", "sh", FailureKind.SCRIPT, "exit 1")

    @Test
    fun `a completed run measures compile and step dispatch from its own events`() {
        val events = listOf(
            run(at = 0),
            compileStart(at = 10),
            compileFinish(at = 210),
            stepStart(at = 300),
            stepFinish(at = 400),
            stepStart(at = 500, step = 1),
            stepFinish(at = 560, step = 1),
            finished("success", at = 600),
        )

        val outcome = summarize(RunCostProjection.project(events))

        assertTrue(outcome is ProfileOutcome.Complete)
        assertEquals(
            ResolvedEvidence.Measured(
                dev.rubentxu.pipeline.v2.runtime.profile.MeasuredDuration(10, 210, RunCostProjection.CLOCK_DOMAIN),
            ),
            outcome.costs.first { it.cost == RuntimeCost.Compile }.evidence,
        )
        val series = outcome.costs.first { it.cost == RuntimeCost.StepDispatch }.evidence as ResolvedEvidence.Series
        assertEquals(2, series.count)
        assertEquals(160L, series.totalMs)
    }

    @Test
    fun `a failed step is still a measured step, on a partial profile`() {
        val events = listOf(
            run(at = 0),
            stepStart(at = 20),
            stepFail(at = 120),
            finished("failure", at = 150),
        )

        val outcome = summarize(RunCostProjection.project(events))

        assertTrue(outcome is ProfileOutcome.Partial)
        assertEquals(
            PartialReason.RunDidNotSucceed("failure"),
            (outcome as ProfileOutcome.Partial).reasons.single(),
        )
        val evidence = outcome.costs.first { it.cost == RuntimeCost.StepDispatch }.evidence
        val measured = evidence as ResolvedEvidence.Measured
        assertEquals(100L, measured.duration.millis)
    }

    @Test
    fun `a step that never closed is bounded, and the report says so`() {
        val events = listOf(
            run(at = 0),
            stepStart(at = 30),
            finished("failure", at = 90),
        )

        val outcome = summarize(RunCostProjection.project(events))

        assertTrue(outcome is ProfileOutcome.Partial)
        val reasons = (outcome as ProfileOutcome.Partial).reasons
        assertTrue(reasons.contains(PartialReason.RunDidNotSucceed("failure")))
        assertTrue(reasons.contains(PartialReason.DanglingInterval(RuntimeCost.StepDispatch)))
        val evidence = outcome.costs.first { it.cost == RuntimeCost.StepDispatch }.evidence
        assertTrue(evidence is ResolvedEvidence.Estimated)
        assertEquals(60L, (evidence as ResolvedEvidence.Estimated).estimate.windowMillis)
    }

    @Test
    fun `the report carries timings only, never the run's payloads`() {
        val events = listOf(
            run(scriptPath = "/workspace/secret/script.kts", at = 0),
            stepStart(at = 10, name = "deployProductionKey"),
            stepFinish(at = 50),
            finished("success", at = 60),
        )
        val outcome = summarize(RunCostProjection.project(events))

        val rendered = (RunCostReport.textLines("run-1", outcome) + RunCostReport.jsonLines("run-1", outcome))
            .joinToString("\n")

        assertTrue(!rendered.contains("secret"))
        assertTrue(!rendered.contains("deployProductionKey"))
        assertTrue(rendered.contains("outcome=complete"))
        assertTrue(rendered.contains("\"schema\":\"runtime.profile.v1\""))
    }

    @Test
    fun `the profile mode parses alone and is refused together with the state mode`() {
        val parsed = MainInspectCli.parseInspect(arrayOf("--profile", "--db", "/tmp/x.db"))
        assertTrue(parsed is InspectParseResult.Parsed)
        assertEquals(true, (parsed as InspectParseResult.Parsed).value.profile)

        val both = MainInspectCli.parseInspect(arrayOf("--state", "--profile"))
        assertTrue(both is InspectParseResult.Rejected)
    }

    @Test
    fun `an ambiguous terminal attributes a duration to no candidate`() {
        val events = listOf(
            run(at = 0),
            dev.rubentxu.pipeline.v2.events.StageStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(10), 0, "build"),
            stepStart(at = 15, step = 1),
            dev.rubentxu.pipeline.v2.events.StageStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(20), 1, "test"),
            StepStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(25), 1, 1, "deploy", "sh"),
            // Two open steps share ordinal 1 across stages; a terminal
            // without a stage index names neither, and neither is measured.
            StepFailed("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(50), null, 1, "deploy", "sh", FailureKind.SCRIPT, "exit 1"),
            finished("failure", at = 60),
        )

        val input = RunCostProjection.project(events)

        val samples = input.costs[dev.rubentxu.pipeline.v2.runtime.profile.RuntimeCost.StepDispatch]
        assertTrue(samples is dev.rubentxu.pipeline.v2.runtime.profile.CostSamples.Estimate)
    }

    @Test
    fun `events with no run boundaries still report their costs honestly`() {
        val events = listOf(compileStart(at = 5), compileFinish(at = 45))

        val input = RunCostProjection.project(events)

        assertEquals(RunEvidence.NoRunBounds, input.run)
        assertEquals(CostSamples.None, input.costs[RuntimeCost.StepDispatch])
    }
}
