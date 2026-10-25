package dev.rubentxu.pipeline.v2.runtime.telemetry

/**
 * The schema label every exported telemetry line carries. A consumer that
 * does not recognize it must drop the line rather than guess at its shape.
 */
const val TELEMETRY_SCHEMA: String = "pipelinek.telemetry.v1"

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
 * telemetry fail-open for the pipeline and fail-closed for the data, and the
 * export outcome is reported, never swallowed and never thrown.
 */
sealed interface TelemetryExport {
    data object Exported : TelemetryExport
    data class Dropped(val reason: String) : TelemetryExport
    data object Disabled : TelemetryExport
}

/**
 * Which attributes and dimension keys a signal may carry, and how much of
 * each. The allowlist is the whole security posture: a key that is not
 * listed does not travel, however innocent it looks, because "not listed" is
 * the only state that stays safe when someone adds a payload-bearing key
 * tomorrow. Cardinality is capped the same way — a dimension with unbounded
 * values is a leak with a nicer name.
 */
data class TelemetryPolicy(
    val allowedAttributeKeys: Set<String>,
    val allowedDimensionKeys: Set<String>,
    val maxTextLength: Int = 64,
    val maxAttributesPerSignal: Int = 8,
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

private fun limitDimensions(dimensions: Map<String, String>, policy: TelemetryPolicy): Map<String, String> =
    dimensions.filterKeys { it in policy.allowedDimensionKeys }
