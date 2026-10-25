package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.TELEMETRY_SCHEMA
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetrySignal
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Renders a telemetry export in the OpenTelemetry shape.
 *
 * The adapter is **optional and pure**: it maps an export that already
 * exists onto the shape a collector understands, and it changes nothing
 * about the run that produced it. It has no transport — no HTTP client, no
 * endpoint, no retry — because a transport would be a second way for
 * telemetry to leave the process, and the export already has one: the
 * consumer reads it.
 *
 * The mapping is faithful to the export rather than richer than it:
 *
 *  - **Spans** keep the identity they already have. A span that never
 *    ended travels without `endTimeUnixNano`, which is the shape
 *    OpenTelemetry gives a span still running — the same shape the export
 *    uses for a span whose timestamps contradict each other.
 *  - **Counters** become a monotonic sum, one data point per distinct
 *    dimension set, with the values summed.
 *  - **Durations** become a histogram: the count of samples, their sum,
 *    and the bucket counts over the bounds below. The bounds are the
 *    adapter's own encoding, not a policy — the policy decides what may
 *    travel, this decides how a travelled duration is counted.
 *
 * The resource carries the run's identity, so a collector that receives
 * the document can tell which run it describes without the caller having
 * to say so.
 */
object RunTelemetryOtlp {

    /**
     * The histogram's bucket boundaries, in milliseconds. Nine bounds, ten
     * buckets: the last bucket is open-ended, and the whole set spans the
     * range a pipeline step can plausibly take, from a sub-millisecond
     * call to half a minute.
     */
    private val DURATION_BOUNDS_MS: List<Long> = listOf(1L, 5L, 10L, 50L, 100L, 500L, 1_000L, 5_000L, 30_000L)

    private const val SCOPE_NAME: String = "dev.rubentxu.pipeline.v2"

    /** The OpenTelemetry status codes the export's statuses map onto. */
    private const val STATUS_OK: Int = 1
    private const val STATUS_ERROR: Int = 2

    /**
     * The document, as one line of JSON.
     *
     * One line, because the export it comes from is line-delimited and a
     * consumer that reads either shape should not have to learn two.
     */
    fun document(trace: TelemetryTrace): String {
        val root = buildJsonObject {
            putJsonArray("resourceSpans") {
                add(
                    buildJsonObject {
                        put("resource", resource(trace))
                        putJsonArray("scopeSpans") {
                            add(
                                buildJsonObject {
                                    put("scope", scope())
                                    putJsonArray("spans") {
                                        spans(trace).forEach { add(it) }
                                    }
                                },
                            )
                        }
                    },
                )
            }
            putJsonArray("resourceMetrics") {
                add(
                    buildJsonObject {
                        put("resource", resource(trace))
                        putJsonArray("scopeMetrics") {
                            add(
                                buildJsonObject {
                                    put("scope", scope())
                                    putJsonArray("metrics") {
                                        metrics(trace).forEach { add(it) }
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
        return Json { prettyPrint = false }.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * The resource the spans and the metrics belong to: the product, the
     * run, and the schema of the export.
     */
    private fun resource(trace: TelemetryTrace): JsonObject = buildJsonObject {
        putJsonArray("attributes") {
            add(attribute("service.name", stringValue("pipelinek")))
            add(attribute("service.instance.id", stringValue(trace.runId)))
            add(attribute("telemetry.schema", stringValue(TELEMETRY_SCHEMA)))
        }
    }

    private fun scope(): JsonObject = buildJsonObject {
        put("name", SCOPE_NAME)
    }

    /**
     * The spans, in the order the export lists them, each with the end it
     * found — or without one, when the export says the span did not end.
     */
    private fun spans(trace: TelemetryTrace): List<JsonObject> {
        val ends = trace.signals
            .filterIsInstance<TelemetrySignal.SpanEnded>()
            .associateBy { it.spanId }
        return trace.signals
            .filterIsInstance<TelemetrySignal.SpanStarted>()
            .map { start ->
                val end = ends[start.spanId]
                buildJsonObject {
                    put("traceId", trace.traceId)
                    put("spanId", start.spanId)
                    start.parentSpanId?.let { put("parentSpanId", it) }
                    put("name", start.name)
                    put("startTimeUnixNano", nanos(start.startedAtMs))
                    end?.let { put("endTimeUnixNano", nanos(it.endedAtMs)) }
                    putJsonArray("attributes") {
                        start.attributes.forEach { add(otlpAttribute(it)) }
                    }
                    end?.let {
                        putJsonObject("status") {
                            put("code", statusCode(it.status))
                            put("message", it.status)
                        }
                    }
                }
            }
    }

    /**
     * The metrics: one sum per counter name and dimension set, one
     * histogram per duration name and dimension set.
     */
    private fun metrics(trace: TelemetryTrace): List<JsonObject> {
        val window = window(trace)
        val counters = trace.signals
            .filterIsInstance<TelemetrySignal.Counter>()
            .groupBy { it.name to it.dimensions }
        val histograms = trace.signals
            .filterIsInstance<TelemetrySignal.Histogram>()
            .groupBy { it.name to it.dimensions }

        val counterMetrics = counters.map { (key, group) ->
            counterMetric(key.first, key.second, group.sumOf { it.value }, window)
        }
        val histogramMetrics = histograms.map { (key, group) ->
            histogramMetric(key.first, key.second, group.map { it.milliseconds }, window)
        }
        return counterMetrics + histogramMetrics
    }

    private fun counterMetric(
        name: String,
        dimensions: Map<String, String>,
        value: Long,
        window: Pair<String, String>,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("unit", "1")
        putJsonObject("sum") {
            put("aggregationTemporality", 2)
            put("isMonotonic", true)
            putJsonArray("dataPoints") {
                add(
                    buildJsonObject {
                        putJsonArray("attributes") {
                            dimensions.forEach { (key, dimension) ->
                                add(attribute(key, stringValue(dimension)))
                            }
                        }
                        put("startTimeUnixNano", window.first)
                        put("timeUnixNano", window.second)
                        put("value", value.toString())
                    },
                )
            }
        }
    }

    private fun histogramMetric(
        name: String,
        dimensions: Map<String, String>,
        samples: List<Long>,
        window: Pair<String, String>,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("unit", "ms")
        putJsonObject("histogram") {
            put("aggregationTemporality", 2)
            putJsonArray("dataPoints") {
                add(
                    buildJsonObject {
                        putJsonArray("attributes") {
                            dimensions.forEach { (key, dimension) ->
                                add(attribute(key, stringValue(dimension)))
                            }
                        }
                        put("startTimeUnixNano", window.first)
                        put("timeUnixNano", window.second)
                        put("count", samples.size.toString())
                        put("sum", samples.sum().toString())
                        putJsonArray("bucketCounts") {
                            bucketCounts(samples).forEach { add(it.toString()) }
                        }
                        putJsonArray("explicitBounds") {
                            DURATION_BOUNDS_MS.forEach { add(it) }
                        }
                    },
                )
            }
        }
    }

    /**
     * The samples counted into the histogram's buckets: the first bound the
     * sample does not exceed, and the open bucket when it exceeds them all.
     */
    private fun bucketCounts(samples: List<Long>): List<Long> {
        val counts = LongArray(DURATION_BOUNDS_MS.size + 1)
        for (sample in samples) {
            val index = DURATION_BOUNDS_MS.indexOfFirst { sample <= it }
            counts[if (index < 0) DURATION_BOUNDS_MS.size else index]++
        }
        return counts.toList()
    }

    /**
     * The window the metrics belong to: the first and the last moment the
     * export's spans name. A metric without a window is a metric nobody can
     * place on a timeline, and the export's own timestamps are the only
     * window it can honestly claim.
     */
    private fun window(trace: TelemetryTrace): Pair<String, String> {
        val timestamps = trace.signals.flatMap { signal ->
            when (signal) {
                is TelemetrySignal.SpanStarted -> listOf(signal.startedAtMs)
                is TelemetrySignal.SpanEnded -> listOf(signal.endedAtMs)
                else -> emptyList()
            }
        }
        val first = timestamps.minOrNull() ?: 0L
        val last = timestamps.maxOrNull() ?: 0L
        return nanos(first) to nanos(last)
    }

    private fun otlpAttribute(attribute: TelemetryAttribute): JsonObject = buildJsonObject {
        put("key", attribute.key)
        put(
            "value",
            when (attribute) {
                is TelemetryAttribute.Text -> stringValue(attribute.value)
                is TelemetryAttribute.Count -> intValue(attribute.value)
                is TelemetryAttribute.Flag -> boolValue(attribute.value)
            },
        )
    }

    private fun attribute(key: String, value: JsonObject): JsonObject = buildJsonObject {
        put("key", key)
        put("value", value)
    }

    private fun stringValue(value: String): JsonObject = buildJsonObject { put("stringValue", value) }

    private fun intValue(value: Long): JsonObject = buildJsonObject { put("intValue", value.toString()) }

    private fun boolValue(value: Boolean): JsonObject = buildJsonObject { put("boolValue", value) }

    /**
     * A status word mapped onto the two codes the format has: a run that
     * succeeded is OK, anything else —including one that was merely
     * unstable— is an error. The word itself travels in the message, so
     * the coarse code never hides the difference.
     */
    private fun statusCode(status: String): Int = if (status == "success") STATUS_OK else STATUS_ERROR

    /**
     * Milliseconds as the nanoseconds the format asks for, as a string: the
     * format encodes its 64-bit integers as strings, and a number would be
     * read as a double and lose precision in the last digits.
     */
    private fun nanos(millis: Long): String = (millis * 1_000_000L).toString()
}
