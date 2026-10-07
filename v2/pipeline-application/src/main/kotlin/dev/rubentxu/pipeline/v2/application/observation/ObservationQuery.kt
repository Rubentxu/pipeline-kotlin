package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished

/**
 * Read-side query over a run's observations.
 *
 * ## Combining rule
 *
 * **AND across dimensions, OR within one dimension** — the rule ADR-0088 fixes.
 * Three `--kind` values union; `--stage X --kind Y` intersects. Nothing else is
 * offered, and in particular there is no expression language: an operator that
 * cannot be expressed as "union inside a dimension, intersect across" is not
 * expressible at all.
 *
 * ## Dimensions that are NOT here, and why
 *
 * `channel` is absent. The durable transcript merges stdout and stderr
 * (`ShExecution.kt:101-102`) and no byte range carries its origin, so a channel
 * filter would be a declared dimension with no producer — the "dead semantic
 * parameter" the constitution forbids. It returns as soon as provenance is
 * captured durably.
 *
 * ## What the text dimension actually reaches today
 *
 * [textCarriedBy] returns text for script messages ONLY. Process stdout and
 * stderr live in the Output Plane, not in the event stream, so `--grep` cannot
 * reach them yet. That is a real limitation, not a design choice, and it is
 * why grep is stated as filtering script messages rather than "console output".
 * It becomes whole-transcript once WU-LPR-042 ingests incrementally.
 *
 * ## Records with no text are not constrained by the text dimension
 *
 * `--grep` discriminates among records that HAVE text. A `StageStarted` carries
 * none, so the text dimension does not apply to it and it survives. The
 * alternative — treating "no text" as "text did not match" — would silently
 * delete every structural line from `--stage build --grep ERROR`, which is not
 * what any caller means. This is a stated rule, not a silent default.
 */
data class ObservationQuery(
    val stageNames: Set<String> = emptySet(),
    val stepNames: Set<String> = emptySet(),
    val eventKinds: Set<String> = emptySet(),
    val outcomes: Set<String> = emptySet(),
    val lines: LineSelector = LineSelector.All,
) {
    /** True when no dimension is set — the identity query. */
    val isIdentity: Boolean
        get() = stageNames.isEmpty() &&
            stepNames.isEmpty() &&
            eventKinds.isEmpty() &&
            outcomes.isEmpty() &&
            lines == LineSelector.All
}

/**
 * Text carried by an observation record, or `null` when the record carries none.
 *
 * Deliberately a small function rather than a property on every event: only one
 * event type models human script output today. Process transcript is a separate
 * plane (see the class KDoc).
 */
fun textCarriedBy(event: DomainEvent): String? = when (event) {
    is EchoOutputCaptured -> event.content
    else -> null
}

/** Outcome carried by a record, or `null` when the record reports none. */
fun outcomeCarriedBy(event: DomainEvent): String? = when (event) {
    is RunFinished -> event.outcome
    is StageFinished -> event.outcome
    is StageSkipped -> "SKIPPED"
    is StageMarkedUnstable -> "UNSTABLE"
    is StepFailed -> "FAILURE"
    else -> null
}

private fun stageCarriedBy(event: DomainEvent): String? = when (event) {
    is dev.rubentxu.pipeline.v2.events.StageStarted -> event.stageName
    is StageFinished -> event.stageName
    is StageSkipped -> event.stageName
    is StageMarkedUnstable -> event.stageName
    else -> null
}

private fun stepCarriedBy(event: DomainEvent): String? = when (event) {
    is dev.rubentxu.pipeline.v2.events.StepStarted -> event.stepName
    is StepFinished -> event.stepName
    is StepFailed -> event.stepName
    else -> null
}

/** A compiled, ready-to-run query. Built once, applied many times. */
data class CompiledObservationQuery(
    private val query: ObservationQuery,
    private val lines: CompiledLineSelector,
) {
    fun accepts(event: DomainEvent): Boolean {
        if (query.stageNames.isNotEmpty()) {
            val stage = stageCarriedBy(event) ?: return false
            if (stage !in query.stageNames) return false
        }
        if (query.stepNames.isNotEmpty()) {
            val step = stepCarriedBy(event) ?: return false
            if (step !in query.stepNames) return false
        }
        if (query.eventKinds.isNotEmpty() && event.kind !in query.eventKinds) return false

        if (query.outcomes.isNotEmpty()) {
            val outcome = outcomeCarriedBy(event) ?: return false
            if (outcome !in query.outcomes) return false
        }

        // Text constrains only records that carry text; see the class KDoc.
        val text = textCarriedBy(event)
        if (text != null && !lines.accepts(text)) return false

        return true
    }
}

/**
 * Compiles [query] once, failing typed when the text dimension is unusable.
 *
 * Pure. A bad pattern or an empty selector list is returned as a value so the
 * CLI can reject it before any effect.
 */
fun compileQuery(query: ObservationQuery): SelectorCompileResult<CompiledObservationQuery> =
    when (val compiledLines = compileLineSelector(query.lines)) {
        is SelectorCompileResult.Invalid -> compiledLines
        is SelectorCompileResult.Ok -> SelectorCompileResult.Ok(CompiledObservationQuery(query, compiledLines.value))
    }

/**
 * Applies [compiled] to [events], preserving order.
 *
 * Selection never reorders: a reader's line 3 is the same line whether or not
 * they filtered, and any future interleaving rule stays a property of the view,
 * not of the query.
 */
fun selectObservations(
    events: List<DomainEvent>,
    compiled: CompiledObservationQuery,
): List<DomainEvent> = events.filter(compiled::accepts)