package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.LiveOutputPresentation
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import java.io.OutputStreamWriter
import java.io.PrintStream

/**
 * `pipelinek observe <run>` — one public verb over both durable authorities.
 *
 * ## Why one verb and not two
 *
 * `events` and `console` already exist and both stay. But a caller who wants "this run's failures
 * and the stderr around them" currently has to know that these are two stores, two cursor
 * vocabularies and two flags. The query that expresses that is ONE question, and the answer should
 * not require knowing which half of the system holds it.
 *
 * What this verb does NOT do is merge the two planes. `Event.sequence` and `OutputFrame.ordinal` have
 * no total order, so [ObservationView.FULL] stays refused here exactly as it is in `run`. `observe`
 * reads both lanes; it does not claim they interleaved.
 *
 * ## Why it reuses instead of reimplementing
 *
 * Every decision this verb could have made twice is made once, elsewhere:
 *
 * - option meaning — [CliParser.parseObservation], sharing `applyOption` with `run`;
 * - event presentation — [RunObservationOutput.encode];
 * - output presentation — [LiveOutputPresentation];
 * - selection — the one compiled query the parser handed over.
 *
 * A second implementation of any of those would be a second authority, and the two would drift the
 * first time one of them gained a case.
 */
object MainObserveCli {

    const val USAGE: String =
        "Usage: pipelinek observe <runId> [--control-root <dir>] [--db <path>] " +
            "[--view normal|events|console|quiet] [--format text|jsonl|json] " +
            "[--stage S] [--step S] [--kind K] [--channel stdout|stderr] [--grep TEXT]"

    /**
     * Frames read per round trip.
     *
     * A read-side bound, not a result size: the loop continues while the index says more frames
     * exist, so this trades memory for round trips and never changes what is eventually read.
     */
    private const val FRAMES_PER_READ = 256

    fun main(args: Array<String>): Int {
        // The run id comes FIRST and the options after it, so there is never a question about
        // whether `--channel stderr` consumed the run id or the flag was missing its value.
        val runId = args.firstOrNull()?.takeIf { !it.startsWith("--") }
        if (runId == null) {
            System.err.println("observe: a run id is required.")
            System.err.println(USAGE)
            return 2
        }
        val optionArgs = args.drop(if (args.first() == runId) 1 else 0)

        val parsed = when (val result = CliParser.parseObservation(optionArgs.toTypedArray())) {
            is ObservationParseResult.Parsed -> result
            is ObservationParseResult.Rejected -> {
                System.err.println("observe: ${result.error}")
                System.err.println(USAGE)
                return 2
            }
        }

        val lanes = try {
            ComposeLanes.forRequest(parsed)
        } catch (e: java.io.IOException) {
            System.err.println("observe: cannot open a durable store: ${e.message}")
            return 2
        }

        val outcome = replay(lanes, runId, parsed, System.out, System.err)
        lanes.close()
        return when (outcome) {
            is ObserveOutcome.Replayed -> 0
            is ObserveOutcome.Refused -> {
                System.err.println("observe: ${outcome.reason}")
                2
            }
        }
    }

    /**
     * Replays what is durable, into [out], with nothing interpreted.
     *
     * Returns a typed outcome rather than an exit code so the decision is testable without a process,
     * and takes both lanes as a port so a test can answer for both without a filesystem.
     */
    fun replay(
        lanes: ObserveLanes,
        runId: String,
        parsed: ObservationParseResult.Parsed,
        out: PrintStream,
        diagnostics: PrintStream,
    ): ObserveOutcome = when (parsed.view) {
        ObservationView.CONSOLE -> replayOutput(lanes, runId, parsed, out, diagnostics)
        ObservationView.NORMAL, ObservationView.EVENTS, ObservationView.QUIET ->
            replayEvents(lanes, runId, parsed, out)
        // The parser refuses it for this verb. Present anyway, because an unreachable branch in an
        // ADT is where a new view would quietly get added.
        ObservationView.FULL -> ObserveOutcome.Refused(ObserveRefusal.FullViewUnavailable)
    }

    private fun replayEvents(
        lanes: ObserveLanes,
        runId: String,
        parsed: ObservationParseResult.Parsed,
        out: PrintStream,
    ): ObserveOutcome {
        if (!lanes.hasEventStore) return ObserveOutcome.Refused(ObserveRefusal.NoDurableEventStore)
        val events = lanes.eventsOf(runId)
        out.print(RunObservationOutput.encode(events.toList(), parsed.view, parsed.format, parsed.compiled))
        out.flush()
        return ObserveOutcome.Replayed
    }

    private fun replayOutput(
        lanes: ObserveLanes,
        runId: String,
        parsed: ObservationParseResult.Parsed,
        out: PrintStream,
        diagnostics: PrintStream,
    ): ObserveOutcome {
        if (!lanes.hasOutputPlane) return ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane)

        // Wrapped, never closed: `documentFor` and the replay both write to the SAME writer, and
        // closing this would close stdout.
        val presentation = LiveOutputPresentation(parsed.format, PrintStream(out), diagnostics)
        val selected = mutableListOf<dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output>()
        var afterOrdinal = -1L
        while (true) {
            when (val read = lanes.outputOf(runId, afterOrdinal, FRAMES_PER_READ)) {
                null -> return ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane)
                is ObservationOutputRead.Refused ->
                    return ObserveOutcome.Refused(ObserveRefusal.PlaneRefused(read.reason))

                is ObservationOutputRead.Page -> {
                    val page = read.page
                    page.records.filter { parsed.compiled.accepts(it) }.forEach { record ->
                        if (parsed.format == ObservationFormat.JSON) {
                            // An array is only complete when its `]` arrives, so records are held
                            // rather than streamed; that is what `documentFor` is for.
                            selected += record
                        } else {
                            presentation.render(record)?.let { out.print(it) }
                        }
                    }
                    afterOrdinal = page.lastOrdinal
                    if (!page.moreFrames) break
                }
            }
        }
        presentation.documentFor(selected, OutputStreamWriter(out, Charsets.UTF_8))
        out.flush()
        return ObserveOutcome.Replayed
    }
}

/** The two durable authorities, as ports. Narrow so a test can answer for both without disk. */
interface ObserveLanes : AutoCloseable {

    /**
     * False when the event lane has no durable store at all.
     *
     * Distinct from "the store has no rows": a run executed in memory never wrote events, and a
     * reader that answered both with an empty list would be reporting an absence as an empty truth.
     */
    val hasEventStore: Boolean

    /** False when there is no control root, which is where the Output Plane lives. */
    val hasOutputPlane: Boolean

    fun eventsOf(runId: String): Sequence<DomainEvent>

    /**
     * `null` means THIS LANE DOES NOT EXIST — no `--control-root` was given, so there is no plane
     * to ask. It never means "nothing there": a store that exists and cannot answer returns
     * [ObservationOutputRead.Refused], and a store with nothing in it returns an empty page. Those
     * three are different answers and collapsing them is how a hole becomes a clean finish.
     */
    fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead?

    override fun close() = Unit
}

/** What a replay decided. Replayed carries no count: the writer already knows what it wrote. */
sealed interface ObserveOutcome {
    data object Replayed : ObserveOutcome
    data class Refused(val reason: ObserveRefusal) : ObserveOutcome
}

sealed interface ObserveRefusal {
    data object NoDurableEventStore : ObserveRefusal {
        override fun toString(): String =
            "there is no durable event store for this run, so its lifecycle cannot be replayed. " +
                "Runs executed without --db keep their events in memory and they are gone when " +
                "the process ends. Process output IS still available with --control-root."
    }

    data object NoOutputPlane : ObserveRefusal {
        override fun toString(): String =
            "there is no Output Plane to read, because --control-root was not given. A run with " +
                "no --control-root writes its plane into a private temporary directory whose name " +
                "only that run knows, so naming it is the only way to reach it."
    }

    data object FullViewUnavailable : ObserveRefusal {
        override fun toString(): String =
            "the full view combines events and process output, and those two have no total order. " +
                "Ask for one lane at a time rather than a document that would have to invent the " +
                "interleaving."
    }

    /** The store said "I cannot", which is not the same as "there is nothing". */
    data class PlaneRefused(val reason: OutputRefusal) : ObserveRefusal {
        override fun toString(): String =
            "the Output Plane refused to answer: $reason. The bytes are unread, not absent."
    }
}

/**
 * Builds the lanes a request implies.
 *
 * Split out of [MainObserveCli] so the I/O of opening stores stays in one place and [replay] stays
 * free of it.
 */
internal object ComposeLanes {

    fun forRequest(parsed: ObservationParseResult.Parsed): ObserveLanes {
        val eventStore = parsed.dbPath?.let { dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore(it) }
        val store = parsed.controlRoot?.let {
            dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider.storeFor(
                java.nio.file.Paths.get(it),
            )
        }
        // The store is all three authorities at once: the frame index says WHICH ranges were
        // published, the read port supplies the bytes, the tail port says whether more can arrive.
        val reader = store?.let { FrameIndexedObservationOutputReader(it.frameIndex(), it, it) }
        return object : ObserveLanes {
            override val hasEventStore: Boolean get() = eventStore != null
            override val hasOutputPlane: Boolean get() = reader != null
            override fun eventsOf(runId: String): Sequence<DomainEvent> =
                eventStore?.eventsFor(runId) ?: emptySequence()
            override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int) =
                reader?.readOutput(runId, afterOrdinal, frameLimit)
            override fun close() {
                eventStore?.close()
            }
        }
    }
}

private fun ObservationParseResult.Parsed.dbPathOrNull(): String? = null
private fun ObservationParseResult.Parsed.controlRootOrNull(): String? = this.controlRoot