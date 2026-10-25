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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The projection turns the run's own events into exportable signals, and
 * the failures that matter here are all leaks of intent: the run's
 * payloads travelling as telemetry, spans that do not pair or nest the
 * way the run actually happened, durations the journal does not support,
 * and an export a consumer cannot place.
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
        val trace = RunTelemetryProjection.fromEvents(
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
        val signals = trace.signals

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
        ).signals

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
        val trace = RunTelemetryProjection.fromEvents(
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

        val rendered = RunTelemetryReport.jsonLines(trace).joinToString("\n")

        assertTrue(rendered.contains("\"schema\":\"pipelinek.telemetry.v2\""))
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
        ).signals

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
        ).signals

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

    @Test
    fun `every exported line names the trace it belongs to`() {
        val trace = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 10, index = 0),
                stepStart(at = 20, stage = 0, step = 0),
                stepFinish(at = 30, stage = 0, step = 0),
                stageFinish(at = 40, index = 0),
                runFinish("success", at = 50),
            ),
        )

        val lines = RunTelemetryReport.jsonLines(trace)

        assertTrue(lines.size > 1, "the export carries no signals")
        // A reader that receives ONE line must be able to say which run it
        // belongs to without the header that came before it.
        assertTrue(lines.all { it.contains("\"traceId\":\"${trace.traceId}\"") })
    }

    @Test
    fun `the branches of a parallel stage have a span each`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 10, index = 0),
                stepStart(at = 15, stage = 0, step = 0, name = "parallel/branch-a/echo-0"),
                stepStart(at = 16, stage = 0, step = 0, name = "parallel/branch-b/echo-0"),
                stepStart(at = 17, stage = 0, step = 0, name = "parallel/branch-c/echo-0"),
                stepFinish(at = 60, stage = 0, step = 0, name = "parallel/branch-c/echo-0"),
                stepFinish(at = 50, stage = 0, step = 0, name = "parallel/branch-a/echo-0"),
                stepFinish(at = 40, stage = 0, step = 0, name = "parallel/branch-b/echo-0"),
                stageFinish(at = 70, index = 0),
                runFinish("success", at = 80),
            ),
        ).signals

        val stepSpans = signals.filterIsInstance<TelemetrySignal.SpanStarted>().filter { it.name == "pipelinek.step" }

        // Three branches that share a stage and an ordinal are three spans.
        // One id for all of them would tell a consumer that three steps
        // happened where one did.
        assertEquals(3, stepSpans.size)
        assertEquals(3, stepSpans.map { it.spanId }.toSet().size)
    }

    @Test
    fun `the span of a branch does not depend on the order the branches opened`() {
        fun traceOf(first: String, second: String) = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 10, index = 0),
                stepStart(at = 15, stage = 0, step = 0, name = first),
                stepStart(at = 16, stage = 0, step = 0, name = second),
                stepFinish(at = 40, stage = 0, step = 0, name = first),
                stepFinish(at = 50, stage = 0, step = 0, name = second),
                stageFinish(at = 60, index = 0),
                runFinish("success", at = 70),
            ),
        )

        fun stepSpanIds(trace: dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryTrace) = trace.signals
            .filterIsInstance<TelemetrySignal.SpanStarted>()
            .filter { it.name == "pipelinek.step" }
            .map { it.spanId }

        val aThenB = stepSpanIds(traceOf("parallel/branch-a/echo-0", "parallel/branch-b/echo-0"))
        val bThenA = stepSpanIds(traceOf("parallel/branch-b/echo-0", "parallel/branch-a/echo-0"))

        // The two traces describe the same two branches; only the order in
        // which they opened differs. Their spans must not differ either: an
        // id that depends on the position would give the same branch a
        // different identity in two runs that did the same work.
        assertEquals(aThenB[1], bThenA[0])
        assertEquals(aThenB[0], bThenA[1])
    }

    @Test
    fun `a stage that runs twice does not reuse its first span`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 10, index = 0),
                stageFinish(at = 20, index = 0),
                // The stage runs again: a retry. Its second occurrence is
                // its own span, not the first one seen twice.
                stageStart(at = 30, index = 0),
                stageFinish(at = 40, index = 0),
                runFinish("success", at = 50),
            ),
        ).signals

        val stageSpans = signals.filterIsInstance<TelemetrySignal.SpanStarted>().filter { it.name == "pipelinek.stage" }
        val stageEnds = signals.filterIsInstance<TelemetrySignal.SpanEnded>().filter { it.name == "pipelinek.stage" }

        assertEquals(2, stageSpans.size)
        assertEquals(2, stageSpans.map { it.spanId }.toSet().size)
        assertEquals(stageSpans.map { it.spanId }.toSet(), stageEnds.map { it.spanId }.toSet())
    }

    @Test
    fun `a second compilation does not measure itself against the first`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                compileStart(at = 10),
                compileFinish(at = 110),
                // The second compilation brings its own start. Measured
                // against the first one's, it would be 190 ms long.
                compileStart(at = 200),
                compileFinish(at = 260),
                runFinish("success", at = 300),
            ),
        ).signals

        val durations = signals.filterIsInstance<TelemetrySignal.Histogram>()
            .filter { it.name == "pipelinek.compile_duration_ms" }
            .map { it.milliseconds }

        assertEquals(listOf(100L, 60L), durations)
    }

    @Test
    fun `a compilation that never started does not borrow the previous one's clock`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                compileStart(at = 10),
                compileFinish(at = 110),
                // A second terminal with no compilation of its own open. It
                // has no start to measure against, so it measures nothing:
                // measured against the previous compilation's start it would
                // claim a 250 ms compilation that never began.
                compileFinish(at = 260),
                runFinish("success", at = 300),
            ),
        ).signals

        val durations = signals.filterIsInstance<TelemetrySignal.Histogram>()
            .filter { it.name == "pipelinek.compile_duration_ms" }
            .map { it.milliseconds }

        assertEquals(listOf(100L), durations)
    }

    @Test
    fun `a compilation that starts again before it ends is measured from the last start`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                compileStart(at = 10),
                // The compilation starts again before the first one ended:
                // the newest start owns the open compilation, and the one it
                // superseded travels without an end. Measured against the
                // first start it would claim a 250 ms compilation.
                compileStart(at = 200),
                compileFinish(at = 260),
                runFinish("success", at = 300),
            ),
        ).signals

        val durations = signals.filterIsInstance<TelemetrySignal.Histogram>()
            .filter { it.name == "pipelinek.compile_duration_ms" }
            .map { it.milliseconds }

        assertEquals(listOf(60L), durations)
    }

    @Test
    fun `an interval that ends before it starts travels no duration and is counted`() {
        val signals = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 10, index = 0),
                stepStart(at = 130, stage = 0, step = 0),
                // The terminal's own timestamp precedes the start's: the two
                // cannot both be true, so no duration may travel.
                stepFinish(at = 100, stage = 0, step = 0),
                stageFinish(at = 240, index = 0),
                runFinish("success", at = 250),
            ),
        ).signals

        assertTrue(
            signals.none { it is TelemetrySignal.SpanEnded && it.name == "pipelinek.step" },
            "a span whose interval contradicts itself must not carry an end",
        )
        assertTrue(
            signals.none { it is TelemetrySignal.Histogram && it.name == "pipelinek.step_duration_ms" },
            "a negative duration must not travel",
        )
        val anomaly = signals.filterIsInstance<TelemetrySignal.Counter>().first { it.name == "pipelinek.invalid_interval_total" }
        assertEquals("step", anomaly.dimensions["operation_kind"])
        assertEquals("success", anomaly.dimensions["status"])
    }

    @Test
    fun `the OTLP document carries the trace and the spans`() {
        val trace = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 120, index = 0),
                stepStart(at = 130, stage = 0, step = 0),
                stepFinish(at = 230, stage = 0, step = 0),
                stageFinish(at = 240, index = 0),
                runFinish("success", at = 250),
            ),
        )

        val document = Json.parseToJsonElement(RunTelemetryOtlp.document(trace)).jsonObject
        val spans = document["resourceSpans"]!!.jsonArray
            .flatMap { it.jsonObject["scopeSpans"]!!.jsonArray }
            .flatMap { it.jsonObject["spans"]!!.jsonArray }
            .map { it.jsonObject }

        assertEquals(3, spans.size)
        assertTrue(spans.all { it["traceId"]!!.jsonPrimitive.content == trace.traceId })
        val runSpan = spans.first { it["name"]!!.jsonPrimitive.content == "pipelinek.run" }
        assertEquals("0", runSpan["startTimeUnixNano"]!!.jsonPrimitive.content)
        assertEquals("250000000", runSpan["endTimeUnixNano"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the OTLP document carries the run's metrics`() {
        val trace = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 120, index = 0),
                stepStart(at = 130, stage = 0, step = 0),
                stepFinish(at = 230, stage = 0, step = 0),
                stageFinish(at = 240, index = 0),
                runFinish("success", at = 250),
            ),
        )

        val document = Json.parseToJsonElement(RunTelemetryOtlp.document(trace)).jsonObject
        val metrics = document["resourceMetrics"]!!.jsonArray
            .flatMap { it.jsonObject["scopeMetrics"]!!.jsonArray }
            .flatMap { it.jsonObject["metrics"]!!.jsonArray }
            .map { it.jsonObject }

        val counter = metrics.first { it["name"]!!.jsonPrimitive.content == "pipelinek.run_outcome_total" }
        val counterPoint = counter["sum"]!!.jsonObject["dataPoints"]!!.jsonArray.single().jsonObject
        assertEquals("1", counterPoint["value"]!!.jsonPrimitive.content)

        val histogram = metrics.first { it["name"]!!.jsonPrimitive.content == "pipelinek.step_duration_ms" }
        val histogramPoint = histogram["histogram"]!!.jsonObject["dataPoints"]!!.jsonArray.single().jsonObject
        assertEquals("1", histogramPoint["count"]!!.jsonPrimitive.content)
        assertEquals("100", histogramPoint["sum"]!!.jsonPrimitive.content)
        // One bucket per bound, plus the open one.
        assertEquals(10, histogramPoint["bucketCounts"]!!.jsonArray.size)
    }

    @Test
    fun `the otlp shape is refused without the telemetry export`() {
        assertTrue(MainInspectCli.parseInspect(arrayOf("--otlp", "--db", "/tmp/x.db")) is InspectParseResult.Rejected)

        val parsed = MainInspectCli.parseInspect(arrayOf("--telemetry", "--otlp", "--db", "/tmp/x.db"))
        assertTrue(parsed is InspectParseResult.Parsed)
        assertEquals(true, (parsed as InspectParseResult.Parsed).value.otlp)
    }
}
