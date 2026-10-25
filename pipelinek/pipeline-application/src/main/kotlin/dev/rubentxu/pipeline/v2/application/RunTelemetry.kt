package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.attribution.OpenStepIdentity
import dev.rubentxu.pipeline.v2.application.attribution.StepAttribution
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
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryTrace
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
 *
 * The projection is where three properties of the export are decided, and
 * each of them was a hole before:
 *
 *  - **The span id names what it spans.** The scope of a step's span is the
 *    triple `(stage, ordinal, name)` plus the number of times that triple
 *    was opened. Without the name, the branches of a parallel stage —three
 *    steps that share a stage and an ordinal— produced one span id, and a
 *    consumer could not tell three spans from one. Without the occurrence,
 *    a step that ran twice produced two spans with one id. The name enters
 *    the **digest**, never the export: the id is a digest of it, so nothing
 *    about the script's vocabulary leaves the process.
 *  - **An interval that contradicts itself does not travel.** A terminal
 *    that arrives before its own start would produce a negative duration,
 *    and a negative duration is not a fact about the run: it is a fact about
 *    two timestamps that cannot both be true. The span travels without its
 *    end —the shape of a span still running— the duration is withheld, and
 *    the contradiction is counted, so the export says that it saw something
 *    impossible instead of inventing a number.
 *  - **The trace is a field.** The trace id is part of the export, not a
 *    derivation each consumer repeats.
 */
object RunTelemetryProjection {

    private const val TRACE = "run"
    private const val SPAN_COMPILE = "compile"
    private const val SPAN_STAGE = "stage"
    private const val SPAN_STEP = "step"

    fun fromEvents(
        runId: String,
        events: List<DomainEvent>,
        policy: TelemetryPolicy = TelemetryPolicy.Default,
    ): TelemetryTrace {
        val traceId = TelemetryTrace.traceIdOf(runId)
        val signals = buildSignals(runId, traceId, events)
        return redactAndLimit(TelemetryTrace(runId, traceId, signals), policy)
    }

    private fun buildSignals(runId: String, traceId: String, events: List<DomainEvent>): List<TelemetrySignal> {
        val signals = mutableListOf<TelemetrySignal>()
        val ids = SpanIdAllocator(traceId)
        var runSpan: OpenSpan? = null
        var compileSpan: OpenSpan? = null
        val stageSpans = mutableMapOf<Int, ArrayDeque<OpenSpan>>()
        val stepSpans = ArrayDeque<OpenStepSpan>()

        for (event in events) {
            val at = event.occurredAt.toEpochMilli()
            when (event) {
                is RunStarted -> {
                    val span = OpenSpan(ids.next(TRACE), at)
                    runSpan = span
                    signals.add(started("pipelinek.run", span.spanId, null, at, listOf(attribute("run.id", runId))))
                }

                is RunFinished -> {
                    val span = runSpan
                    if (span != null) {
                        // A run ends once. Clearing the open span keeps a
                        // second terminal from closing the same run twice.
                        runSpan = null
                        closeSpan("pipelinek.run", "run", span, at, event.outcome, signals)
                        signals.add(counter("pipelinek.run_outcome_total", mapOf("status" to outcomeClass(event.outcome))))
                    }
                }

                is CompilationStarted -> {
                    val parent = runSpan
                    if (parent != null) {
                        // The compilation opens its span where the compilation
                        // opens: the start it will be measured against is the
                        // one this event carries, and the span is announced
                        // when it begins, like every other span.
                        val span = OpenSpan(ids.next(SPAN_COMPILE), at)
                        compileSpan = span
                        signals.add(started("pipelinek.script.compile", span.spanId, parent.spanId, at, emptyList()))
                    }
                }

                is CompilationFinished -> {
                    val span = compileSpan
                    // The compilation is over: the next terminal must find no
                    // open compilation, or it would close this one a second
                    // time and measure itself against a clock that is not
                    // its own.
                    compileSpan = null
                    if (span != null) {
                        if (closeSpan("pipelinek.script.compile", "compile", span, at, compileOutcome(event), signals)) {
                            signals.add(histogram("pipelinek.compile_duration_ms", at - span.startedAtMs, emptyMap()))
                        }
                    }
                }

                is StageStarted -> {
                    val parent = runSpan
                    if (parent != null) {
                        val span = OpenSpan(ids.next("$SPAN_STAGE/${event.stageIndex}"), at)
                        stageSpans.getOrPut(event.stageIndex) { ArrayDeque() }.addLast(span)
                        signals.add(
                            started(
                                "pipelinek.stage",
                                span.spanId,
                                parent.spanId,
                                at,
                                listOf(TelemetryAttribute.Count("stage.index", event.stageIndex.toLong())),
                            ),
                        )
                    }
                }

                is StageFinished -> {
                    // The oldest open stage span of this index: a stage that
                    // ran twice closes its first occurrence first.
                    val span = stageSpans[event.stageIndex]?.removeFirstOrNull()
                    if (span != null) {
                        closeSpan("pipelinek.stage", "stage", span, at, event.outcome, signals)
                    }
                }

                is StepStarted -> {
                    val parent = stageSpans[event.stageIndex]?.lastOrNull()
                    if (parent != null) {
                        val scope = "$SPAN_STEP/${event.stageIndex}/${event.stepIndex}/${event.stepName}"
                        val span = OpenSpan(ids.next(scope), at)
                        stepSpans.addLast(OpenStepSpan(event.stageIndex, event.stepIndex, event.stepName, span))
                        signals.add(
                            started(
                                "pipelinek.step",
                                span.spanId,
                                parent.spanId,
                                at,
                                listOf(
                                    TelemetryAttribute.Count("stage.index", event.stageIndex.toLong()),
                                    TelemetryAttribute.Count("step.index", event.stepIndex.toLong()),
                                ),
                            ),
                        )
                    }
                }

                is StepFinished ->
                    closeStep(stepSpans, event.stageIndex, event.stepIndex, event.stepName, at, event.outcome ?: "unknown", signals)

                is StepFailed ->
                    closeStep(stepSpans, event.stageIndex, event.stepIndex, event.stepName, at, "failure", signals, event.failureKind.name)

                else -> {}
            }
        }
        return signals
    }

    /** An open span: what it is, and when it began. */
    private data class OpenSpan(val spanId: String, val startedAtMs: Long)

    private data class OpenStepSpan(
        val stageIndex: Int,
        val stepIndex: Int,
        val stepName: String,
        val span: OpenSpan,
    )

    /**
     * Hands out the span ids of one export.
     *
     * The scope names what the span spans, and the occurrence counts how
     * many times that scope was opened, so a span that repeats —a stage that
     * ran twice, a step that was retried— does not reuse its predecessor's
     * id. Both parts are digested: what leaves the process is a hex id, so
     * neither the step's name nor the ordinal is readable in the export.
     */
    private class SpanIdAllocator(private val traceId: String) {
        private val occurrences = mutableMapOf<String, Int>()

        fun next(scope: String): String {
            val occurrence = occurrences.getOrElse(scope) { 0 }
            occurrences[scope] = occurrence + 1
            return Sha256.ofText("span/$traceId/$scope/$occurrence").take(16)
        }
    }

    /**
     * Close a span, unless its own timestamps contradict each other.
     *
     * A terminal that arrives before its start would travel a negative
     * duration, and a negative duration is not a fact about the run but a
     * fact about two timestamps that cannot both be true. So the span
     * travels **without its end** — the shape of a span still running— the
     * duration is withheld, and the contradiction is counted with the
     * dimensions that describe what was contradicted. The count is what
     * keeps the withholding visible: an export that dropped the anomaly
     * would look exactly like one where no anomaly happened.
     *
     * Returns whether the span ended normally.
     */
    private fun closeSpan(
        name: String,
        operationKind: String,
        span: OpenSpan,
        endedAtMs: Long,
        status: String,
        signals: MutableList<TelemetrySignal>,
        failureClass: String? = null,
    ): Boolean {
        if (endedAtMs < span.startedAtMs) {
            signals.add(
                counter(
                    "pipelinek.invalid_interval_total",
                    buildMap {
                        put("operation_kind", operationKind)
                        put("status", status)
                        failureClass?.let { put("failure_class", it) }
                    },
                ),
            )
            return false
        }
        signals.add(ended(name, span.spanId, endedAtMs, status))
        return true
    }

    /**
     * Close the open step the terminal event belongs to, and report its
     * duration — only when the event names one step unambiguously. The
     * decision is the shared one ([StepAttribution]): a terminal without a
     * stage index may match several open steps, and then it belongs to none
     * of them, so no span ends and no duration is measured. Attributing the
     * duration to the first candidate would put a number in the export that
     * the journal does not say.
     *
     * The name the terminal carries breaks the tie when the branches of a
     * parallel stage are open at the same time: they share the stage and
     * the ordinal, and only their names tell them apart.
     */
    private fun closeStep(
        open: ArrayDeque<OpenStepSpan>,
        stageIndex: Int?,
        stepIndex: Int,
        stepName: String,
        endedAtMs: Long,
        status: String,
        signals: MutableList<TelemetrySignal>,
        failureClass: String? = null,
    ) {
        val attribution = StepAttribution.resolve(open, stageIndex, stepIndex, stepName) {
            OpenStepIdentity(it.stageIndex, it.stepIndex, it.stepName)
        }
        if (attribution !is StepAttribution.Matched) return
        val step = open.removeAt(attribution.openIndex)
        if (!closeSpan("pipelinek.step", "step", step.span, endedAtMs, status, signals, failureClass)) return
        val dimensions = buildMap(2) {
            put("operation_kind", "step")
            put("status", status)
            failureClass?.let { put("failure_class", it) }
        }
        signals.add(histogram("pipelinek.step_duration_ms", endedAtMs - step.span.startedAtMs, dimensions))
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

    private fun compileOutcome(event: CompilationFinished): String =
        if (event.diagnostics.any { it.severity.name == "ERROR" }) "failure" else "success"

    private fun outcomeClass(outcome: String): String = when (outcome) {
        "success" -> "success"
        "unstable" -> "unstable"
        else -> "failure"
    }
}

/**
 * Renders a telemetry export as one JSON object per line. Line-delimited
 * JSON is the whole choice: a collector or agent can tail the stream, and a
 * truncated last line is dropped by the reader without losing the lines
 * before it.
 *
 * **Every line names the trace it belongs to.** A reader that receives one
 * line —the last one, a line filtered out of a stream— must be able to say
 * which run it belongs to without consulting the header that came before
 * it, and the trace id is what says it. The header repeats the run id and
 * the trace id; the lines that follow repeat the trace id.
 */
object RunTelemetryReport {

    fun jsonLines(trace: TelemetryTrace): List<String> = listOf(
        "{\"schema\":\"$TELEMETRY_SCHEMA\",\"runId\":\"${escape(trace.runId)}\",\"traceId\":\"${escape(trace.traceId)}\"}",
    ) + trace.signals.map { signal ->
        val traceField = "\"traceId\":\"${escape(trace.traceId)}\","
        when (signal) {
            is TelemetrySignal.SpanStarted ->
                "{\"kind\":\"span_started\",$traceField\"name\":\"${escape(signal.name)}\",\"spanId\":\"${signal.spanId}\"," +
                    "\"parentSpanId\":${signal.parentSpanId?.let { "\"$it\"" } ?: "null"}," +
                    "\"startedAtMs\":${signal.startedAtMs},\"attributes\":${attributes(signal.attributes)}}"

            is TelemetrySignal.SpanEnded ->
                "{\"kind\":\"span_ended\",$traceField\"name\":\"${escape(signal.name)}\",\"spanId\":\"${signal.spanId}\"," +
                    "\"endedAtMs\":${signal.endedAtMs},\"status\":\"${escape(signal.status)}\"}"

            is TelemetrySignal.Counter ->
                "{\"kind\":\"counter\",$traceField\"name\":\"${escape(signal.name)}\",\"value\":${signal.value}," +
                    "\"dimensions\":${dimensions(signal.dimensions)}}"

            is TelemetrySignal.Histogram ->
                "{\"kind\":\"histogram\",$traceField\"name\":\"${escape(signal.name)}\",\"milliseconds\":${signal.milliseconds}," +
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
