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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun stepFinish(at: Long, stage: Int = 0, step: Int = 0, name: String = "deploy") =
        StepFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, name, "sh", "success")

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
        // The run completed; the measurements did not, and the header says
        // so instead of letting "complete" stand for both facts.
        assertTrue(rendered.contains("measured=1/7"))
        assertTrue(rendered.contains("\"schema\":\"runtime.profile.v1\""))
    }

    @Test
    fun `the JSON report is line-delimited and every line is a JSON object`() {
        val events = listOf(
            run(at = 0),
            compileStart(at = 10),
            compileFinish(at = 210),
            stepStart(at = 300),
            stepFinish(at = 400),
            finished("success", at = 600),
        )
        val outcome = summarize(RunCostProjection.project(events))

        val lines = RunCostReport.jsonLines("run-1", outcome)

        // Every line parses ON ITS OWN. A consumer tails this stream and
        // never has to know that a document was split across lines.
        val parsed = lines.map { line -> Json.parseToJsonElement(line) }
        assertTrue(parsed.all { it is JsonObject })
        val header = parsed.first().jsonObject
        assertEquals("runtime.profile.v1", header["schema"]?.jsonPrimitive?.content)
        assertEquals("run-1", header["runId"]?.jsonPrimitive?.content)
        assertEquals("complete", header["outcome"]?.jsonPrimitive?.content)
        // One line per cost, each naming its own cost and its evidence.
        val costLines = parsed.drop(1).map { it.jsonObject }
        assertEquals(outcome.costs.size, costLines.size)
        assertTrue(costLines.all { "cost" in it && "evidence" in it })
    }

    @Test
    fun `a run id carrying quotes and control characters does not break the JSON`() {
        val events = listOf(run(at = 0), stepStart(at = 10), stepFinish(at = 20), finished("success", at = 30))
        val outcome = summarize(RunCostProjection.project(events))

        val lines = RunCostReport.jsonLines("run \"one\"\n\ttwo", outcome)

        // A parser that throws IS the assertion: a raw quote or control
        // character turns a line that looks like JSON into a line that
        // stops being it.
        lines.forEach { line -> Json.parseToJsonElement(line) }
        val header = Json.parseToJsonElement(lines.first()).jsonObject
        assertEquals("run \"one\"\n\ttwo", header["runId"]?.jsonPrimitive?.content)
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
            // A LATER terminal does name its stage, and it closes one of the
            // two — only one. The other stays open, so the cost of the step
            // dispatch is a bound and not a measurement. A terminal that had
            // closed the first candidate instead would leave both steps
            // closed, and the cost would come out MEASURED: a duration
            // invented for a step that no terminal ever named.
            stepFinish(at = 55, stage = 1, step = 1),
            finished("failure", at = 60),
        )

        val input = RunCostProjection.project(events)

        val samples = input.costs[dev.rubentxu.pipeline.v2.runtime.profile.RuntimeCost.StepDispatch]
        assertTrue(samples is dev.rubentxu.pipeline.v2.runtime.profile.CostSamples.Estimate)
        // The step that opened first is still open — the ambiguous terminal
        // did not close it — and the bound starts where that step started.
        assertEquals(
            15L,
            (samples as dev.rubentxu.pipeline.v2.runtime.profile.CostSamples.Estimate).estimate.lowerAtMs,
        )
    }

    @Test
    fun `the branches of a parallel stage are measured with their own intervals`() {
        val events = listOf(
            run(at = 0),
            stepStart(at = 15, name = "parallel/branch-a/echo-0"),
            stepStart(at = 16, name = "parallel/branch-b/echo-0"),
            stepStart(at = 17, name = "parallel/branch-c/echo-0"),
            // The branches END in another order than they opened, and each
            // terminal names the branch it belongs to.
            stepFinish(at = 60, name = "parallel/branch-c/echo-0"),
            stepFinish(at = 50, name = "parallel/branch-a/echo-0"),
            stepFinish(at = 40, name = "parallel/branch-b/echo-0"),
            finished("success", at = 80),
        )

        val input = RunCostProjection.project(events)

        val samples = input.costs[RuntimeCost.StepDispatch]
        assertTrue(samples is CostSamples.Intervals)
        // Each interval is the branch's own: c ran from 17 to 60, a from 15
        // to 50 and b from 16 to 40. A terminal paired with the first open
        // candidate would have measured 15-60, 16-50 and 17-40 — intervals
        // that no branch ever lived.
        assertEquals(
            listOf(
                dev.rubentxu.pipeline.v2.runtime.profile.MeasuredDuration(17, 60, RunCostProjection.CLOCK_DOMAIN),
                dev.rubentxu.pipeline.v2.runtime.profile.MeasuredDuration(15, 50, RunCostProjection.CLOCK_DOMAIN),
                dev.rubentxu.pipeline.v2.runtime.profile.MeasuredDuration(16, 40, RunCostProjection.CLOCK_DOMAIN),
            ),
            (samples as CostSamples.Intervals).intervals,
        )
    }

    @Test
    fun `events with no run boundaries still report their costs honestly`() {
        val events = listOf(compileStart(at = 5), compileFinish(at = 45))

        val input = RunCostProjection.project(events)

        assertEquals(RunEvidence.NoRunBounds, input.run)
        assertEquals(CostSamples.None, input.costs[RuntimeCost.StepDispatch])
    }
}
