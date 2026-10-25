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

    private fun stepStart(at: Long, stage: Int, step: Int, name: String = "deploy") =
        StepStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, name, "sh")

    private fun stepFinish(at: Long, stage: Int, step: Int, name: String = "deploy") =
        StepFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, name, "sh", "success")

    private fun stepFail(at: Long, stage: Int, step: Int) =
        StepFailed("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, "deploy", "sh", FailureKind.SCRIPT, "exit 1")

    /**
     * A terminal that does not name its stage. That is what a row written by
     * an older build decodes to, and it is the only shape in which a terminal
     * can belong to more than one open step.
     */
    private fun stepFailWithoutStage(at: Long, step: Int) =
        StepFailed("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), null, step, "deploy", "sh", FailureKind.SCRIPT, "exit 1")

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
    fun `an ambiguous terminal attributes a duration to no candidate`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 10, index = 0),
                stepStart(at = 15, stage = 0, step = 1),
                stageStart(at = 20, index = 1),
                stepStart(at = 25, stage = 1, step = 1),
                // Two open steps share ordinal 1 across stages, and this
                // terminal names no stage: it belongs to neither of them, so
                // no span ends and no duration is measured.
                stepFailWithoutStage(at = 50, step = 1),
                runFinish("failure", at = 60),
            ),
        )

        assertTrue(signals.none { it is TelemetrySignal.SpanEnded && it.name == "pipelinek.step" })
        assertTrue(signals.none { it is TelemetrySignal.Histogram && it.name == "pipelinek.step_duration_ms" })
    }

    @Test
    fun `the branches of a parallel stage are measured one by one, not paired in order`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 10, index = 0),
                // Three branches of one parallel stage: same stage, same
                // ordinal, and only the name tells them apart.
                stepStart(at = 15, stage = 0, step = 0, name = "parallel/branch-a/echo-0"),
                stepStart(at = 16, stage = 0, step = 0, name = "parallel/branch-b/echo-0"),
                stepStart(at = 17, stage = 0, step = 0, name = "parallel/branch-c/echo-0"),
                // The branches END in another order than they opened.
                stepFinish(at = 60, stage = 0, step = 0, name = "parallel/branch-c/echo-0"),
                stepFinish(at = 50, stage = 0, step = 0, name = "parallel/branch-a/echo-0"),
                stepFinish(at = 40, stage = 0, step = 0, name = "parallel/branch-b/echo-0"),
                stageFinish(at = 70, index = 0),
                runFinish("success", at = 80),
            ),
        )

        val durations = signals.filterIsInstance<TelemetrySignal.Histogram>()
            .filter { it.name == "pipelinek.step_duration_ms" }
            .map { it.milliseconds }
        // Each duration is the branch's own: c ran 43 ms, a 35 ms, b 24 ms.
        // A terminal paired with the first open candidate would have produced
        // 45, 34 and 23 — three numbers, none of them belonging to the branch
        // they were attributed to.
        assertEquals(listOf(43L, 35L, 24L), durations)
        assertEquals(3, signals.count { it is TelemetrySignal.SpanEnded && it.name == "pipelinek.step" })
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
