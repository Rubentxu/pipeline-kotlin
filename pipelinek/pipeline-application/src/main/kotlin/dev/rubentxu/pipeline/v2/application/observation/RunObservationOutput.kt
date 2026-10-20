package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import java.io.StringWriter
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
 *
 * ## Why [Stream] is the only implementation
 *
 * Human presentation is STATEFUL. `HumanConsoleRenderer` learns each stage's name
 * from its `StageStarted` event and needs that name to label the Step lines that
 * arrive later, so "the line for this event" is not a function of the event
 * alone — it is a function of the event and everything that came before it.
 *
 * That makes a per-call render wrong in two ways, and both were live defects:
 *
 * - a FOLLOWER re-rendered each page with a fresh fold, so a Step committed in a
 *   later page printed `[step: stage 0]` because the stage that named it had been
 *   committed in an earlier one and the scope had been thrown away;
 * - the BATCH path folded only the events the query had already selected, so a
 *   query that filtered out a `StageStarted` degraded the presentation of the
 *   Steps it kept — the filter leaked into the presentation of the survivors,
 *   which is what [ConsolePrintingEventSink] was written to prevent on the live
 *   path.
 *
 * So both paths go through [Stream]: fold every event in, print the ones the
 * query accepts. A replayed run, a live run and a followed run now print the same
 * bytes for the same query, and there is one place where that is decided.
 */
object RunObservationOutput {

    /**
     * Whether [format] can be produced one record at a time.
     *
     * TEXT and JSONL can: every record stands alone, so emitting the next one
     * cannot retroactively invalidate what came before. JSON cannot — it is a
     * DOCUMENT, complete only when its `]` arrives, and a reader that stops early
     * would leave an unterminated array on stdout. One array per page would be
     * worse still: several documents concatenated with nothing between them,
     * which is not the document anybody asked to parse.
     *
     * This is the single place that decides it. The parser refuses the
     * combination before any store is opened, and a follower refuses it again at
     * its own boundary, so neither depends on the other having been obeyed.
     */
    fun isIncremental(format: ObservationFormat): Boolean = when (format) {
        ObservationFormat.TEXT, ObservationFormat.JSON_LINES -> true
        ObservationFormat.JSON -> false
    }

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
        budget: RecordBudget = RecordBudget.All,
    ): String {
        if (!isIncremental(format)) {
            return JsonEventLog.encode(budget.bounded(selectObservations(events, query)))
        }
        val sink = StringWriter()
        Stream(view, format, query, budget, sink).write(events.asSequence())
        return sink.toString()
    }

    /**
     * Streaming encoding for the durable path, which must not materialise the
     * whole run as one string (WU-RP-044 / M5 RSS debt).
     *
     * A document format is encoded here exactly as [encode] encodes it, because
     * this function is called ONCE for that path: the whole history is written in
     * a single pass and the caller has nothing more to add. A caller that wrote it
     * per page would get one document per page, which is why [isIncremental] exists
     * and why a follower refuses a non-incremental format instead of getting here.
     */
    fun writeTo(
        events: Sequence<DomainEvent>,
        out: Writer,
        view: ObservationView,
        format: ObservationFormat,
        query: CompiledObservationQuery,
        budget: RecordBudget = RecordBudget.All,
    ) {
        if (!isIncremental(format)) {
            val selected = events.asRecords().filter(query::accepts).toEvents().toList()
            JsonEventLog.encodeTo(budget.bounded(selected).asSequence(), out)
            return
        }
        Stream(view, format, query, budget, out).write(events)
    }

    /**
     * One presentation, kept open across as many rounds as the caller has.
     *
     * Stateful by construction: the console scope travels inside, so a step
     * committed now is still labelled by a stage committed earlier. Not
     * thread-safe, and it does not need to be — a single reader owns its stream,
     * exactly as [ConsolePrintingEventSink] owns its own.
     */
    class Stream internal constructor(
        view: ObservationView,
        private val format: ObservationFormat,
        private val query: CompiledObservationQuery,
        private val budget: RecordBudget,
        private val out: Writer,
    ) {
        private val console: HumanConsoleRenderer.ConsoleStream? =
            if (format == ObservationFormat.TEXT) HumanConsoleRenderer.stream(view) else null

        /**
         * How many selected records this stream has already emitted.
         *
         * Lives here rather than in the caller's loop because this is the only place that knows what
         * a "record" was: a line the VIEW excluded was never a record, and spending the budget on it
         * would make `--limit` a second filter hiding behind the first.
         */
        private var emitted = 0

        init {
            // A document has no incremental form. Reachable only by a caller that skipped both
            // refusals, and a programmer defect deserves a loud failure rather than a second
            // document that looks like a continuation of the first.
            check(isIncremental(format)) {
                "$format is a document, not a stream; refusing to emit it incrementally"
            }
        }

        fun write(events: Sequence<DomainEvent>) {
            // Folded and counted separately: an event that is not going to be printed still has to
            // reach the console renderer, because that is what carries the stage scope the LATER
            // lines resolve against. Stopping before the fold would trade a presentation state for a
            // saved cycle, and the state is the whole reason the renderer exists.
            for (event in events) {
                when (format) {
                    ObservationFormat.TEXT -> {
                        val line = console!!.accept(event)
                        if (line != null && accepts(event)) {
                            out.write(line)
                            out.write("\n")
                        }
                    }

                    ObservationFormat.JSON_LINES -> if (accepts(event)) {
                        out.write(JsonEventLog.encodeOne(event))
                        out.write("\n")
                    }

                    // Unreachable: `init` refused this format.
                    ObservationFormat.JSON -> Unit
                }
            }
        }

        /**
         * Whether this stream has emitted everything its budget paid for.
         *
         * Asked by a FOLLOWER that has to decide whether to keep reading. It cannot answer by itself:
         * the budget is spent inside the emitter, because the emitter is the only place that knows
         * which events were records and which were folded-and-dropped.
         */
        val isSpent: Boolean
            get() = !budget.allows(emitted)

        /** Whether [event] is selected AND still within budget. The one place either test is made. */
        private fun accepts(event: DomainEvent): Boolean {
            if (!query.accepts(ObservationRecord.Event(event))) return false
            if (!budget.allows(emitted)) return false
            emitted++
            return true
        }
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
