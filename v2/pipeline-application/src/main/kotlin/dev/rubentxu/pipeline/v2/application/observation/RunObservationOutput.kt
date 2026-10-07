package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import java.io.Writer

/**
 * Chooses how a run's observations reach stdout.
 *
 * ## The law this encodes
 *
 * ADR-0088: **view** selects what a reader sees, **format** encodes it, and
 * neither ever changes execution semantics or persistence. This object is the
 * only place that turns the two into bytes, so a caller cannot accidentally
 * emit a different wire form for the same run.
 *
 * The machine formats are continuations, not replacements:
 *
 * - [ObservationFormat.JSON] reproduces the array `run` has always emitted,
 *   byte for byte, so every existing consumer keeps working. That is why the
 *   historical output is now opt-in rather than removed — a consumer that
 *   discovers it by accident is exactly what ADR-0088 is fixing.
 * - [ObservationFormat.JSON_LINES] is one document per line, for following a run
 *   without buffering it. Both machine forms draw their bytes from the same
 *   `JsonEventLog` writer, so the two documents cannot drift.
 *
 * [ObservationFormat.TEXT] is the default and the human interface.
 */
object RunObservationOutput {

    /**
     * Whole-run encoding. Pure; returns the exact text to write to stdout.
     *
     * Takes an ALREADY-COMPILED query. The CLI compiles it at parse time and
     * refuses an unusable one before any effect, so nothing here has to handle
     * a bad pattern — and the type makes it impossible to pass an uncompiled
     * query by accident.
     */
    fun encode(
        events: List<DomainEvent>,
        view: ObservationView,
        format: ObservationFormat,
        query: CompiledObservationQuery,
    ): String {
        val selected = selectObservations(events, query)
        return when (format) {
            ObservationFormat.TEXT -> HumanConsoleRenderer.render(selected, view)
            ObservationFormat.JSON -> JsonEventLog.encode(selected)
            ObservationFormat.JSON_LINES -> jsonLines(selected)
        }
    }

    /**
     * Streaming encoding for the durable path, which must not materialise the
     * whole run as one string (WU-RP-044 / M5 RSS debt).
     *
     * Selection stays lazy for the machine formats: events are dropped as they
     * stream rather than being accumulated and filtered at the end.
     */
    fun writeTo(
        events: Sequence<DomainEvent>,
        out: Writer,
        view: ObservationView,
        format: ObservationFormat,
        query: CompiledObservationQuery,
    ) {
        when (format) {
            ObservationFormat.TEXT -> {
                // The human renderer folds over the whole view, so it needs the
                // sequence. Explicit: making it streaming belongs to WU-LPR-042,
                // which moves to incremental ingestion anyway.
                out.write(HumanConsoleRenderer.render(selectObservations(events.toList(), query), view))
            }

            ObservationFormat.JSON ->
                JsonEventLog.encodeTo(events.asRecords().filter(query::accepts).toEvents(), out)

            ObservationFormat.JSON_LINES ->
                events.asRecords().filter(query::accepts).toEvents().forEach { event ->
                    out.write(JsonEventLog.encodeOne(event))
                    out.write("\n")
                }
        }
    }

    private fun jsonLines(events: List<DomainEvent>): String =
        if (events.isEmpty()) "" else events.joinToString(separator = "\n", postfix = "\n") {
            JsonEventLog.encodeOne(it)
        }

    /**
     * The compiled query speaks records; these two hops carry an event-only stream across that
     * boundary without teaching [CompiledObservationQuery] a second entry point that could drift from
     * the first. Streaming stays lazy: the query runs per element, not over a materialised list.
     */
    private fun Sequence<DomainEvent>.asRecords(): Sequence<ObservationRecord> =
        map { ObservationRecord.Event(it) }

    private fun Sequence<ObservationRecord>.toEvents(): Sequence<DomainEvent> =
        map { (it as ObservationRecord.Event).event }
}