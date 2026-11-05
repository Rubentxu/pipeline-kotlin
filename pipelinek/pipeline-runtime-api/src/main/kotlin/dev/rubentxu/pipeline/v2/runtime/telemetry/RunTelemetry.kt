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

    /**
     * A textual value, subject to the policy's text budget.
     *
     * A value longer than the budget is shortened, and one whose shortening
     * leaves nothing readable is dropped rather than exported empty.
     */
    data class Text(override val key: String, val value: String) : TelemetryAttribute

    /**
     * A counted value, carried as an exact integer.
     *
     * The text budget does not touch a count: it is either exact or it does
     * not travel.
     */
    data class Count(override val key: String, val value: Long) : TelemetryAttribute

    /**
     * A value that is either present or absent.
     *
     * It carries no magnitude, so a reader learns the fact and nothing about
     * how much of it there was.
     */
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

    /**
     * A span opened at [startedAtMs], optionally inside a parent span.
     *
     * Its attributes travel only when the policy allows their keys, and only
     * up to the per-signal budget: a span that starts says what it is, not
     * what it happens to carry.
     */
    data class SpanStarted(
        override val name: String,
        val spanId: String,
        val parentSpanId: String?,
        val startedAtMs: Long,
        val attributes: List<TelemetryAttribute>,
    ) : TelemetrySignal

    /**
     * A span closed at [endedAtMs], with a coarse [status] for how it ended.
     *
     * The status is a word, not a payload: it says how the span closed and
     * nothing about what happened inside it.
     */
    data class SpanEnded(
        override val name: String,
        val spanId: String,
        val endedAtMs: Long,
        val status: String,
    ) : TelemetrySignal

    /**
     * A counter reading, tagged with the dimensions it was counted under.
     *
     * The dimensions are the free-form part of a signal, so the policy
     * governs them key-wise and by how many distinct values one key may
     * carry across the whole export.
     */
    data class Counter(
        override val name: String,
        val value: Long,
        val dimensions: Map<String, String>,
    ) : TelemetrySignal

    /**
     * One observed duration in milliseconds, tagged with its dimensions.
     *
     * It is a single sample rather than a distribution: the value is the elapsed
     * time between the start and the end of what was measured, and the dimensions
     * say what it was measured for.
     */
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
 *
 * These three cases are the vocabulary of that outcome rather than a value
 * this product hands over: the runtime reports a failed export as an export
 * metric and a warning on stderr, and the run's result does not change either
 * way.
 */
sealed interface TelemetryExport {
    /**
     * Every signal the exporter was handed was written out.
     *
     * The export holds every signal it was given; what the policy shortened or
     * dropped lives inside a signal, not among them.
     */
    data object Exported : TelemetryExport

    /**
     * The export could not be written, and the reason says why.
     *
     * The failure is reported rather than thrown: telemetry fails open for
     * the pipeline, so a consumer learns that the data is missing instead
     * of the run failing over it.
     */
    data class Dropped(val reason: String) : TelemetryExport

    /**
     * Telemetry is switched off for this run, so there was nothing to
     * export.
     */
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
    /**
     * The derivation every consumer of an export shares.
     *
     * [traceIdOf] is the one place the trace id is computed, so a consumer
     * that correlates its own signals with the product's derives the same
     * value the product does instead of guessing a second derivation that
     * would not match.
     */
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
 *
 * ## What is exported
 *
 * A signal carries timing, a count or a bucket: a span that opened and
 * closed, a counter reading, a duration sample. It never carries payload —
 * no script text, no command, no transcript. Those live in the output plane
 * and in the journal, and a second copy of them here would be a second place
 * a secret could hide. The four shapes and the boundary they share are
 * [TelemetrySignal]'s.
 *
 * ## The vocabulary, and the conventions it speaks
 *
 * The keys that travel are the product's own: `run.id`, `stage.index`,
 * `step.index`, `compile.outcome` and `failure.class` as attributes, and
 * `operation_kind`, `status` and `failure_class` as dimensions.
 * [TelemetryIdentityTable] is where that vocabulary is decided, and the
 * allowlists below are read from it, so the vocabulary of the export and the
 * vocabulary of the product cannot drift apart.
 *
 * The conventions the product speaks are the OpenTelemetry CI/CD ones, pinned
 * at [OTEL_SEMCONV_CICD_VERSION] and declared a release candidate: their
 * names may still move between two versions of the specification, so the pin
 * is part of what the product promises. The pin is read from the product and
 * not from the payload — an exported document states the schema it speaks,
 * and the conventions version is not part of it.
 *
 * ## Which identities travel
 *
 * The run id travels, under `run.id`. The correlation id, the operation id,
 * the attempt id and the fencing token do not travel at all: the correlation
 * id belongs to the invoker, the operation id and the attempt id are
 * idempotency facts, and the fencing token is write authority. None of them
 * may be used for causality, and none of them grants a permission.
 *
 * The historical trace id is a digest of the run id: it groups the signals of
 * one run for anyone holding the journal, and it is not an identity of its
 * own ([TelemetryTrace.traceIdOf]). The live W3C trace id is the only
 * carrier of causality, and a live context refuses to hold the derived one
 * ([LiveTraceContext]).
 *
 * ## The limits
 *
 * A signal carries at most [maxAttributesPerSignal] attributes, a text value
 * is at most [maxTextLength] characters long, and one dimension key may hold
 * at most [maxDistinctValuesPerDimension] distinct values across the whole
 * export. The surplus travels as [DIMENSION_OVERFLOW], visibly: an export
 * that hid the collapse would look exactly like one where the surplus never
 * existed.
 *
 * ## Off, and failed
 *
 * The OpenTelemetry SDK is an optional dependency: without the operator's
 * opt-in no SDK, no exporter and no worker exist, and the run emits no
 * telemetry at all. A finished run can also be projected into the same
 * vocabulary and exported on demand, which is the path that does not need the
 * SDK.
 *
 * An export that cannot be written, or a shutdown that fails, is reported:
 * the runtime records the loss as an export metric and says so on stderr,
 * and the run's result does not change either way. Telemetry fails open for
 * the pipeline and fails closed for the data.
 */
data class TelemetryPolicy(
    val allowedAttributeKeys: Set<String>,
    val allowedDimensionKeys: Set<String>,
    val maxTextLength: Int = 64,
    val maxAttributesPerSignal: Int = 8,
    val maxDistinctValuesPerDimension: Int = 16,
) {
    /**
     * The policy the product ships and the one every consumer starts from.
     *
     * It is deliberately small: widening the shipped default is a product
     * decision, not a configuration one. A caller that needs another key
     * builds its own [TelemetryPolicy] and passes it to the exporter.
     */
    companion object {
        /**
         * The shipped policy: durable identities and coarse classes only.
         * Deliberately small, and deliberately not widened by callers —
         * widening it is a product decision, not a configuration one.
         *
         * The allowlists are READ from the identity table rather than
         * written here, so the vocabulary of the export and the vocabulary
         * of the product are one thing: a key that travels is a key the
         * table names, and a key the table does not name does not travel.
         * The set of keys is unchanged by that move — what changed is
         * where they are decided.
         */
        val Default: TelemetryPolicy = TelemetryPolicy(
            allowedAttributeKeys = TelemetryIdentityTable.Default.allowedAttributeKeys(),
            allowedDimensionKeys = TelemetryIdentityTable.Default.allowedDimensionKeys(),
        )
    }
}

/**
 * Apply the policy to a signal's free parts. Pure, and fail-closed in the
 * direction that matters: a text attribute whose key is not allowed is
 * dropped, not truncated into something that looks allowed; a value that is
 * too long is shortened to the budget, because length is not a secret but bulk
 * is a cost; and when trimming would leave nothing readable, the attribute
 * goes. Dimensions follow the same rule key-wise and, beyond the per-signal
 * budget, the surplus is dropped — never the whole signal, which would hide
 * that the signal existed.
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
    val cap = TelemetryDimensionCap.plan(policy)
    perSignal.forEach { cap.observe(it) }
    return trace.copy(signals = perSignal.map { cap.apply(it) })
}

/**
 * The cardinality decision for one export: how many distinct values one
 * dimension key may carry, and which of them travel as themselves.
 *
 * The cardinality cap is the one rule a single signal cannot decide, because
 * it is not about one signal: it is about how many **distinct** values one
 * key may carry across the whole export. The decision therefore belongs to
 * the export, and this class is that decision — learned by observing the
 * export's signals, and asked about each of them afterwards.
 *
 * ## What it holds
 *
 * Learning it is bounded, and the bound is the point: a key keeps the first
 * [TelemetryPolicy.maxDistinctValuesPerDimension] values it was seen with,
 * and one flag saying whether a value beyond them ever arrived. The keys it
 * learns are the ones [TelemetryPolicy.allowedDimensionKeys] allows, so what
 * it holds is a function of the **policy** and not of the length of the
 * export: an export of ten million signals learns exactly what an export of
 * ten signals learns.
 *
 * ## The order
 *
 * The order matters because the budget keeps the **first** values it sees:
 * the export is a function of the run it describes, and the run's order is
 * part of the journal. A value that appears more than once travels as itself
 * whichever of its appearances comes first.
 *
 * ## The one rule
 *
 * This class is where the rule lives, and both ways of applying it — the
 * export that holds every signal and the export that streams them — go
 * through here. A second reading of the rule would be a second rule, and two
 * rules about the same limit drift apart exactly where nobody looks.
 */
class TelemetryDimensionCap private constructor(
    private val budget: Int,
    private val allowedKeys: Set<String>,
) {

    /** What one key was seen with: the values it may still admit, and whether more came. */
    private class KeyState {

        /** The first values the key was seen with, in the order they appeared. */
        val values = LinkedHashSet<String>()

        /** Whether a value beyond the budget ever arrived. */
        var exceeded = false
    }

    /** What each key was seen with, in the order the keys appeared. */
    private val observed = LinkedHashMap<String, KeyState>()

    /**
     * The number of distinct values this cap is holding.
     *
     * The bound is what makes the cap usable on an export of any size: a key
     * holds at most [TelemetryPolicy.maxDistinctValuesPerDimension] values,
     * and only the keys [TelemetryPolicy.allowedDimensionKeys] allows are
     * held at all, so this number is a function of the policy and never of
     * the length of the export.
     */
    val retainedValues: Int
        get() = observed.values.sumOf { it.values.size }

    /**
     * Learn one signal's dimensions.
     *
     * The signal is expected to be redacted already: the cap counts the
     * values that would travel, and a value the policy drops never travels.
     * A key the policy does not allow is not learned at all — it could not
     * travel, so it cannot spend the budget of the export.
     */
    fun observe(signal: TelemetrySignal) {
        if (budget <= 0) return
        for ((key, value) in signal.dimensionsOrEmpty()) {
            if (key !in allowedKeys) continue
            val state = observed.getOrPut(key) { KeyState() }
            if (value in state.values) continue
            if (state.values.size < budget) {
                state.values.add(value)
            } else {
                // One value too many for the budget: the key has more values
                // than it may carry, and the marker takes a slot of its own.
                state.exceeded = true
            }
        }
    }

    /**
     * The signal with the dimensions this cap admits.
     *
     * A value the key still admits travels as it is, and a value the key no
     * longer admits travels as [DIMENSION_OVERFLOW]. A key the cap never saw
     * travels whole: it was never at risk. With no slot for any value the
     * dimensions do not travel at all — and the signals still do, because
     * dropping them would hide that they existed.
     */
    fun apply(signal: TelemetrySignal): TelemetrySignal {
        if (budget <= 0) return signal.withDimensions(emptyMap())
        val dimensions = signal.dimensionsOrEmpty()
        if (dimensions.isEmpty()) return signal
        return signal.withDimensions(dimensions.mapValues { (key, value) -> travel(key, value) })
    }

    /**
     * The value a key's value travels as.
     *
     * While the key has not exceeded the budget, every value it was seen
     * with still fits: the budget keeps them all. Once it has, the values
     * that came after the ones it keeps travel as the marker.
     */
    private fun travel(key: String, value: String): String {
        val state = observed[key] ?: return value
        if (!state.exceeded) return value
        val index = state.values.indexOf(value)
        return if (index in 0 until (budget - 1)) value else DIMENSION_OVERFLOW
    }

    /**
     * The one way to obtain a cap.
     *
     * A cap is not a thing a caller builds with numbers of its own: it is
     * the decision a policy makes, and the policy is what the caller holds.
     * A cap built any other way could exist without a policy behind it, and
     * then it would decide about a budget nobody published.
     */
    companion object {

        /**
         * A cap that decides under [policy].
         *
         * The cap starts empty: it learns what the export carries as the
         * export is observed, and it answers only about what it has seen.
         */
        fun plan(policy: TelemetryPolicy): TelemetryDimensionCap =
            TelemetryDimensionCap(policy.maxDistinctValuesPerDimension, policy.allowedDimensionKeys)
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
