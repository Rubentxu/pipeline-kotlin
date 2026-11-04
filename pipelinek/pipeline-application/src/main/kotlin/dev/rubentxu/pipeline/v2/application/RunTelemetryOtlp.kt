package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.TelemetrySource
import dev.rubentxu.pipeline.v2.runtime.telemetry.TELEMETRY_SCHEMA
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetrySignal
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryTrace
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.Data
import io.opentelemetry.sdk.metrics.data.HistogramData
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.data.SumData
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import java.io.OutputStream

/**
 * Renders a telemetry export in the OpenTelemetry wire format.
 *
 * The adapter is **optional and pure**: it maps an export that already
 * exists onto the shape a collector understands, and it changes nothing
 * about the run that produced it. It has no transport — no HTTP client, no
 * endpoint, no retry — because a transport would be a second way for
 * telemetry to leave the process, and the export already has one: the
 * consumer reads it.
 *
 * ## WHY THE WRITING IS DELEGATED, NOT REWRITTEN
 *
 * This product has two producers of telemetry: the live one, which publishes
 * an execution as it happens through the OpenTelemetry SDK, and this one,
 * which publishes a finished run read back from the journal. Two producers
 * of one format are two chances to disagree about it, and the disagreement
 * is invisible until a collector refuses a document.
 *
 * So the writing is delegated to the upstream marshalers, the same ones the
 * live exporter uses. The hand-written encoder that used to live here is
 * gone: it was a second reading of the format, and a document it wrote was
 * a document only this product could read — including its hybrid shape, with
 * `resourceSpans` and `resourceMetrics` in ONE object, which is not an OTLP
 * request at all. What this object writes now is two standard documents: one
 * for the spans, one for the metrics, each a request a collector accepts.
 *
 * ## WHY ONLY COMPLETED SPANS TRAVEL
 *
 * The OpenTelemetry span is a FINISHED measurement: it has a start and an
 * end, and its duration is the difference. A span whose interval contradicts
 * itself — a terminal that arrived before its own start — has no honest
 * duration, and a span that never ended has no end at all. Neither can be
 * written as a finished span, and writing one with the end omitted would
 * publish a measurement that does not exist: a collector would read an open
 * span that no process will ever close, and would wait for it forever.
 *
 * The information is not lost. The projection counts a contradictory interval
 * (`pipelinek.invalid_interval_total`) and the plain report still declares
 * every span that was opened, so the anomaly is visible where it belongs:
 * in the report that is allowed to say "this happened, and this is
 * incomplete". The OTLP view carries only what is true.
 *
 * ## WHY THE WRITER STAYS SMALL
 *
 * A history can be large, and the memory an export costs must not grow with
 * it. Nothing here materializes the whole export: the spans are produced one
 * at a time and travel in bounded batches, each batch one OTLP request, each
 * request one line. The metrics are FOLDED as they are read — the counters
 * into a sum per dimension set, the durations into a bucket count per
 * dimension set — so the samples are counted and then forgotten, and the
 * only thing that grows with the history is the set of DISTINCT dimension
 * sets, which the product's own cardinality policy already caps.
 *
 * The one structure that does grow with the history is the set of spans
 * still in doubt — the ones that opened and have not closed yet. It holds
 * one reference per OPEN span and nothing more, so a long run that closes
 * each span before opening the next costs the writer the same as a short
 * one.
 *
 * The writer never closes the stream it was handed: the caller opened it,
 * the caller closes it. Closing a stream the writer does not own would be a
 * way to break a consumer that wants to keep writing to it.
 */
object RunTelemetryOtlp {

    /**
     * The scope every span and metric of this product travels under. The
     * same scope the live exporter uses, so a backend that receives both
     * producers sees one product's telemetry and not two products'.
     */
    private const val SCOPE_NAME: String = "dev.rubentxu.pipeline.v2"

    /**
     * How many spans travel in one request. The number is a memory bound and
     * nothing else: a request is a valid OTLP document whatever it holds, so
     * the smallest bound that keeps the request count low is the one worth
     * paying for.
     */
    private const val SPANS_PER_REQUEST: Int = 256

    /** How many metrics travel in one request. Same reason as the spans. */
    private const val METRICS_PER_REQUEST: Int = 256

    /** The nanoseconds a millisecond is worth, for the format's own clock. */
    private const val NANOS_PER_MILLI: Long = 1_000_000L

    /** The line separator between two requests of the stream. */
    private val NEWLINE: ByteArray = byteArrayOf('\n'.code.toByte())

    /** The shape a trace id must have for the format to accept it: 32 hex digits. */
    private val TRACE_ID: Regex = Regex("[0-9a-f]{32}")

    /** The shape a span id must have for the format to accept it: 16 hex digits. */
    private val SPAN_ID: Regex = Regex("[0-9a-f]{16}")

    /**
     * The histogram's bucket boundaries, in milliseconds. Nine bounds, ten
     * buckets: the last bucket is open-ended, and the whole set spans the
     * range a pipeline step can plausibly take, from a sub-millisecond
     * call to half a minute.
     */
    private val DURATION_BOUNDS_MS: List<Long> = listOf(1L, 5L, 10L, 50L, 100L, 500L, 1_000L, 5_000L, 30_000L)

    /**
     * The resource the spans and the metrics belong to.
     *
     * It names the product, the schema the export speaks, and the producer
     * that published it. It does NOT name the run: a resource is the
     * description of the thing that produced the telemetry, and a run is
     * not that — a resource that changes once per run describes a different
     * service on every run, and a backend that also receives the live
     * exporter's telemetry would see two products where there is one. The
     * run is named where it belongs: by the trace id every span carries, and
     * by the `run.id` attribute the run's own span carries.
     */
    private val RESOURCE: Resource = Resource.create(
        Attributes.builder()
            .put("service.name", "pipelinek")
            .put("telemetry.schema", TELEMETRY_SCHEMA)
            .put(TelemetrySource.KEY, TelemetrySource.HISTORICAL)
            .build(),
    )

    /** The scope of every span and metric of this product. */
    private val SCOPE: InstrumentationScopeInfo = InstrumentationScopeInfo.create(SCOPE_NAME)

    /**
     * The spans of [trace] that COMPLETED, in the shape the format reads.
     *
     * A span with no end, or with an end that precedes its own start, is
     * not a measurement and does not become one here.
     *
     * The result is LAZY, and it is lazy in the order that keeps the writer
     * small: a span is published the moment its ending arrives, and the
     * opening it is measured against is forgotten at that same moment. The
     * only thing the writer holds is the set of spans still in doubt — the
     * ones that opened and have not closed yet — so its memory is
     * proportional to how DEEP the run is, and not to how long its history
     * is.
     *
     * The order of the spans is therefore the order in which they closed.
     * That is not a loss: a span is a fact about an interval that is over,
     * and the interval is over when it closes.
     */
    fun spans(trace: TelemetryTrace): Sequence<SpanData> = sequence {
        val inDoubt = LinkedHashMap<String, TelemetrySignal.SpanStarted>()
        for (signal in trace.signals) {
            when (signal) {
                is TelemetrySignal.SpanStarted -> {
                    // A repeated opening is not a new fact: the span keeps
                    // the opening it was first measured against.
                    if (signal.spanId !in inDoubt) inDoubt[signal.spanId] = signal
                }

                is TelemetrySignal.SpanEnded -> {
                    val start = inDoubt.remove(signal.spanId) ?: continue
                    if (signal.endedAtMs < start.startedAtMs) continue
                    yield(HistoricalSpan(start, signal, trace.traceId))
                }

                else -> Unit
            }
        }
    }

    /**
     * The metrics of [trace], in the shape the format reads.
     *
     * The counters are monotonic sums, one data point per distinct
     * dimension set, with the values summed; the durations are histograms,
     * with the count of samples, their sum, and the bucket counts over the
     * bounds of this object.
     *
     * The signals are folded as they are read: a sample is counted and then
     * forgotten, so the memory this costs is the set of DISTINCT dimension
     * sets and not the number of samples.
     */
    fun metrics(trace: TelemetryTrace): List<MetricData> {
        val window = window(trace)
        val counters = LinkedHashMap<MetricKey, Long>()
        val histograms = LinkedHashMap<MetricKey, DurationTally>()
        for (signal in trace.signals) {
            when (signal) {
                is TelemetrySignal.Counter -> {
                    val key = MetricKey(signal.name, signal.dimensions)
                    counters[key] = (counters[key] ?: 0L) + signal.value
                }

                is TelemetrySignal.Histogram -> {
                    val key = MetricKey(signal.name, signal.dimensions)
                    (histograms[key] ?: DurationTally().also { histograms[key] = it }).add(signal.milliseconds)
                }

                else -> Unit
            }
        }
        return counters.map { (key, value) ->
            counterMetric(key.name, key.dimensions, value, window)
        } + histograms.map { (key, tally) ->
            histogramMetric(key.name, key.dimensions, tally, window)
        }
    }

    /**
     * Writes the spans of [trace] as OTLP requests, one per line.
     *
     * Only the spans that COMPLETED travel: a span with no end, or with an
     * end that precedes its own start, is not a measurement and does not
     * become one here.
     */
    fun writeSpans(trace: TelemetryTrace, out: OutputStream) {
        val batch = ArrayList<SpanData>(SPANS_PER_REQUEST)
        var written = false
        for (span in spans(trace)) {
            batch.add(span)
            if (batch.size == SPANS_PER_REQUEST) {
                writeSpanRequest(batch, out)
                written = true
                batch.clear()
            }
        }
        // An export with no completed span still writes a request: an empty
        // request says "this export had nothing completed in it", which is a
        // fact, while no request at all says nothing at all.
        if (batch.isNotEmpty() || !written) writeSpanRequest(batch, out)
    }

    /**
     * Writes the metrics of [trace] as OTLP requests, one per line.
     */
    fun writeMetrics(trace: TelemetryTrace, out: OutputStream) {
        val batch = ArrayList<MetricData>(METRICS_PER_REQUEST)
        var written = false
        for (metric in metrics(trace)) {
            batch.add(metric)
            if (batch.size == METRICS_PER_REQUEST) {
                writeMetricRequest(batch, out)
                written = true
                batch.clear()
            }
        }
        if (batch.isNotEmpty() || !written) writeMetricRequest(batch, out)
    }

    private fun writeSpanRequest(spans: List<SpanData>, out: OutputStream) {
        TraceRequestMarshaler.create(spans).writeJsonTo(out)
        out.write(NEWLINE)
    }

    private fun writeMetricRequest(metrics: List<MetricData>, out: OutputStream) {
        MetricsRequestMarshaler.create(metrics).writeJsonTo(out)
        out.write(NEWLINE)
    }

    private fun counterMetric(
        name: String,
        dimensions: Map<String, String>,
        value: Long,
        window: Window,
    ): MetricData {
        val point = LongPointData.create(window.startNanos, window.endNanos, dimensionAttributes(dimensions), value)
        val sum = SumData.createLongSumData(true, AggregationTemporality.CUMULATIVE, listOf(point))
        return HistoricalMetric(name, "1", MetricDataType.LONG_SUM, sum)
    }

    private fun histogramMetric(
        name: String,
        dimensions: Map<String, String>,
        tally: DurationTally,
        window: Window,
    ): MetricData {
        val point = HistogramPointData.create(
            window.startNanos,
            window.endNanos,
            dimensionAttributes(dimensions),
            tally.sum,
            false,
            0.0,
            false,
            0.0,
            DURATION_BOUNDS_MS.map { it.toDouble() },
            tally.bucketCounts(),
        )
        val histogram = HistogramData.create(AggregationTemporality.CUMULATIVE, listOf(point))
        return HistoricalMetric(name, "ms", MetricDataType.HISTOGRAM, histogram)
    }

    /**
     * The window the metrics belong to: the first and the last moment the
     * export's spans name. A metric without a window is a metric nobody can
     * place on a timeline, and the export's own timestamps are the only
     * window it can honestly claim.
     */
    private fun window(trace: TelemetryTrace): Window {
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (signal in trace.signals) {
            val moment = when (signal) {
                is TelemetrySignal.SpanStarted -> signal.startedAtMs
                is TelemetrySignal.SpanEnded -> signal.endedAtMs
                else -> continue
            }
            if (moment < first) first = moment
            if (moment > last) last = moment
        }
        if (first == Long.MAX_VALUE) return Window(0L, 0L)
        return Window(first * NANOS_PER_MILLI, last * NANOS_PER_MILLI)
    }

    /** The span of the export, in the shape the format reads. */
    private class HistoricalSpan(
        private val start: TelemetrySignal.SpanStarted,
        private val end: TelemetrySignal.SpanEnded,
        private val traceId: String,
    ) : SpanData {
        override fun getName(): String = start.name

        override fun getKind(): SpanKind = SpanKind.INTERNAL

        override fun getSpanContext(): SpanContext = spanContext(traceId, start.spanId)

        override fun getParentSpanContext(): SpanContext =
            start.parentSpanId?.let { spanContext(traceId, it) } ?: SpanContext.getInvalid()

        /**
         * A run that succeeded is OK, anything else —including one that was
         * merely unstable— is an error. The word itself travels in the
         * message, so the coarse code never hides the difference.
         */
        override fun getStatus(): StatusData =
            StatusData.create(if (end.status == "success") StatusCode.OK else StatusCode.ERROR, end.status)

        override fun getStartEpochNanos(): Long = start.startedAtMs * NANOS_PER_MILLI

        override fun getAttributes(): Attributes = spanAttributes(start.attributes)

        override fun getEvents(): List<EventData> = emptyList()

        override fun getLinks(): List<LinkData> = emptyList()

        override fun getEndEpochNanos(): Long = end.endedAtMs * NANOS_PER_MILLI

        override fun hasEnded(): Boolean = true

        override fun getTotalRecordedEvents(): Int = 0

        override fun getTotalRecordedLinks(): Int = 0

        override fun getTotalAttributeCount(): Int = getAttributes().size()

        /**
         * The interface still demands the library that the scope replaced,
         * and the type it asks for is deprecated upstream. The scope is
         * overridden below, so nothing reads this value: it is here because
         * the interface does not allow a null, and refusing to compile would
         * be a worse answer than implementing a method nobody calls.
         */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getInstrumentationLibraryInfo(): InstrumentationLibraryInfo =
            InstrumentationLibraryInfo.create(SCOPE_NAME, null)

        override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = SCOPE

        override fun getResource(): Resource = RESOURCE
    }

    /** The metric of the export, in the shape the format reads. */
    private class HistoricalMetric(
        private val name: String,
        private val unit: String,
        private val type: MetricDataType,
        private val data: Data<*>,
    ) : MetricData {
        override fun getResource(): Resource = RESOURCE

        override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = SCOPE

        override fun getName(): String = name

        override fun getDescription(): String = ""

        override fun getUnit(): String = unit

        override fun getType(): MetricDataType = type

        override fun getData(): Data<*> = data
    }

    /**
     * The running tally of the durations of one dimension set: how many
     * samples, how long they were together, and how many fell in each
     * bucket. It keeps the COUNTS and not the samples, so a million
     * durations cost the same as ten.
     */
    private class DurationTally {
        private val counts = LongArray(DURATION_BOUNDS_MS.size + 1)
        private var samples: Long = 0
        private var millis: Double = 0.0

        fun add(milliseconds: Long) {
            val index = DURATION_BOUNDS_MS.indexOfFirst { milliseconds <= it }
            counts[if (index < 0) DURATION_BOUNDS_MS.size else index]++
            samples++
            millis += milliseconds
        }

        /** The number of samples the tally saw. */
        val count: Long get() = samples

        /** How long the samples were, together. */
        val sum: Double get() = millis

        /** How many samples fell in each bucket. */
        fun bucketCounts(): List<Long> = counts.toList()
    }

    /** The window a metric belongs to, in the format's own clock. */
    private data class Window(val startNanos: Long, val endNanos: Long)

    /** The name and the dimensions that tell one metric from another. */
    private data class MetricKey(val name: String, val dimensions: Map<String, String>)

    /** The attributes a span carries, in the format's own value types. */
    private fun spanAttributes(attributes: List<TelemetryAttribute>): Attributes {
        val builder = Attributes.builder()
        for (attribute in attributes) {
            when (attribute) {
                is TelemetryAttribute.Text -> builder.put(attribute.key, attribute.value)
                is TelemetryAttribute.Count -> builder.put(attribute.key, attribute.value)
                is TelemetryAttribute.Flag -> builder.put(attribute.key, attribute.value)
            }
        }
        return builder.build()
    }

    /** The attributes a metric carries: the dimensions, as text. */
    private fun dimensionAttributes(dimensions: Map<String, String>): Attributes {
        val builder = Attributes.builder()
        dimensions.forEach { (key, value) -> builder.put(key, value) }
        return builder.build()
    }

    /**
     * The context of one span: the trace it belongs to and its own id.
     *
     * The ids are the export's own hexadecimal identifiers, and the format
     * reads them as bytes. A context that cannot be built would publish a
     * span with an identifier of zeros — an identifier that belongs to no
     * span — so a malformed id yields the invalid context instead, and the
     * document says nothing about that span.
     */
    private fun spanContext(traceId: String, spanId: String): SpanContext =
        if (TRACE_ID.matches(traceId) && SPAN_ID.matches(spanId)) {
            SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())
        } else {
            SpanContext.getInvalid()
        }
}
