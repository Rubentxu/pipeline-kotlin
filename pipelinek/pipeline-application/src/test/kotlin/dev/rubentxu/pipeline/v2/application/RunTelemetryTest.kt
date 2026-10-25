package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetrySignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The projection turns the run's own events into exportable signals, and
 * the two failures that matter here are both leaks of intent: the run's
 * payloads travelling as telemetry, and spans that do not pair or nest the
 * way the run actually happened.
 */
class RunTelemetryTest {

    private var seq = 0L

    private fun runStart(scriptPath: String = "/workspace/secret/script.kts", at: Long) =
        RunStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), scriptPath)

    private fun runFinish(outcome: String, at: Long) =
        RunFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), outcome, emptyList())

    private fun compileStart(at: Long) = CompilationStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at))

    private fun compileFinish(at: Long) =
        CompilationFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), cacheKey(), emptyList())

    private fun cacheKey() = dev.rubentxu.pipeline.v2.scripting.CacheKey("hash", dev.rubentxu.pipeline.v2.scripting.CacheKey.V3)

    private fun stageStart(at: Long, index: Int) =
        StageStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), index, "build")

    private fun stageFinish(at: Long, index: Int) =
        StageFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), index, "build", "success")

    private fun stepStart(at: Long, stage: Int, step: Int) =
        StepStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, "deploy", "sh")

    private fun stepFinish(at: Long, stage: Int, step: Int) =
        StepFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, "deploy", "sh", "success")

    private fun stepFail(at: Long, stage: Int, step: Int) =
        StepFailed("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, "deploy", "sh", FailureKind.SCRIPT, "exit 1")

    @Test
    fun `the run, compile and step spans nest under their parents and pair their ends`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                compileStart(at = 10),
                compileFinish(at = 110),
                stageStart(at = 120, index = 0),
                stepStart(at = 130, stage = 0, step = 0),
                stepFinish(at = 230, stage = 0, step = 0),
                stageFinish(at = 240, index = 0),
                runFinish("success", at = 250),
            ),
        )

        val runStartSignal = signals.filterIsInstance<TelemetrySignal.SpanStarted>().first { it.name == "pipelinek.run" }
        val compile = signals.filterIsInstance<TelemetrySignal.SpanStarted>().first { it.name == "pipelinek.script.compile" }
        val step = signals.filterIsInstance<TelemetrySignal.SpanStarted>().first { it.name == "pipelinek.step" }
        val stage = signals.filterIsInstance<TelemetrySignal.SpanStarted>().first { it.name == "pipelinek.stage" }

        assertEquals(null, runStartSignal.parentSpanId)
        assertEquals(runStartSignal.spanId, compile.parentSpanId)
        assertEquals(runStartSignal.spanId, stage.parentSpanId)
        assertEquals(stage.spanId, step.parentSpanId)

        val runEnd = signals.filterIsInstance<TelemetrySignal.SpanEnded>().first { it.name == "pipelinek.run" }
        assertEquals(runStartSignal.spanId, runEnd.spanId)
        assertEquals("success", runEnd.status)
    }

    @Test
    fun `durations become histograms and failures become counted classes`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                compileStart(at = 10),
                compileFinish(at = 110),
                stageStart(at = 120, index = 0),
                stepStart(at = 130, stage = 0, step = 0),
                stepFail(at = 230, stage = 0, step = 0),
                stageFinish(at = 240, index = 0),
                runFinish("failure", at = 250),
            ),
        )

        val compileHistogram = signals.filterIsInstance<TelemetrySignal.Histogram>().first { it.name.endsWith("compile_duration_ms") }
        assertEquals(100L, compileHistogram.milliseconds)

        val stepHistogram = signals.filterIsInstance<TelemetrySignal.Histogram>().first { it.name.endsWith("step_duration_ms") }
        assertEquals(100L, stepHistogram.milliseconds)
        assertEquals("failure", stepHistogram.dimensions["status"])
        assertEquals("SCRIPT", stepHistogram.dimensions["failure_class"])

        val outcome = signals.filterIsInstance<TelemetrySignal.Counter>().first { it.name.endsWith("run_outcome_total") }
        assertEquals("failure", outcome.dimensions["status"])
    }

    @Test
    fun `the exported lines carry the schema, the run id and no run payload`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(scriptPath = "/workspace/secret/script.kts", at = 0),
                stageStart(at = 10, index = 0),
                stepStart(at = 20, stage = 0, step = 0),
                stepFinish(at = 30, stage = 0, step = 0),
                stageFinish(at = 40, index = 0),
                runFinish("success", at = 50),
            ),
        )

        val rendered = RunTelemetryReport.jsonLines("run-1", signals).joinToString("\n")

        assertTrue(rendered.contains("\"schema\":\"pipelinek.telemetry.v1\""))
        assertTrue(rendered.contains("run-1"))
        assertTrue(!rendered.contains("secret"))
        assertTrue(!rendered.contains("deploy"))
    }

    @Test
    fun `the telemetry mode parses alone and shares no query with the other reports`() {
        val parsed = MainInspectCli.parseInspect(arrayOf("--telemetry", "--db", "/tmp/x.db"))
        assertTrue(parsed is InspectParseResult.Parsed)
        assertEquals(true, (parsed as InspectParseResult.Parsed).value.telemetry)

        assertTrue(MainInspectCli.parseInspect(arrayOf("--explain", "--telemetry")) is InspectParseResult.Rejected)
        assertTrue(MainInspectCli.parseInspect(arrayOf("--profile", "--telemetry")) is InspectParseResult.Rejected)
    }
}
