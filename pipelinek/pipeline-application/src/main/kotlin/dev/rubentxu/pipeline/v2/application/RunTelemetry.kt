package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.runtime.telemetry.TELEMETRY_SCHEMA
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryPolicy
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetrySignal
import dev.rubentxu.pipeline.v2.runtime.telemetry.redactAndLimit

/**
 * Projects a run's persisted events into telemetry signals.
 *
 * The signals are derived, never authoritative: span and trace identifiers
 * are deterministic digests of the durable identities, so a platform reading
 * a trace can correlate it back to the journal without the trace ever
 * becoming a second source of run truth. Every signal then passes the
 * redaction policy, so what leaves this projection is already the shape the
 * outside world may see.
 */
object RunTelemetryProjection {

    private const val TRACE = "run"
    private const val SPAN_COMPILE = "compile"
    private const val SPAN_STAGE = "stage"
    private const val SPAN_STEP = "step"

    fun fromEvents(runId: String, events: List<DomainEvent>, policy: TelemetryPolicy = TelemetryPolicy.Default): List<TelemetrySignal> {
        val signals = buildSignals(runId, events)
        return signals.map { redactAndLimit(it, policy) }
    }

    private fun buildSignals(runId: String, events: List<DomainEvent>): List<TelemetrySignal> {
        val signals = mutableListOf<TelemetrySignal>()
        val traceId = traceOf(runId)
        var runSpan: String? = null
        var compileStart: Long? = null
        val stageSpans = mutableMapOf<Int, String>()
        val stepSpans = ArrayDeque<OpenStepSpan>()

        for (event in events) {
            val at = event.occurredAt.toEpochMilli()
            when (event) {
                is RunStarted -> {
                    val span = spanId(traceId, TRACE)
                    runSpan = span
                    signals.add(started("pipelinek.run", span, null, at, listOf(attribute("run.id", runId))))
                }

                is RunFinished -> {
                    val span = runSpan
                    if (span != null) {
                        signals.add(ended("pipelinek.run", span, at, event.outcome))
                        signals.add(counter("pipelinek.run_outcome_total", mapOf("status" to outcomeClass(event.outcome))))
                    }
                }

                is CompilationStarted -> compileStart = at
                is CompilationFinished -> {
                    val start = compileStart
                    val parent = runSpan
                    if (start != null && parent != null) {
                        val span = spanId(traceId, SPAN_COMPILE)
                        signals.add(started("pipelinek.script.compile", span, parent, start, emptyList()))
                        signals.add(ended("pipelinek.script.compile", span, at, compileOutcome(event)))
                        signals.add(histogram("pipelinek.compile_duration_ms", at - start, emptyMap()))
                    }
                }

                is StageStarted -> {
                    val parent = runSpan
                    if (parent != null) {
                        val span = spanId(traceId, "$SPAN_STAGE/${event.stageIndex}")
                        stageSpans[event.stageIndex] = span
                        signals.add(
                            started(
                                "pipelinek.stage",
                                span,
                                parent,
                                at,
                                listOf(TelemetryAttribute.Count("stage.index", event.stageIndex.toLong())),
                            ),
                        )
                    }
                }

                is StageFinished -> {
                    val span = stageSpans.remove(event.stageIndex)
                    if (span != null) signals.add(ended("pipelinek.stage", span, at, event.outcome))
                }

                is StepStarted -> {
                    val parent = stageSpans[event.stageIndex]
                    if (parent != null) {
                        val span = spanId(traceId, "$SPAN_STEP/${event.stageIndex}/${event.stepIndex}")
                        stepSpans.addLast(OpenStepSpan(event.stageIndex, event.stepIndex, span, at))
                        signals.add(
                            started(
                                "pipelinek.step",
                                span,
                                parent,
                                at,
                                listOf(
                                    TelemetryAttribute.Count("stage.index", event.stageIndex.toLong()),
                                    TelemetryAttribute.Count("step.index", event.stepIndex.toLong()),
                                ),
                            ),
                        )
                    }
                }

                is StepFinished -> closeStep(stepSpans, event.stageIndex, event.stepIndex, at, event.outcome ?: "unknown", signals)
                is StepFailed -> closeStep(stepSpans, event.stageIndex, event.stepIndex, at, "failure", signals, event.failureKind.name)

                else -> {}
            }
        }
        return signals
    }

    private data class OpenStepSpan(val stageIndex: Int, val stepIndex: Int, val spanId: String, val startedAtMs: Long)

    private fun closeStep(
        open: ArrayDeque<OpenStepSpan>,
        stageIndex: Int?,
        stepIndex: Int,
        endedAtMs: Long,
        status: String,
        signals: MutableList<TelemetrySignal>,
        failureClass: String? = null,
    ) {
        val index = open.indexOfFirst { it.stepIndex == stepIndex && (stageIndex == null || it.stageIndex == stageIndex) }
        if (index < 0) return
        val step = open.removeAt(index)
        signals.add(ended("pipelinek.step", step.spanId, endedAtMs, status))
        val dimensions = buildMap(2) {
            put("operation_kind", "step")
            put("status", status)
            failureClass?.let { put("failure_class", it) }
        }
        signals.add(histogram("pipelinek.step_duration_ms", endedAtMs - step.startedAtMs, dimensions))
    }

    private fun started(name: String, span: String, parent: String?, at: Long, attributes: List<TelemetryAttribute>) =
        TelemetrySignal.SpanStarted(name, span, parent, at, attributes)

    private fun ended(name: String, span: String, at: Long, status: String) =
        TelemetrySignal.SpanEnded(name, span, at, status)

    private fun counter(name: String, dimensions: Map<String, String>) =
        TelemetrySignal.Counter(name, 1, dimensions)

    private fun histogram(name: String, millis: Long, dimensions: Map<String, String>) =
        TelemetrySignal.Histogram(name, millis, dimensions)

    private fun attribute(key: String, value: String) = TelemetryAttribute.Text(key, value)

    /**
     * A trace id derived from the run id: correlatable by anyone holding the
     * journal, and never an identity of its own — two runs are two traces
     * because two run ids are two runs.
     */
    private fun traceOf(runId: String): String = Sha256.ofText("pipelinek-telemetry/run/$runId").take(32)

    private fun spanId(traceId: String, scope: String): String =
        Sha256.ofText("span/$traceId/$scope").take(16)

    private fun compileOutcome(event: CompilationFinished): String =
        if (event.diagnostics.any { it.severity.name == "ERROR" }) "failure" else "success"

    private fun outcomeClass(outcome: String): String = when (outcome) {
        "success" -> "success"
        "unstable" -> "unstable"
        else -> "failure"
    }
}

/**
 * Renders telemetry signals as one JSON object per line. Line-delimited
 * JSON is the whole choice: a collector or agent can tail the stream, and a
 * truncated last line is dropped by the reader without losing the lines
 * before it.
 */
object RunTelemetryReport {

    fun jsonLines(runId: String, signals: List<TelemetrySignal>): List<String> = listOf(
        "{\"schema\":\"$TELEMETRY_SCHEMA\",\"runId\":\"${escape(runId)}\"}",
    ) + signals.map { signal ->
        when (signal) {
            is TelemetrySignal.SpanStarted ->
                "{\"kind\":\"span_started\",\"name\":\"${escape(signal.name)}\",\"spanId\":\"${signal.spanId}\"," +
                    "\"parentSpanId\":${signal.parentSpanId?.let { "\"$it\"" } ?: "null"}," +
                    "\"startedAtMs\":${signal.startedAtMs},\"attributes\":${attributes(signal.attributes)}}"

            is TelemetrySignal.SpanEnded ->
                "{\"kind\":\"span_ended\",\"name\":\"${escape(signal.name)}\",\"spanId\":\"${signal.spanId}\"," +
                    "\"endedAtMs\":${signal.endedAtMs},\"status\":\"${escape(signal.status)}\"}"

            is TelemetrySignal.Counter ->
                "{\"kind\":\"counter\",\"name\":\"${escape(signal.name)}\",\"value\":${signal.value}," +
                    "\"dimensions\":${dimensions(signal.dimensions)}}"

            is TelemetrySignal.Histogram ->
                "{\"kind\":\"histogram\",\"name\":\"${escape(signal.name)}\",\"milliseconds\":${signal.milliseconds}," +
                    "\"dimensions\":${dimensions(signal.dimensions)}}"
        }
    }

    private fun attributes(attributes: List<TelemetryAttribute>): String =
        attributes.joinToString(prefix = "{", postfix = "}", separator = ",") { attribute ->
            when (attribute) {
                is TelemetryAttribute.Text -> "\"${escape(attribute.key)}\":\"${escape(attribute.value)}\""
                is TelemetryAttribute.Count -> "\"${escape(attribute.key)}\":${attribute.value}"
                is TelemetryAttribute.Flag -> "\"${escape(attribute.key)}\":${attribute.value}"
            }
        }

    private fun dimensions(dimensions: Map<String, String>): String =
        dimensions.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "\"${escape(key)}\":\"${escape(value)}\""
        }

    private fun escape(s: String): String = escapeJsonText(s)
}
