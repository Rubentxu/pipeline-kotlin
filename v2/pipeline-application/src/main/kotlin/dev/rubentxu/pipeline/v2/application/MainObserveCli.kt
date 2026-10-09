package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.LiveOutputPresentation
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
import dev.rubentxu.pipeline.v2.application.observation.FollowDecision
import dev.rubentxu.pipeline.v2.application.observation.followDecision
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
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
            "[--stage S] [--step S] [--kind K] [--channel stdout|stderr] [--grep TEXT] " +
            "[--limit N] [--tail-bytes N] [--follow]"

    /**
     * Frames read per round trip.
     *
     * A read-side bound, not a result size: the loop continues while the index says more frames
     * exist, so this trades memory for round trips and never changes what is eventually read.
     */
    private const val FRAMES_PER_READ = 256

    /** Rows per event-lane read while following. A read bound, never a result size. */
    private const val EVENTS_PER_READ = 256

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

        val outcome = if (parsed.follow) {
            MainObserveCli.follow(
                lanes,
                runId,
                parsed,
                InterruptFollow { Thread.currentThread().isInterrupted },
                System.out,
                System.err,
            )
        } else {
            replay(lanes, runId, parsed, System.out, System.err)
        }
        lanes.close()
        return when (outcome) {
            is ObserveOutcome.Replayed -> 0
            // Every follow is a success: it returned because the run reached its terminal fact, or
            // because the consumer stopped it. The exit code says the READER succeeded; how the run
            // ended is on the run plane, and putting it in an exit code would make this verb a
            // second authority on the outcome.
            is ObserveOutcome.Followed -> 0
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
        // The STREAMING entry point, not [RunObservationOutput.encode]. `encode` is the same encoder
        // behind a different door, and the difference is memory: it takes a `List` and hands back a
        // `String`, so a replay held the run's whole history twice while it rendered it.
        //
        // `run`'s live path already writes through [RunObservationOutput.writeTo]; this is the replay
        // path doing the same thing, which is what makes `ObsE5ObserveReplayTest`'s OBSERVE-1 claim —
        // "the event lane renders through the SAME encoder `run` uses" — true rather than merely
        // consistent-looking. It was checked by content, and content cannot tell two encoders apart.
        //
        // What this does NOT buy, stated rather than implied: the event sequence is still consumed to
        // the end, because the console renderer folds every event to keep the stage scope that later
        // lines resolve against. This is "replay without two full copies", not "bounded replay".
        val writer = java.io.OutputStreamWriter(out, Charsets.UTF_8)
        RunObservationOutput.writeTo(
            lanes.eventsOf(runId),
            writer,
            parsed.view,
            parsed.format,
            parsed.compiled,
            parsed.budget,
        )
        writer.flush()
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

        val presentation = LiveOutputPresentation(parsed.format, PrintStream(out), diagnostics)
        val selected = mutableListOf<dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output>()
        // The tail is READ ONCE and its records are printed; only its position carries on. Keeping
        // just the position was the first version of this and it printed NOTHING, because every
        // record the tail had already fetched was thrown away and the next read started after it.
        val tail = when (val bytes = parsed.tailBytes) {
            null -> null
            else -> when (val start = lanes.tailOf(runId, bytes)) {
                null -> return ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane)
                is ObservationOutputRead.Refused ->
                    return ObserveOutcome.Refused(ObserveRefusal.PlaneRefused(start.reason))

                is ObservationOutputRead.Page -> start.page
            }
        }
        var afterOrdinal = tail?.lastOrdinal ?: -1L
        // Counted HERE and not inside the presentation because this loop also has to know WHEN the
        // budget ran out, in order to stop reading rather than keep paging a run it will not print.
        var emitted = 0
        // A local function rather than a lambda so the two early exits read as `return` instead of a
        // label the reader has to look up: the record is dropped either because the query rejected
        // it or because the budget is spent, and neither is an error.
        fun print(record: dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output) {
            if (!parsed.compiled.accepts(record)) return
            if (!parsed.budget.allows(emitted)) return
            emitted++
            if (parsed.format == ObservationFormat.JSON) {
                // An array is only complete when its `]` arrives, so records are held rather than
                // streamed; that is what `documentFor` is for.
                selected += record
            } else {
                presentation.render(record)?.let { out.print(it) }
            }
        }
        tail?.records?.forEach { print(it) }
        while (parsed.budget.allows(emitted)) {
            when (val read = lanes.outputOf(runId, afterOrdinal, FRAMES_PER_READ)) {
                null -> return ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane)
                is ObservationOutputRead.Refused ->
                    return ObserveOutcome.Refused(ObserveRefusal.PlaneRefused(read.reason))

                is ObservationOutputRead.Page -> {
                    val page = read.page
                    page.records.forEach { print(it) }
                    afterOrdinal = page.lastOrdinal
                    if (!page.moreFrames) break
                }
            }
        }
        presentation.documentFor(selected, OutputStreamWriter(out, Charsets.UTF_8))
        out.flush()
        return ObserveOutcome.Replayed
    }

    /**
     * Follows ONE lane until it is finished or the consumer stops asking.
     *
     * ## Why one lane, never both
     *
     * Following the event lane and the output lane in one loop would have to emit them in SOME
     * order, and that order is not a fact about the run — it is a fact about when this process
     * happened to poll each store. Emitting them as one stream would be claiming an interleaving,
     * which is the same fabrication that keeps [ObservationView.FULL] refused. So `--follow` follows
     * the lane the chosen view names, and a caller who wants both uses two readers with two cursors.
     *
     * ## How each lane knows it is finished
     *
     * Two different answers, and neither is a tail state:
     *
     * - the event lane ends on `RunFinished` committed in the store — a terminal FACT, not an
     *   inference about how much longer the run might write;
     * - the output lane ends when no frame is pending and every stream it has seen is sealed —
     *   which is [followDecision]'s own question, and which says nothing about success.
     *
     * ## What it polls, stated plainly
     *
     * There is no `EventsCommitted` emitter yet (OBS-D bis), so this asks the durable authority for
     * its position rather than being told. That is a NAMED gap, not a hidden one: it costs a cheap
     * indexed read per idle round instead of a push, and the alternative would have been to ship a
     * follow whose latency source was undocumented.
     */
    fun follow(
        lanes: ObserveLanes,
        runId: String,
        parsed: ObservationParseResult.Parsed,
        control: FollowControl,
        out: PrintStream,
        diagnostics: PrintStream,
    ): ObserveOutcome = when (parsed.view) {
        ObservationView.CONSOLE -> followOutput(lanes, runId, parsed, control, out, diagnostics)
        ObservationView.NORMAL, ObservationView.EVENTS, ObservationView.QUIET ->
            followEvents(lanes, runId, parsed, control, out)
        ObservationView.FULL -> ObserveOutcome.Refused(ObserveRefusal.FullViewUnavailable)
    }

    private fun followEvents(
        lanes: ObserveLanes,
        runId: String,
        parsed: ObservationParseResult.Parsed,
        control: FollowControl,
        out: PrintStream,
    ): ObserveOutcome {
        if (!lanes.hasEventStore) return ObserveOutcome.Refused(ObserveRefusal.NoDurableEventStore)
        // The parser already refused this combination before opening a store; this is the same
        // decision read from the same place, at this boundary, so that this function cannot be
        // talked into emitting a document per round by a caller that skipped the parser.
        if (!RunObservationOutput.isIncremental(parsed.format)) {
            return ObserveOutcome.Refused(
                ObserveRefusal.FollowNeedsAnIncrementalFormat(parsed.format.wire),
            )
        }
        var cursor: dev.rubentxu.pipeline.v2.events.identity.EventCursor? = null
        // ONE writer for the whole follow, and ONE presentation. `writeTo` writes and does NOT
        // flush, so a fresh writer per round would leave the round's bytes sitting in that round's
        // buffer, and flushing the PrintStream underneath it flushes nothing: these are different
        // buffers. The presentation is kept open across rounds for a second reason — human
        // rendering is stateful, and a per-round renderer forgets the stage that names a later step.
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        val presentation =
            RunObservationOutput.Stream(parsed.view, parsed.format, parsed.compiled, parsed.budget, writer)
        while (true) {
            val slice = lanes.eventSliceOf(runId, cursor, EVENTS_PER_READ)
                ?: return ObserveOutcome.Refused(ObserveRefusal.NoDurableEventStore)
            if (slice.events.isNotEmpty()) {
                cursor = slice.nextCursor
                // `write` streams and drops as it goes; the query runs per element, so a long run
                // is never materialised just to be filtered at the end.
                presentation.write(slice.events.asSequence())
                writer.flush()
            }
            if (slice.events.any { it is dev.rubentxu.pipeline.v2.events.RunFinished } && !slice.hasMore) {
                return FollowOutcome.ReachedRunFinish.asOutcome()
            }
            if (presentation.isSpent) return FollowOutcome.ReachedRecordBudget.asOutcome()
            if (control.shouldStop()) return FollowOutcome.StoppedByConsumer.asOutcome()
            if (slice.events.isEmpty()) control.idle()
        }
    }

    private fun followOutput(
        lanes: ObserveLanes,
        runId: String,
        parsed: ObservationParseResult.Parsed,
        control: FollowControl,
        out: PrintStream,
        diagnostics: PrintStream,
    ): ObserveOutcome {
        if (!lanes.hasOutputPlane) return ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane)
        val presentation = LiveOutputPresentation(parsed.format, PrintStream(out), diagnostics)
        // Resolved once, and PRINTED — a follow that kept only the position would emit the tail's
        // records to nowhere and then wait for output that had already been committed.
        val tail = when (val bytes = parsed.tailBytes) {
            null -> null
            else -> when (val start = lanes.tailOf(runId, bytes)) {
                null -> return ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane)
                is ObservationOutputRead.Refused ->
                    return ObserveOutcome.Refused(ObserveRefusal.PlaneRefused(start.reason))

                is ObservationOutputRead.Page -> start.page
            }
        }
        var afterOrdinal = tail?.lastOrdinal ?: -1L
        var emitted = 0
        tail?.records?.forEach { record ->
            if (!parsed.compiled.accepts(record)) return@forEach
            if (!parsed.budget.allows(emitted)) return@forEach
            emitted++
            presentation.emit(record)
        }
        while (true) {
            var moreFrames = false
            when (val read = lanes.outputOf(runId, afterOrdinal, FRAMES_PER_READ)) {
                null -> return ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane)
                is ObservationOutputRead.Refused ->
                    return ObserveOutcome.Refused(ObserveRefusal.PlaneRefused(read.reason))

                is ObservationOutputRead.Page -> {
                    val page = read.page
                    moreFrames = page.moreFrames
                    page.records.filter { parsed.compiled.accepts(it) }.forEach { record ->
                        if (!parsed.budget.allows(emitted)) return@forEach
                        emitted++
                        presentation.emit(record)
                    }
                    afterOrdinal = page.lastOrdinal
                }
            }
            if (!parsed.budget.allows(emitted)) return FollowOutcome.ReachedRecordBudget.asOutcome()
            val tails = lanes.outputTailsOf(runId).orEmpty()
            // Two authorities, asked separately. `tails` is the OUTPUT plane and answers whether more
            // BYTES can arrive; `hasRunFinished` is the EXECUTION plane and answers whether the run
            // ended at all. A silent run has no tails, so without the second fact `follow` on
            // `sh("true")` would never return.
            when (followDecision(moreFrames, tails, lanes.hasRunFinished(runId))) {
                FollowDecision.ReadAgain -> {
                    if (control.shouldStop()) return FollowOutcome.StoppedByConsumer.asOutcome()
                    if (!moreFrames) control.idle()
                }

                FollowDecision.Finished -> return FollowOutcome.ReachedSealedOutput.asOutcome()
            }
        }
    }
}

/** Why a follow stopped, which is not the same as whether the run succeeded. */
sealed interface FollowOutcome {
    data object ReachedRunFinish : FollowOutcome
    data object ReachedSealedOutput : FollowOutcome
    data object StoppedByConsumer : FollowOutcome

    /**
     * The read spent `--limit`.
     *
     * Its own case because none of the others happened. The run did not commit a terminal fact, and
     * the consumer did not ask to stop — so reporting either would be a caller being told the run
     * finished, or that we gave up, when in fact we were told exactly how much to show.
     */
    data object ReachedRecordBudget : FollowOutcome

    fun asOutcome(): ObserveOutcome = ObserveOutcome.Followed(this)
}

/**
 * The control the CLI hands a follow.
 *
 * Interruption rather than a duration: a user who does not want to watch a run any more presses a
 * key or sends a signal, and "should I keep waiting" is answered by whether anyone is still asking.
 */
private class InterruptFollow(private val interrupted: () -> Boolean) : FollowControl {
    override fun shouldStop(): Boolean = interrupted()
    override fun idle() = Thread.sleep(FOLLOW_IDLE_MILLIS)
}

/** Idle wait between reads that found nothing. A consumer policy, tuned in OBS-F with measurements. */
private const val FOLLOW_IDLE_MILLIS = 25L

/**
 * When a follow reads again, and when it gives up.
 *
 * A port rather than a clock so a test can stop after a COUNT of idle rounds instead of waiting for
 * a duration: a follower that only ends when the wall clock says so is a follower whose test is a
 * timing assertion, and those fail on a loaded machine and get read as a product defect.
 */
interface FollowControl {
    fun shouldStop(): Boolean

    /** Called when a round read nothing. The wait belongs to the consumer, not the product. */
    fun idle()
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
     * Whether the durable EVENT authority says this run reached a terminal state.
     *
     * Added for **UAT-R1-01** (OBS-R1 mandate §1.5), because the output lane provably cannot
     * substitute for it. A run that finished writing nothing owns no stream and no frame, so
     * [outputTailsOf] answers an empty list for it — and so it answers the same empty list for a run
     * that has not started. A follower that read termination off that list would hang on
     * `sh("true")` forever, which is precisely the UAT row.
     *
     * It is consulted ONLY when the tail list is empty. A non-empty sealed tail is the output lane's
     * own authority and needs no corroboration; requiring the run to have finished as well would make
     * a console-only lane, which has no event store to ask, unterminatable. See `followDecision`.
     *
     * It is a FACT from the run's own authority rather than an inference, which is why it is a
     * method here and not something `observe` has to derive from what the output lane happens to hold.
     *
     * Defaulted so every lane can answer without re-implementing: [RunFinished] is the terminal fact
     * and the event lane already knows how to enumerate. A lane with a cheaper indexed query may
     * override it; one that cannot answer this must NOT return true, because that would end a follow
     * on a run that is still going.
     */
    fun hasRunFinished(runId: String): Boolean = eventsOf(runId).any { it is RunFinished }

    /**
     * `null` means THIS LANE DOES NOT EXIST — no `--control-root` was given, so there is no plane
     * to ask. It never means "nothing there": a store that exists and cannot answer returns
     * [ObservationOutputRead.Refused], and a store with nothing in it returns an empty page. Those
     * three are different answers and collapsing them is how a hole becomes a clean finish.
     */
    fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead?

    /**
     * Where `--tail-bytes` starts the output lane.
     *
     * `null` for the same reason [outputOf] returns `null`: this lane is absent. It never means
     * "the tail is empty", which is an ordinary page with no records.
     */
    fun tailOf(runId: String, tailBytes: Long): ObservationOutputRead?

    /**
     * One bounded page of the event lane, resumed strictly after [after].
     *
     * Incremental on purpose: a follower that re-read the whole history every round would be O(run)
     * per poll, which is the difference between following a build and stalling on it. `null` means
     * this lane is absent, exactly as in [outputOf].
     */
    fun eventSliceOf(
        runId: String,
        after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
        limit: Int,
    ): dev.rubentxu.pipeline.v2.events.EventSlice?

    /**
     * Tail state per stream of the run, or `null` when the lane is absent.
     *
     * Entries that are `null` INSIDE the list are streams whose state could not be established, and
     * [followDecision] keeps reading on them — unknown is the safe direction.
     */
    fun outputTailsOf(runId: String): List<dev.rubentxu.pipeline.v2.output.OutputTailState?>?

    override fun close() = Unit
}

/** What a replay decided. Replayed carries no count: the writer already knows what it wrote. */
sealed interface ObserveOutcome {
    data object Replayed : ObserveOutcome
    data class Refused(val reason: ObserveRefusal) : ObserveOutcome

    /**
     * A follow stopped for a NAMED reason.
     *
     * Carrying which one matters because "followed to the end" and "stopped when I asked" are
     * different facts about the run, and a reader that reported both as "done" would be claiming
     * the run had finished when this process simply went away.
     */
    data class Followed(val reason: FollowOutcome) : ObserveOutcome
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

    /**
     * A follow was asked for in a format that cannot be continued.
     *
     * Refused rather than approximated. Emitting one array per round would put several documents on
     * stdout with nothing between them, and closing the array when a consumer cuts the follow short
     * would claim the run was complete when it is not. Neither is the document that was asked for,
     * so the answer names the format that is.
     */
    data class FollowNeedsAnIncrementalFormat(val format: String) : ObserveRefusal {
        override fun toString(): String =
            "--format $format is a document: it is only a document once its closing bracket arrives, " +
                "and a follow can be stopped before that. Emitting one document per round would not be " +
                "a continuation of the first. Use --format jsonl, where every line stands alone."
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
            dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider.storeForReading(
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
            override fun hasRunFinished(runId: String): Boolean =
                // RunFinished is the terminal FACT. Its absence is not a guess that the run is still
                // going: it only means this authority cannot prove otherwise, which keeps reading.
                eventStore?.eventsFor(runId)?.any { it is RunFinished } ?: false
            override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int) =
                reader?.readOutput(runId, afterOrdinal, frameLimit)

            override fun tailOf(runId: String, tailBytes: Long) = reader?.readTail(runId, tailBytes)

            override fun eventSliceOf(
                runId: String,
                after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
                limit: Int,
            ) = eventStore?.readSlice(runId, after, limit)

            override fun outputTailsOf(runId: String) =
                reader?.tailStatesOf(runId)
            override fun close() {
                eventStore?.close()
            }
        }
    }
}

private fun ObservationParseResult.Parsed.controlRootOrNull(): String? = this.controlRoot
