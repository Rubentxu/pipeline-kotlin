package dev.rubentxu.pipeline.v2.runtime.telemetry

import dev.rubentxu.pipeline.v2.domain.digest.Sha256

/**
 * The schema label every exported telemetry line carries. A consumer that
 * does not recognize it must drop the line rather than guess at its shape.
 *
 * **Version 2** differs from version 1 in three ways, all of them visible:
 * every exported line names the trace it belongs to; a dimension value that
 * does not fit the budget is replaced by [DIMENSION_OVERFLOW] instead of
 * travelling whole; and a dimension value longer than the text budget is
 * shortened. A consumer of version 1 that reads a version 2 line sees the
 * trace id as a new field and may read `__overflow__` as a value it has
 * never seen — which is why the label changed rather than the shape
 * pretending to be the same.
 */
const val TELEMETRY_SCHEMA: String = "pipelinek.telemetry.v2"

/**
 * The value that replaces a dimension value whose key already carried more
 * distinct values than the policy admits.
 *
 * It is a marker, not a value: a consumer that sees it knows that the export
 * collapsed several values into one, and the marker is exported as a constant
 * so it can compare against it rather than guess at the spelling. The
 * collapse is visible on purpose — an export that silently dropped the
 * surplus would be indistinguishable from one where the surplus never
 * existed.
 */
const val DIMENSION_OVERFLOW: String = "__overflow__"

/**
 * One attribute a signal may carry. Attributes are identified,
 * allowlist-governed values — never free-form maps, because a map cannot be
 * redacted and a map with a budget can still hide a secret in a key nobody
 * reviews.
 */
sealed interface TelemetryAttribute {
    val key: String

    data class Text(override val key: String, val value: String) : TelemetryAttribute
    data class Count(override val key: String, val value: Long) : TelemetryAttribute
    data class Flag(override val key: String, val value: Boolean) : TelemetryAttribute
}

/**
 * What telemetry may say. Four shapes, and the boundary they share: a signal
 * carries timing, a count or a bucket — never payload. No script text, no
 * command, no transcript: those live in the output plane and the journal,
 * and duplicating them here would create a second place a secret could hide.
 */
sealed interface TelemetrySignal {
    val name: String

    data class SpanStarted(
        override val name: String,
        val spanId: String,
        val parentSpanId: String?,
        val startedAtMs: Long,
        val attributes: List<TelemetryAttribute>,
    ) : TelemetrySignal

    data class SpanEnded(
        override val name: String,
        val spanId: String,
        val endedAtMs: Long,
        val status: String,
    ) : TelemetrySignal

    data class Counter(
        override val name: String,
        val value: Long,
        val dimensions: Map<String, String>,
    ) : TelemetrySignal

    data class Histogram(
        override val name: String,
        val milliseconds: Long,
        val dimensions: Map<String, String>,
    ) : TelemetrySignal
}

/**
 * What an exporter did with the signals it was handed. Not a run decision:
 * telemetry fail-open for the pipeline and fail-closed for the data, and
 * the export outcome is reported, never swallowed and never thrown.
 */
sealed interface TelemetryExport {
    data object Exported : TelemetryExport
    data class Dropped(val reason: String) : TelemetryExport
    data object Disabled : TelemetryExport
}

/**
 * One export: the run it describes, the trace that groups its signals, and
 * the signals themselves.
 *
 * The trace id is a **field**, not a computation left to the reader. Before
 * this carrier existed the projection computed a trace id and then dropped
 * it, so nothing outside the process could tell which run a line belonged
 * to; a consumer holding two exports had no way to group them beyond the run
 * id, which the export did carry. Carrying the trace id as data makes the
 * grouping a fact of the export instead of a re-derivation every consumer
 * would have to repeat — and repeat wrongly.
 */
data class TelemetryTrace(
    val runId: String,
    val traceId: String,
    val signals: List<TelemetrySignal>,
) {
    companion object {
        /**
         * The trace id of a run: a digest of the run id.
         *
         * It is correlatable by anyone holding the journal, and never an
         * identity of its own — two runs are two traces because two run ids
         * are two runs. It lives here, in the published module, so a
         * consumer that wants to correlate its own signals with the
         * product's derives the same value the product does, instead of
         * guessing a second derivation that would not match.
         */
        fun traceIdOf(runId: String): String =
            Sha256.ofText("pipelinek-telemetry/run/$runId").take(32)
    }
}

/**
 * Which attributes and dimension keys a signal may carry, and how much of
 * each. The allowlist is the whole security posture: a key that is not
 * listed does not travel, however innocent it looks, because "not listed" is
 * the only state that stays safe when someone adds a payload-bearing key
 * tomorrow. Cardinality is capped the same way — a dimension with unbounded
 * values is a leak with a nicer name.
 *
 * The cardinality budget is [maxDistinctValuesPerDimension], and it counts
 * the overflow marker among the values it admits: a key that exceeds the
 * budget travels at most that many **distinct** values, one of which may be
 * [DIMENSION_OVERFLOW].
 */
data class TelemetryPolicy(
    val allowedAttributeKeys: Set<String>,
    val allowedDimensionKeys: Set<String>,
    val maxTextLength: Int = 64,
    val maxAttributesPerSignal: Int = 8,
    val maxDistinctValuesPerDimension: Int = 16,
) {
    companion object {
        /**
         * The shipped policy: durable identities and coarse classes only.
         * Deliberately small, and deliberately not extensible by callers —
         * widening it is a product decision, not a configuration one.
         */
        val Default: TelemetryPolicy = TelemetryPolicy(
            allowedAttributeKeys = setOf(
                "run.id",
                "stage.index",
                "step.index",
                "compile.outcome",
                "failure.class",
            ),
            allowedDimensionKeys = setOf(
                "operation_kind",
                "status",
                "failure_class",
            ),
        )
    }
}

/**
 * Apply the policy to a signal's free parts. Pure, and fail-closed in the
 * direction that matters: a text attribute whose key is not allowed is
 * dropped, not truncated into something that looks allowed; a value that is
 * too long is shortened to the budget with a marker, because length is not a
 * secret but bulk is a cost; and when trimming would leave nothing readable,
 * the attribute goes. Dimensions follow the same rule key-wise and, beyond
 * the per-signal budget, the surplus is dropped — never the whole signal,
 * which would hide that the signal existed.
 *
 * A dimension **value** is shortened by the same budget as an attribute
 * value: the value is text, and text that is too long is bulk whether it
 * sits in an attribute or in a dimension.
 */
fun redactAndLimit(signal: TelemetrySignal, policy: TelemetryPolicy): TelemetrySignal = when (signal) {
    is TelemetrySignal.SpanStarted -> signal.copy(
        attributes = signal.attributes
            .filter { it.key in policy.allowedAttributeKeys }
            .take(policy.maxAttributesPerSignal)
            .mapNotNull { attribute -> trim(attribute, policy) },
    )

    is TelemetrySignal.SpanEnded -> signal
    is TelemetrySignal.Counter -> signal.copy(dimensions = limitDimensions(signal.dimensions, policy))
    is TelemetrySignal.Histogram -> signal.copy(dimensions = limitDimensions(signal.dimensions, policy))
}

/**
 * Apply the policy to a whole export.
 *
 * This is the same rule as [redactAndLimit] at a wider scope, not a second
 * rule: the per-signal treatment runs first, and what this adds is the one
 * constraint a single signal cannot see — **how many distinct values a
 * dimension key may carry across the whole export**.
 *
 * A signal-level budget cannot bound cardinality, because the leak a
 * dimension produces is not one long value but many values: a key whose
 * values are drawn from an open set travels more of it with every run, and
 * the export's size stops being a function of its policy. The cap therefore
 * counts distinct values per key, over the export, and replaces the surplus
 * with [DIMENSION_OVERFLOW] — visibly, because an export that hid the
 * collapse would look exactly like one where the surplus never existed.
 */
fun redactAndLimit(trace: TelemetryTrace, policy: TelemetryPolicy): TelemetryTrace {
    val perSignal = trace.signals.map { redactAndLimit(it, policy) }
    val budget = policy.maxDistinctValuesPerDimension
    if (budget <= 0) {
        // No slot for any value, and none for the overflow marker either:
        // the keys do not travel at all. The signals still travel — dropping
        // them would hide that they existed.
        return trace.copy(signals = perSignal.map { it.withDimensions(emptyMap()) })
    }

    val distinct = distinctValues(perSignal)
    val admitted = distinct.mapValues { (_, values) -> if (values.size > budget) budget - 1 else values.size }
    return trace.copy(signals = perSignal.map { signal -> signal.withDimensions(cappedDimensions(signal, distinct, admitted)) })
}

/**
 * The distinct values of each dimension key, in the order they first appear.
 * The order matters because the budget keeps the **first** values it sees:
 * the export is a function of the run it describes, and the run's order is
 * part of the journal.
 */
private fun distinctValues(signals: List<TelemetrySignal>): Map<String, List<String>> {
    val distinct = LinkedHashMap<String, LinkedHashSet<String>>()
    for (signal in signals) {
        for ((key, value) in signal.dimensionsOrEmpty()) {
            distinct.getOrPut(key) { LinkedHashSet() }.add(value)
        }
    }
    return distinct.mapValues { (_, values) -> values.toList() }
}

/**
 * The dimensions of a signal after the cardinality cap: a value the key
 * still admits travels as it is, and a value the key no longer admits
 * travels as [DIMENSION_OVERFLOW].
 */
private fun cappedDimensions(
    signal: TelemetrySignal,
    distinct: Map<String, List<String>>,
    admitted: Map<String, Int>,
): Map<String, String> = signal.dimensionsOrEmpty().mapValues { (key, value) ->
    val values = distinct[key]
    val kept = admitted[key]
    if (values == null || kept == null || kept >= values.size) {
        value
    } else {
        val index = values.indexOf(value)
        if (index in 0 until kept) value else DIMENSION_OVERFLOW
    }
}

/**
 * The dimensions a signal carries, or an empty map for the shapes that carry
 * none. A signal without dimensions has nothing to cap.
 */
private fun TelemetrySignal.dimensionsOrEmpty(): Map<String, String> = when (this) {
    is TelemetrySignal.Counter -> dimensions
    is TelemetrySignal.Histogram -> dimensions
    else -> emptyMap()
}

/**
 * The same signal with different dimensions. The shapes without dimensions
 * are returned unchanged.
 */
private fun TelemetrySignal.withDimensions(dimensions: Map<String, String>): TelemetrySignal = when (this) {
    is TelemetrySignal.Counter -> copy(dimensions = dimensions)
    is TelemetrySignal.Histogram -> copy(dimensions = dimensions)
    else -> this
}

private fun trim(attribute: TelemetryAttribute, policy: TelemetryPolicy): TelemetryAttribute? = when (attribute) {
    is TelemetryAttribute.Text -> {
        if (attribute.value.length <= policy.maxTextLength) {
            attribute
        } else {
            // Length is not a secret but bulk is a cost; and a trim that
            // leaves nothing readable carries no fact, so the attribute is
            // dropped rather than shipped whole or shipped empty.
            val kept = attribute.value.take(policy.maxTextLength)
            if (kept.isBlank()) null else TelemetryAttribute.Text(attribute.key, kept)
        }
    }

    is TelemetryAttribute.Count -> attribute
    is TelemetryAttribute.Flag -> attribute
}

/**
 * The dimension keys the policy allows, with their values shortened by the
 * text budget. A value whose trim leaves nothing readable takes its key
 * with it: a key with no readable value carries no fact.
 */
private fun limitDimensions(dimensions: Map<String, String>, policy: TelemetryPolicy): Map<String, String> =
    dimensions
        .filterKeys { it in policy.allowedDimensionKeys }
        .mapNotNull { (key, value) ->
            val kept = if (value.length <= policy.maxTextLength) value else value.take(policy.maxTextLength)
            if (kept.isBlank()) null else key to kept
        }
        .toMap()
