package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.output.OutputChannel

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
 * ## `channel`, and the KDoc that used to be wrong about it
 *
 * This dimension was absent until OBS-D, and its absence was justified here with a claim that has
 * since become false:
 *
 * > ~~`channel` is absent. The durable transcript merges stdout and stderr and no byte range
 * > carries its origin, so a channel filter would be a declared dimension with no producer.~~
 *
 * OBS-C2.3 removed the merge: `DurableShellExecutor` runs two independent pumps, and
 * [ObservationRecord.Output.channel] rides on the frame of every committed range, durably. The
 * producer exists, so the honest options were to deliver the dimension or to delete a false claim —
 * and a query that cannot select stderr is not a smaller feature, it is a wrong one.
 *
 * ## A dimension a record cannot satisfy EXCLUDES it
 *
 * That is the AND rule stated literally, and `channel` makes it visible where `stage` already had
 * it: a `StageStarted` carries no channel, so `--channel stderr` excludes it rather than ignoring the
 * dimension. `--channel stderr` therefore means "stderr bytes", not "stderr bytes plus the lifecycle
 * that happened to run alongside them". A reader wanting both asks for the view that carries both.
 *
 * ## What the text dimension reaches
 *
 * Text for script messages (`[EchoOutputCaptured]`) and text for committed process bytes
 * ([ObservationRecord.Output.text]). It reaches process output because those bytes are durable and
 * progressively readable, which is what OBS-B and OBS-C established — before that, `--grep` could
 * only ever have meant script messages, and saying otherwise would have been a dead semantic
 * parameter.
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
    val outcomes: Set<ObservedOutcome> = emptySet(),
    val channels: Set<OutputChannel> = emptySet(),
    val lines: LineSelector = LineSelector.All,
) {
    /** True when no dimension is set — the identity query. */
    val isIdentity: Boolean
        get() = stageNames.isEmpty() &&
            stepNames.isEmpty() &&
            eventKinds.isEmpty() &&
            outcomes.isEmpty() &&
            channels.isEmpty() &&
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
    fun accepts(record: ObservationRecord): Boolean {
        // A record that is not an event carries no stage, step, kind or outcome, and each of those
        // dimensions excludes it rather than ignoring it. Written once, as the shape of the record
        // rather than four copies of the same null check.
        val event = (record as? ObservationRecord.Event)?.event

        if (query.stageNames.isNotEmpty()) {
            val stage = event?.let { stageCarriedBy(it) } ?: return false
            if (stage !in query.stageNames) return false
        }
        if (query.stepNames.isNotEmpty()) {
            val step = event?.let { stepCarriedBy(it) } ?: return false
            if (step !in query.stepNames) return false
        }
        if (query.eventKinds.isNotEmpty()) {
            val kind = event?.kind ?: return false
            if (kind !in query.eventKinds) return false
        }
        if (query.outcomes.isNotEmpty()) {
            val outcome = event?.let { outcomeOf(it) } ?: return false
            if (outcome !in query.outcomes) return false
        }
        if (query.channels.isNotEmpty()) {
            val channel = channelCarriedBy(record) ?: return false
            if (channel !in query.channels) return false
        }

        // Text constrains only records that carry text; see the class KDoc.
        val text = textCarriedBy(record)
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
 * Applies [compiled] to [records], preserving order.
 *
 * Selection never reorders: a reader's line 3 is the same line whether or not
 * they filtered, and any future interleaving rule stays a property of the view,
 * not of the query.
 */
fun selectRecords(
    records: List<ObservationRecord>,
    compiled: CompiledObservationQuery,
): List<ObservationRecord> = records.filter(compiled::accepts)

/**
 * [selectRecords] over events only.
 *
 * Kept because most callers hold a list of semantic facts and have no output to select over; it is
 * not a second selection rule, it is the same compiled query applied to the event lane.
 */
fun selectObservations(
    events: List<DomainEvent>,
    compiled: CompiledObservationQuery,
): List<DomainEvent> = selectRecords(events.map { ObservationRecord.Event(it) }, compiled)
    .map { (it as ObservationRecord.Event).event }