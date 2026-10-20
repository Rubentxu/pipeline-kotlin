package dev.rubentxu.pipeline.v2.events.identity

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WU-LPR-051 — Agent-efficient event inspection.
 *
 * The [EventViewProjection] (WU-LPR-050) emits the full envelope; agents
 * that need a narrower view (a single field, a context window, the tail of
 * a long run) consume [EventInspection]. The inspection is a pure
 * transformation over the projected lines: no query language, no
 * arbitrary predicates — agent callers pass typed values (`EventField`
 * whitelist + `EventContextWindow`) and get a typed result.
 *
 * ## What this is NOT
 *
 *  - NOT a query language. There is no `WHERE` clause, no expression
 *    evaluator, no regex matcher. The contract is: agents express
 *    intent with closed typed ADTs.
 *  - NOT a substitute for [EventViewProjection]. Inspection sits on top
 *    of projection: pick fields from an already-projected envelope.
 *  - NOT an effect. Inspection is pure; the CLI is the effectful boundary.
 */
object EventInspection {

    /**
     * Project the [envelopes] into a field-narrowed view: each envelope is
     * rendered as a one-line summary carrying only the whitelisted fields.
     *
     * @param envelopes The query result, in sequence order.
     * @param fields The whitelisted fields to include in each line.
     * @return One line per envelope, in the same order as the input.
     */
    fun projectFields(
        envelopes: List<PipelineEventEnvelope>,
        fields: List<EventField>,
    ): List<String> {
        if (fields.isEmpty()) return emptyList()
        val header = fields.joinToString(separator = " ") { it.header }
        val lines = envelopes.map { envelope ->
            fields.joinToString(separator = " ") { it.extract(envelope) }
        }
        return listOf(header) + lines
    }

    /**
     * Project a context window around the matched envelopes: for every
     * envelope whose kind matches [kindFilter], emit the matched envelope
     * plus up to [before] envelopes preceding it and up to [after]
     * envelopes following it. Duplicates are removed (a window that
     * overlaps with the previous window's tail collapses).
     *
     * The output preserves the original envelope order; the matched
     * envelope is the center of each window.
     *
     * @param envelopes The query result, in sequence order.
     * @param kindFilter The kind to match (exact string equality).
     * @param before Number of preceding envelopes to include (>= 0).
     * @param after Number of following envelopes to include (>= 0).
     * @return The deduped context-window lines.
     */
    fun contextWindow(
        envelopes: List<PipelineEventEnvelope>,
        kindFilter: String,
        before: Int,
        after: Int,
    ): List<PipelineEventEnvelope> {
        require(before >= 0) { "before must be >= 0, got $before" }
        require(after >= 0) { "after must be >= 0, got $after" }
        if (envelopes.isEmpty()) return emptyList()

        val selected = mutableSetOf<Int>()
        envelopes.forEachIndexed { idx, env ->
            if (env.kind == kindFilter) {
                val from = (idx - before).coerceAtLeast(0)
                val to = (idx + after).coerceAtMost(envelopes.size - 1)
                for (i in from..to) selected.add(i)
            }
        }
        return selected.sorted().map { envelopes[it] }
    }

    /**
     * Project the tail of the run: the last [count] envelopes (or fewer if
     * the run has fewer). Preserves sequence order; oldest first.
     */
    fun tail(envelopes: List<PipelineEventEnvelope>, count: Int): List<PipelineEventEnvelope> {
        require(count >= 0) { "count must be >= 0, got $count" }
        if (envelopes.isEmpty() || count == 0) return emptyList()
        return if (envelopes.size <= count) envelopes else envelopes.takeLast(count)
    }

    /**
     * Project a follow cursor: given a list of envelopes and the last seen
     * sequence, return only the envelopes whose sequence is strictly
     * greater than [afterSequence]. The caller uses the returned
     * envelopes' last sequence as the next cursor token.
     */
    fun follow(
        envelopes: List<PipelineEventEnvelope>,
        afterSequence: Long,
    ): List<PipelineEventEnvelope> = envelopes.filter { it.sequence > afterSequence }
}

/**
 * Whitelisted envelope field. Each case knows how to extract a single
 * string value from a [PipelineEventEnvelope]. Closed ADT — adding a new
 * field forces every consumer of [EventInspection.projectFields] to be
 * revisited (compile-time exhaustiveness check).
 */
sealed interface EventField {
    val header: String

    fun extract(envelope: PipelineEventEnvelope): String

    /** Event sequence (store-assigned, monotonic per run). */
    data object Sequence : EventField {
        override val header: String = "seq"
        override fun extract(envelope: PipelineEventEnvelope): String =
            envelope.sequence.toString()
    }

    /** Event kind (e.g. `StepStarted`, `RunFinished`). */
    data object Kind : EventField {
        override val header: String = "kind"
        override fun extract(envelope: PipelineEventEnvelope): String =
            envelope.kind
    }

    /** Event id (`evt-...`). */
    data object EventId : EventField {
        override val header: String = "eventId"
        override fun extract(envelope: PipelineEventEnvelope): String =
            envelope.eventRef.id.value
    }

    /** Subject canonical text (`v1:kind:seg/seg/...`). */
    data object Subject : EventField {
        override val header: String = "subject"
        override fun extract(envelope: PipelineEventEnvelope): String =
            envelope.subject.canonicalText()
    }

    /** Source canonical text (where the event was emitted from). */
    data object Source : EventField {
        override val header: String = "source"
        override fun extract(envelope: PipelineEventEnvelope): String =
            envelope.eventRef.source.canonicalText()
    }
}
