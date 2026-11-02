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
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryTrace
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.Status.StatusCode as TraceStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * The projection turns the run's own events into exportable signals, and
 * the failures that matter here are all leaks of intent: the run's
 * payloads travelling as telemetry, spans that do not pair or nest the
 * way the run actually happened, durations the journal does not support,
 * and an export a consumer cannot place.
 */
class RunTelemetryTest {

    /** A real wall-clock moment, so the format carries the timestamps. */
    private val epoch: Long = 1_700_000_000_000L

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
    fun `sequence projection and incremental JSONL preserve the existing export`() {
        val events = listOf(runStart(at = 0), stageStart(at = 10, index = 0), stepStart(at = 20, stage = 0, step = 0),
            stepFinish(at = 40, stage = 0, step = 0), stageFinish(at = 50, index = 0), runFinish("success", at = 60))
        val expected = RunTelemetryProjection.fromEvents("run-1", events)
        val streamed = RunTelemetryProjection.fromEvents("run-1", events.asSequence().constrainOnce())
        val lines = mutableListOf<String>()

        RunTelemetryReport.forEachJsonLine(streamed, lines::add)

        assertEquals(expected, streamed)
        assertEquals(RunTelemetryReport.jsonLines(expected), lines)
    }

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

    /**
     * The class a run's outcome is counted under.
     *
     * The words are the domain's, and each one keeps the class it has always
     * been counted under, so a dashboard does not move under the operator's
     * feet while the result is being typed.
     */
    @Test
    fun `every word the product writes is counted under the class it always had`() {
        assertEquals("success", outcomeClassOf("success"))
        assertEquals("unstable", outcomeClassOf("unstable"))
        assertEquals("failure", outcomeClassOf("failure"))
        assertEquals("failure", outcomeClassOf("aborted"))
    }

    /**
     * A word this build does not know is not a failure.
     *
     * The counter answers "how many runs failed", so a word it cannot read is
     * not a failure it observed: a vocabulary that grew is not a failure.
     * Such a word is counted in its own class, and the run does not land in
     * the failed bucket with nobody looking.
     */
    @Test
    fun `a word the product does not write is not counted as a failure`() {
        assertEquals("unknown", outcomeClassOf("degraded"))
        assertEquals("unknown", outcomeClassOf("SUCCESS"))
    }

    /** The class the counter counts a run under, given the word its outcome carries. */
    private fun outcomeClassOf(outcome: String): String? =
        RunTelemetryProjection
            .fromEvents("run-1", listOf(runStart(at = 0), runFinish(outcome, at = 250)))
            .signals
            .filterIsInstance<TelemetrySignal.Counter>()
            .first { it.name.endsWith("run_outcome_total") }
            .dimensions["status"]

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
    fun `a run that crashed leaves its spans open and invents no terminal`() {
        // The run started, a stage started, a step started, and then the
        // process died: there is no terminal for any of them, because the
        // journal does not have one.
        val trace = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(
                runStart(at = 0),
                stageStart(at = 10, index = 0),
                stepStart(at = 20, stage = 0, step = 0),
            ),
        )

        assertTrue(
            trace.signals.none { it is TelemetrySignal.SpanEnded },
            "una ejecución que se quedó sin terminal no puede tener finales inventados: el journal " +
                "no los tiene y la proyección no los fabrica",
        )
        assertTrue(
            trace.signals.none { it is TelemetrySignal.Histogram && it.name == "pipelinek.step_duration_ms" },
            "un span abierto no mide duración: la duración se mide contra un final que no existe",
        )
        assertEquals(
            3,
            trace.signals.filterIsInstance<TelemetrySignal.SpanStarted>().size,
            "lo que SÍ ocurrió se declara: el run, la etapa y el paso se abrieron",
        )

        val documents = otlpDocuments(trace)
        val spans = documents[0]["resourceSpans"]!!.jsonArray
            .flatMap { it.jsonObject["scopeSpans"]!!.jsonArray }
            .flatMap { it.jsonObject["spans"]!!.jsonArray }
            .map { it.jsonObject }

        assertTrue(
            spans.isEmpty(),
            "un span incompleto no viaja como un span OTLP: el formato exige un final, y un final " +
                "que no existe no se inventa. El hueco sigue visible en el informe plano, que sí " +
                "puede declarar lo que quedó abierto",
        )
    }

    @Test
    fun `the OTLP export writes two standard documents and carries the trace and its spans`() {
        val trace = completedRunTrace()

        val documents = otlpDocuments(trace)

        assertEquals(
            2,
            documents.size,
            "un export OTLP son dos documentos estándar: uno de trazas y otro de métricas. " +
                "Un solo documento con ambos no es una petición OTLP que ningún colector acepte",
        )

        val spans = documents[0]["resourceSpans"]!!.jsonArray
            .flatMap { it.jsonObject["scopeSpans"]!!.jsonArray }
            .flatMap { it.jsonObject["spans"]!!.jsonArray }
            .map { it.jsonObject }

        assertEquals(3, spans.size)
        assertTrue(spans.all { it["traceId"]!!.jsonPrimitive.content == trace.traceId })
        assertTrue(
            spans.all { it.containsKey("endTimeUnixNano") },
            "todo span que viaja es un span terminado: el formato exige un final",
        )
        val runSpan = spans.first { it["name"]!!.jsonPrimitive.content == "pipelinek.run" }
        assertEquals("1700000000000000000", runSpan["startTimeUnixNano"]!!.jsonPrimitive.content)
        assertEquals("1700000000250000000", runSpan["endTimeUnixNano"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the OTLP export carries the run's metrics`() {
        val trace = completedRunTrace()

        val documents = otlpDocuments(trace)
        val metrics = documents[1]["resourceMetrics"]!!.jsonArray
            .flatMap { it.jsonObject["scopeMetrics"]!!.jsonArray }
            .flatMap { it.jsonObject["metrics"]!!.jsonArray }
            .map { it.jsonObject }

        val counter = metrics.first { it["name"]!!.jsonPrimitive.content == "pipelinek.run_outcome_total" }
        val counterPoint = counter["sum"]!!.jsonObject["dataPoints"]!!.jsonArray.single().jsonObject
        assertEquals("1", counterPoint["asInt"]!!.jsonPrimitive.content)

        val histogram = metrics.first { it["name"]!!.jsonPrimitive.content == "pipelinek.step_duration_ms" }
        val histogramPoint = histogram["histogram"]!!.jsonObject["dataPoints"]!!.jsonArray.single().jsonObject
        assertEquals("1", histogramPoint["count"]!!.jsonPrimitive.content)
        assertEquals(100.0, histogramPoint["sum"]!!.jsonPrimitive.double)
        // One bucket per bound, plus the open one.
        assertEquals(10, histogramPoint["bucketCounts"]!!.jsonArray.size)
    }

    /**
     * The document a collector reads is the document the OFFICIAL OTLP model
     * accepts. The test encodes the very same data the exporter writes with
     * the official marshaler, in the official protobuf wire format, and hands
     * it to the official generated model: what comes back must be the export
     * itself, span for span.
     *
     * A decoder written in this project would prove nothing: it would only
     * say that this project agrees with itself.
     */
    @Test
    fun `the exported spans are a document the official OTLP model reads back`() {
        val trace = completedRunTrace()

        val bytes = ByteArrayOutputStream()
        TraceRequestMarshaler.create(RunTelemetryOtlp.spans(trace).toList()).writeBinaryTo(bytes)
        val request = ExportTraceServiceRequest.parseFrom(bytes.toByteArray())

        val scopeSpans = request.resourceSpansList.single().scopeSpansList.single()
        val official = scopeSpans.spansList
        assertEquals(3, official.size)
        assertEquals("dev.rubentxu.pipeline.v2", scopeSpans.scope.name)
        official.forEach { span ->
            assertEquals(trace.traceId, span.traceId.toByteArray().toHex())
            assertTrue(span.endTimeUnixNano > span.startTimeUnixNano, "un span del modelo oficial tiene final")
        }
        val runSpan = official.first { it.name == "pipelinek.run" }
        assertEquals(1_700_000_000_000_000_000L, runSpan.startTimeUnixNano)
        assertEquals(1_700_000_000_250_000_000L, runSpan.endTimeUnixNano)
        assertEquals(TraceStatus.STATUS_CODE_OK, runSpan.status.code)
    }

    /**
     * The two producers of this product's telemetry must be tellable apart by
     * the document itself: the one that publishes a run while it happens, and
     * the one that publishes a finished run read back from the journal.
     */
    @Test
    fun `the historical export names its producer`() {
        val trace = RunTelemetryProjection.fromEvents(
            "run-1",
            listOf(runStart(at = 0), runFinish("success", at = 250)),
        )

        val documents = otlpDocuments(trace)
        val resource = documents[0]["resourceSpans"]!!.jsonArray.single()
            .jsonObject["resource"]!!.jsonObject
        val attributes = resource["attributes"]!!.jsonArray.map { it.jsonObject }

        val source = attributes.first { it["key"]!!.jsonPrimitive.content == TelemetrySource.KEY }
        assertEquals(
            TelemetrySource.HISTORICAL,
            source["value"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
            "el documento dice QUIÉN lo publicó: sin esa etiqueta un backend no puede distinguir " +
                "una ejecución observada en vivo de la misma ejecución leída del diario",
        )
    }

    /**
     * A span whose final precedes its own start measures nothing: its
     * duration would be negative, and no collector can read that as a
     * measurement. The projection does not produce such a span — it counts
     * the contradiction instead — but the writer is the last gate before
     * publishing, so it refuses on its own terms, and the trace it is handed
     * is built here to reach that gate.
     */
    @Test
    fun `a span whose end precedes its start does not travel as a measurement`() {
        val trace = TelemetryTrace(
            runId = "run-1",
            traceId = TelemetryTrace.traceIdOf("run-1"),
            signals = listOf(
                TelemetrySignal.SpanStarted("pipelinek.run", "0123456789abcdef", null, 100L, emptyList()),
                TelemetrySignal.SpanEnded("pipelinek.run", "0123456789abcdef", 50L, "success"),
            ),
        )

        assertTrue(
            RunTelemetryOtlp.spans(trace).count() == 0,
            "un span cuyo final precede a su inicio no se publica: su duración sería negativa, " +
                "y una duración negativa no es una medida",
        )
    }

    /**
     * The export must not grow with the history it carries. The spans
     * travel in BOUNDED batches, each batch one request, so a hundred
     * thousand spans cost the writer one batch and not the whole history.
     *
     * A consumer sees the same spans either way; what the bound buys is a
     * writer whose memory does not follow the length of the run.
     */
    @Test
    fun `the export travels in bounded batches and not the whole history in one document`() {
        val trace = TelemetryTrace(
            runId = "run-1",
            traceId = TelemetryTrace.traceIdOf("run-1"),
            signals = (0 until 257).flatMap { index ->
                val spanId = index.toString(16).padStart(16, '0')
                listOf(
                    TelemetrySignal.SpanStarted("pipelinek.step", spanId, null, epoch + index, emptyList()),
                    TelemetrySignal.SpanEnded("pipelinek.step", spanId, epoch + index + 1, "success"),
                )
            },
        )

        val documents = otlpDocuments(trace)

        assertEquals(
            3,
            documents.size,
            "257 spans no caben en un documento: viajan en lotes acotados —uno de trazas, otro de " +
                "trazas y uno de métricas— y el escritor no sostiene más de un lote a la vez",
        )
        assertEquals(
            256,
            spansIn(documents[0]).size,
            "el primer lote lleva el máximo de spans que un documento transporta",
        )
        assertEquals(
            1,
            spansIn(documents[1]).size,
            "el resto de los spans viaja en el lote que cierra la exportación",
        )
    }

    /**
     * A repeated opening is not a new fact. The journal is append-only and
     * ordered, so the FIRST statement about a span is its start; a second
     * opening of the same id is noise, and measuring the span against it
     * would publish a duration the journal never stated.
     */
    @Test
    fun `a span that opens twice is measured against the opening it was first given`() {
        val trace = TelemetryTrace(
            runId = "run-1",
            traceId = TelemetryTrace.traceIdOf("run-1"),
            signals = listOf(
                TelemetrySignal.SpanStarted("pipelinek.run", "0123456789abcdef", null, 100L, emptyList()),
                TelemetrySignal.SpanStarted("pipelinek.run", "0123456789abcdef", null, 200L, emptyList()),
                TelemetrySignal.SpanEnded("pipelinek.run", "0123456789abcdef", 300L, "success"),
            ),
        )

        val span = spansIn(otlpDocuments(trace)[0]).single()

        assertEquals(
            "100000000",
            span["startTimeUnixNano"]!!.jsonPrimitive.content,
            "una apertura repetida no es un hecho nuevo: el span se mide contra la apertura que " +
                "recibió la primera vez",
        )
    }

    @Test
    fun `the otlp shape is refused without the telemetry export`() {
        assertTrue(MainInspectCli.parseInspect(arrayOf("--otlp", "--db", "/tmp/x.db")) is InspectParseResult.Rejected)

        val parsed = MainInspectCli.parseInspect(arrayOf("--telemetry", "--otlp", "--db", "/tmp/x.db"))
        assertTrue(parsed is InspectParseResult.Parsed)
        assertEquals(true, (parsed as InspectParseResult.Parsed).value.otlp)
    }

    /**
     * The export as the CLI writes it: two standard OTLP documents, one per
     * line. The reader here is the one that already reads the product's
     * other line-delimited output, so the export keeps the shape a consumer
     * can tail.
     */
    private fun otlpDocuments(trace: TelemetryTrace): List<JsonObject> {
        val out = ByteArrayOutputStream()
        RunTelemetryOtlp.writeSpans(trace, out)
        RunTelemetryOtlp.writeMetrics(trace, out)
        return out.toString(StandardCharsets.UTF_8)
            .trim()
            .lines()
            .map { Json.parseToJsonElement(it).jsonObject }
    }

    /** Los spans que un documento de trazas lleva, en el orden en que viajan. */
    private fun spansIn(document: JsonObject): List<JsonObject> =
        document["resourceSpans"]!!.jsonArray
            .flatMap { it.jsonObject["scopeSpans"]!!.jsonArray }
            .flatMap { it.jsonObject["spans"]!!.jsonArray }
            .map { it.jsonObject }

    /**
     * A finished run, at a real wall-clock moment. The moment matters: the
     * format omits a timestamp that is exactly zero, so a run that "starts
     * at zero" would be a run whose start the document does not carry — an
     * artifact of the fixture, not of the export.
     */
    private fun completedRunTrace(): TelemetryTrace = RunTelemetryProjection.fromEvents(
        "run-1",
        listOf(
            runStart(at = epoch),
            stageStart(at = epoch + 120, index = 0),
            stepStart(at = epoch + 130, stage = 0, step = 0),
            stepFinish(at = epoch + 230, stage = 0, step = 0),
            stageFinish(at = epoch + 240, index = 0),
            runFinish("success", at = epoch + 250),
        ),
    )

    /** The hexadecimal the format prints an identifier as. */
    private fun ByteArray.toHex(): String = joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
}
