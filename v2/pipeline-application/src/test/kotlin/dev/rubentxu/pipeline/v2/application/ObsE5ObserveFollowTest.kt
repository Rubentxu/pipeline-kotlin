package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

/**
 * OBS-E5c — `observe --follow`, one lane at a time, stopping for a NAMED reason.
 *
 * ## The rule these rows defend
 *
 * A follow emits records until something proves it is done. The two lanes prove it with two
 * different facts, and neither is "the wall clock said so":
 *
 * - the event lane ends on `RunFinished` committed in the store — a terminal fact;
 * - the output lane ends when no frame is pending AND every stream it has seen is sealed.
 *
 * Everything else — a consumer losing interest, a lane that never existed — is a DIFFERENT outcome.
 * Reporting those as "followed to the end" would be the failure with the worst consequence, because
 * it looks exactly like a successfully completed follow: a truncated transcript reported as complete.
 *
 * ```text
 * FOLLOW-1  the event lane stops on RunFinished and emitted everything before it
 * FOLLOW-2  the output lane stops only once every stream is sealed
 * FOLLOW-3  a consumer stop is StoppedByConsumer, not finished
 * FOLLOW-4  an unknown tail state keeps the output lane reading
 * FOLLOW-5  the event lane resumes from its cursor and never repeats itself
 * FOLLOW-6  an empty lane plus a consumer stop is StoppedByConsumer, not finished
 * FOLLOW-7  a follow over a lane that does not exist is refused
 * FOLLOW-8  the stage scope survives the round boundary
 * FOLLOW-9  replay and follow agree byte for byte
 * FOLLOW-10 a follow refuses the snapshot format
 * FOLLOW-11 run refuses --follow instead of dropping it
 * ```
 *
 * FOLLOW-6 is the row that matters most and the easiest to get wrong: an empty lane whose tails are
 * all sealed would otherwise look identical to a run that legitimately produced nothing.
 *
 * FOLLOW-8 and FOLLOW-9 were found together, and neither was visible by reading the loop. Human
 * presentation is STATEFUL — `HumanConsoleRenderer` learns a stage's name from its `StageStarted`
 * and needs it to label later Step lines — so a follower that re-rendered each round printed
 * `[step: stage 0]` for a step whose stage had been committed in an earlier round. Measured before
 * the fix, with real bytes:
 *
 * ```text
 * replay : [PipelineK] [step: build] sh-0
 * follow : [PipelineK] [step: stage 0] sh-0
 * ```
 *
 * FOLLOW-9 is the row that keeps it from coming back, because it pins the two paths to each other
 * rather than pinning either to a literal.
 *
 * Mutations, with the rows each one MEASURED to flip (not the rows it was expected to flip —
 * two of these turned out wider than predicted, which is why the numbers below are observations):
 *
 * - `M-F1` (stopping looks like finishing) → return `ReachedRunFinish` from every consumer-stop
 *   exit, in both lanes. REDS FOLLOW-3, FOLLOW-4 and FOLLOW-6: both lanes have a "the consumer
 *   stopped reading" exit, and turning that into a terminal fact corrupts three rows, not two.
 * - `M-F2` (unknown means finished) → stop treating an unestablished tail as a reason to keep
 *   reading. REDS FOLLOW-4 alone.
 * - `M-F3` (the cursor is decorative) → always resume from `null`. REDS FOLLOW-1, FOLLOW-5,
 *   FOLLOW-8 and FOLLOW-9 — wider than the cursor row alone, because re-reading from `null` also
 *   never reaches `RunFinished`, so the follower never stops for the right reason and re-renders
 *   the same first page forever.
 * - `M-F4` (a fresh presentation per round) → build the `RunObservationOutput.Stream` inside the
 *   loop instead of before it. REDS FOLLOW-8 and FOLLOW-9. This is the mutation that reproduces the
 *   defect exactly: the per-round fold that dropped the stage scope.
 */
class ObsE5ObserveFollowTest {

    private val runId = "run-1"
    private val at: Instant = Instant.parse("2026-10-07T10:00:00Z")

    private fun event(id: String, sequence: Long) = StageStarted(id, runId, sequence, at, 0, "build")
    private fun stepStarted(id: String, sequence: Long) =
        StepStarted(id, runId, sequence, at, stageIndex = 0, stepIndex = 0, stepName = "sh-0", stepType = "sh")

    private fun finished(id: String, sequence: Long) =
        RunFinished(id, runId, sequence, at, "SUCCESS", emptyList())

    private fun outputRecord(ordinal: Long, text: String): dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val stream = OutputStreamId("$runId/s0-0/stdout")
        return dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output(
            OutputFrame(ordinal, stream, OutputChannel.STDOUT, 0, bytes.size.toLong()),
            bytes,
            text,
        )
    }

    /**
     * Serves scripted rounds. Nothing here consults a clock: each round advances by what the test
     * scripted, and the consumer decides when it has had enough.
     */
    private class Scripted(
        private val slices: List<EventSlice> = emptyList(),
        private val pages: List<ObservationOutputPage> = emptyList(),
        private val tails: List<OutputTailState?>? = null,
        private val hasEvents: Boolean = slices.isNotEmpty(),
        private val hasOutput: Boolean = pages.isNotEmpty() || tails != null,
    ) : ObserveLanes {
        var rounds = 0
            private set
        var cursors: List<EventCursor?> = mutableListOf()
            private set

        override val hasEventStore: Boolean get() = hasEvents
        override val hasOutputPlane: Boolean get() = hasOutput
        override fun eventsOf(runId: String): Sequence<DomainEvent> = slices.flatMap { it.events }.asSequence()

        override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead? {
            rounds++
            val next = pages.indexOfFirst { it.lastOrdinal > afterOrdinal }
            return ObservationOutputRead.Page(
                if (next < 0) ObservationOutputPage(emptyList(), afterOrdinal, false)
                else pages[next].copy(
                    records = pages[next].records.take(frameLimit),
                    moreFrames = pages.drop(next + 1).isNotEmpty(),
                ),
            )
        }
        /**
        * This fake does not model a tail, and says so by answering that the lane is absent.
        *
        * Returning a page it did not compute would be a fabricated read; returning `null` is a refusal,
        * which is loud: a caller that asked for `--tail-bytes` against this fake gets `NoOutputPlane`
        * rather than plausible bytes that were never read.
        */
        override fun tailOf(runId: String, tailBytes: Long): ObservationOutputRead? = null

        override fun eventSliceOf(runId: String, after: EventCursor?, limit: Int): EventSlice? {
            rounds++
            cursors = cursors + after
            val next = slices.indexOfFirst { it.nextCursor.lastSequence > (after?.lastSequence ?: 0L) }
            return if (next < 0) EventSlice(emptyList(), after ?: EventCursor(runId, 0), false) else slices[next]
        }

        override fun outputTailsOf(runId: String): List<OutputTailState?>? = tails
    }

    /** Stops after [stopAfterRounds] rounds. A COUNT, so the test never waits on a duration. */
    private class CountingControl(private val stopAfterRounds: Int) : FollowControl {
        var rounds = 0
            private set
        var idles = 0
            private set
        override fun shouldStop(): Boolean = ++rounds >= stopAfterRounds
        override fun idle() {
            idles++
        }
    }

    private fun parsed(vararg args: String): ObservationParseResult.Parsed {
        val result = CliParser.parseObservation(arrayOf(*args))
        assertTrue(result is ObservationParseResult.Parsed, "expected admission, got: $result")
        return result as ObservationParseResult.Parsed
    }

    private class Sink {
        val bytes = ByteArrayOutputStream()
        val out = PrintStream(bytes, true, "UTF-8")
        val diagnostics = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
        fun stdout(): String = bytes.toString("UTF-8")
    }

    @Test
    fun `FOLLOW-1 the event lane stops on RunFinished`() {
        val slices = listOf(
            EventSlice(listOf(event("e1", 1)), EventCursor(runId, 1), true),
            EventSlice(listOf(event("e2", 2), finished("e3", 3)), EventCursor(runId, 3), false),
        )
        val lanes = Scripted(slices = slices)
        val sink = Sink()

        val outcome = MainObserveCli.follow(
            lanes,
            runId,
            parsed("--follow", "--view", "events"),
            CountingControl(stopAfterRounds = 99),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(ObserveOutcome.Followed(FollowOutcome.ReachedRunFinish), outcome)
        val stdout = sink.stdout()
        // Asserted as PRESENTATION, not as event class names: `--view events --format text` is the
        // human interface, and `HumanConsoleRenderer` prints what a person reads. A row that grepped
        // for `StageStarted` would have been testing the Kotlin type, not the bytes.
        assertTrue(stdout.contains("[stage: build]"), "the pre-terminal event must be emitted. got:\n$stdout")
        assertTrue(stdout.contains("[Finished: SUCCESS]"), "the terminal fact must be emitted too. got:\n$stdout")
        assertEquals(2, lanes.rounds, "it should stop as soon as the fact is committed, not keep polling")
    }

    @Test
    fun `FOLLOW-2 the output lane stops only once every stream is sealed`() {
        val pages = listOf(
            ObservationOutputPage(listOf(outputRecord(0, "compiling\n")), lastOrdinal = 0, moreFrames = false),
        )
        var round = 0
        val lanes = object : ObserveLanes {
            override val hasEventStore = false
            override val hasOutputPlane = true
            override fun eventsOf(runId: String): Sequence<DomainEvent> = emptySequence()
            override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead {
                round++
                return ObservationOutputRead.Page(
                    if (round == 1) pages.single() else ObservationOutputPage(emptyList(), 0, false),
                )
            }
            /**
            * This fake does not model a tail, and says so by answering that the lane is absent.
            *
            * Returning a page it did not compute would be a fabricated read; returning `null` is a refusal,
            * which is loud: a caller that asked for `--tail-bytes` against this fake gets `NoOutputPlane`
            * rather than plausible bytes that were never read.
            */
            override fun tailOf(runId: String, tailBytes: Long): ObservationOutputRead? = null

            override fun eventSliceOf(runId: String, after: EventCursor?, limit: Int): EventSlice? = null
            override fun outputTailsOf(runId: String): List<OutputTailState?> =
                listOf(OutputTailState.Sealed(finalEnd = 17L))
        }
        val sink = Sink()

        val outcome = MainObserveCli.follow(
            lanes,
            runId,
            parsed("--follow", "--view", "console"),
            CountingControl(stopAfterRounds = 99),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(ObserveOutcome.Followed(FollowOutcome.ReachedSealedOutput), outcome)
        assertTrue(sink.stdout().contains("compiling"), "got:\n${sink.stdout()}")
    }

    @Test
    fun `FOLLOW-3 a consumer stop is StoppedByConsumer and not finished`() {
        val slices = listOf(
            EventSlice(listOf(event("e1", 1)), EventCursor(runId, 1), false),
            EventSlice(emptyList(), EventCursor(runId, 1), false),
        )
        val lanes = Scripted(slices = slices)
        val sink = Sink()

        val outcome = MainObserveCli.follow(
            lanes,
            runId,
            parsed("--follow", "--view", "events"),
            CountingControl(stopAfterRounds = 2),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(
            ObserveOutcome.Followed(FollowOutcome.StoppedByConsumer),
            outcome,
            "a run that has not committed RunFinished did not finish, however long we watched it",
        )
    }

    @Test
    fun `FOLLOW-4 an unknown tail state keeps the output lane reading`() {
        val lanes = object : ObserveLanes {
            override val hasEventStore = false
            override val hasOutputPlane = true
            override fun eventsOf(runId: String): Sequence<DomainEvent> = emptySequence()
            override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int) =
                ObservationOutputRead.Page(ObservationOutputPage(emptyList(), afterOrdinal, false))

            override fun eventSliceOf(runId: String, after: EventCursor?, limit: Int): EventSlice? = null

            // A stream this store cannot answer for. `null` is NOT sealed.
            override fun outputTailsOf(runId: String): List<OutputTailState?> = listOf(null)

            /**
             * This fake does not model a tail, and says so by answering that the lane is absent.
             *
             * Returning a page it did not compute would be a fabricated read; returning `null` is a
             * refusal, which is loud: a caller that asked for `--tail-bytes` against this fake gets
             * `NoOutputPlane` rather than plausible bytes that were never read.
             */
            override fun tailOf(runId: String, tailBytes: Long): ObservationOutputRead? = null
        }
        val sink = Sink()

        val outcome = MainObserveCli.follow(
            lanes,
            runId,
            parsed("--follow", "--view", "console"),
            CountingControl(stopAfterRounds = 3),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(
            ObserveOutcome.Followed(FollowOutcome.StoppedByConsumer),
            outcome,
            "not knowing a stream's state must keep reading, never finish",
        )
    }

    @Test
    fun `FOLLOW-5 the event lane resumes from its cursor and never repeats itself`() {
        val slices = listOf(
            EventSlice(listOf(event("e1", 1)), EventCursor(runId, 1), true),
            EventSlice(listOf(finished("e2", 2)), EventCursor(runId, 2), false),
        )
        val lanes = Scripted(slices = slices)
        val sink = Sink()

        MainObserveCli.follow(
            lanes,
            runId,
            parsed("--follow", "--view", "events"),
            CountingControl(stopAfterRounds = 99),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(
            listOf(null, EventCursor(runId, 1)),
            lanes.cursors,
            "the second round must resume AFTER the first round's cursor. Re-reading from null " +
                "every round would re-emit the same events forever, and a text-only assertion would " +
                "not notice.",
        )
    }

    @Test
    fun `FOLLOW-6 an empty lane plus a consumer stop is not finished`() {
        val lanes = Scripted(slices = emptyList(), hasEvents = true)
        val sink = Sink()

        val outcome = MainObserveCli.follow(
            lanes,
            runId,
            parsed("--follow", "--view", "events"),
            CountingControl(stopAfterRounds = 2),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(
            ObserveOutcome.Followed(FollowOutcome.StoppedByConsumer),
            outcome,
            "nothing committed and nothing sealed is indistinguishable from a quiet run ONLY if " +
                "the follower stops pretending. Reporting Finished here is how a truncated " +
                "transcript gets called complete.",
        )
    }

    @Test
    fun `FOLLOW-7 a follow over a lane that does not exist is refused`() {
        val lanes = Scripted(hasEvents = false, hasOutput = false)
        val sink = Sink()

        assertEquals(
            ObserveOutcome.Refused(ObserveRefusal.NoDurableEventStore),
            MainObserveCli.follow(
                lanes, runId, parsed("--follow", "--view", "events"),
                CountingControl(1), sink.out, sink.diagnostics,
            ),
        )
        assertEquals(
            ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane),
            MainObserveCli.follow(
                lanes, runId, parsed("--follow", "--view", "console"),
                CountingControl(1), sink.out, sink.diagnostics,
            ),
        )
        assertEquals("", sink.stdout(), "a refused follow must not have printed a clean finish")
    }

    @Test
    fun `FOLLOW-8 the stage scope survives the round boundary`() {
        // Round 1 commits the stage, round 2 commits the steps. `HumanConsoleRenderer` is STATEFUL:
        // it learns the stage name from `StageStarted` and needs it to name a Step line, and
        // `ConsolePrintingEventSink` — the live path in `run` — therefore keeps one `ConsoleStream`
        // for the whole run. A follower that re-renders each round with the batch fold starts every
        // round with an EMPTY scope and prints "stage 0".
        val slices = listOf(
            EventSlice(listOf(event("e1", 1)), EventCursor(runId, 1), true),
            EventSlice(
                listOf(stepStarted("e2", 2), finished("e3", 3)),
                EventCursor(runId, 3),
                false,
            ),
        )
        val lanes = Scripted(slices = slices)
        val sink = Sink()

        MainObserveCli.follow(
            lanes,
            runId,
            parsed("--follow", "--view", "normal"),
            CountingControl(stopAfterRounds = 99),
            sink.out,
            sink.diagnostics,
        )

        assertTrue(
            sink.stdout().contains("[step: build] sh-0"),
            "a step committed in a LATER round must still be named by the stage committed EARLIER. " +
                "got:\n${sink.stdout()}",
        )
    }

    @Test
    fun `FOLLOW-9 replay and follow agree byte for byte`() {
        // The law from the UAT vertical: a replay digest and a live digest are the same digest.
        // FOLLOW-8 says the follow keeps its scope; this says the two paths render identically, so
        // a per-round renderer cannot drift from the batch one without turning this red.
        val all = listOf(event("e1", 1), stepStarted("e2", 2), finished("e3", 3))
        val sink = Sink()

        val replayed = MainObserveCli.replay(
            Scripted(slices = listOf(EventSlice(all, EventCursor(runId, 3), false))),
            runId,
            parsed("--view", "normal"),
            sink.out,
            sink.diagnostics,
        )
        val replayedBytes = sink.bytes.toString("UTF-8")

        val followed = Sink()
        MainObserveCli.follow(
            Scripted(
                slices = listOf(
                    EventSlice(listOf(all[0]), EventCursor(runId, 1), true),
                    EventSlice(all.drop(1), EventCursor(runId, 3), false),
                ),
            ),
            runId,
            parsed("--follow", "--view", "normal"),
            CountingControl(stopAfterRounds = 99),
            followed.out,
            followed.diagnostics,
        )

        assertEquals(ObserveOutcome.Replayed, replayed)
        assertEquals(
            replayedBytes,
            followed.bytes.toString("UTF-8"),
            "following a completed run must print what replaying it prints",
        )
    }


    @Test
    fun `FOLLOW-10 a follow refuses the snapshot format`() {
        // `--format json` is a DOCUMENT: complete only when its `]` arrives. A follow can be cut
        // short by the consumer at any round, so it cannot promise one — and emitting an array per
        // round would put N documents on stdout, which is not the document anybody asked to parse.
        val result = CliParser.parseObservation(arrayOf("--follow", "--view", "events", "--format", "json"))

        assertTrue(
            result is ObservationParseResult.Rejected &&
                result.error is CliError.FollowNeedsAnIncrementalFormat,
            "a follow that may be cut short must not promise a complete JSON document. got: $result",
        )
        assertTrue(
            CliParser.parseObservation(arrayOf("--follow", "--view", "events", "--format", "jsonl")) is
                ObservationParseResult.Parsed,
            "jsonl IS the incremental machine format: one document per line, no closing bracket to lie about",
        )
    }

    @Test
    fun `FOLLOW-11 run refuses --follow instead of dropping it`() {
        // `applyOption` is shared by both verbs, so without an explicit answer `run --follow` parses
        // and nobody reads the flag: `CliFlags` has no such field. A parameter that is accepted and
        // never interpreted is a dead semantic parameter, and dropping it silently is acting on
        // something the caller did not ask for.
        // `parse` reads args[0] as the verb and stops option scanning at the first
        // non-`--` argument, so the flag has to precede the script.
        val result = CliParser.parse(arrayOf("run", "--follow", "pipeline.kts"))

        assertTrue(
            result is CliParseResult.Rejected && result.error is CliError.OptionBelongsToObserve,
            "run already reads its run as it happens; there is nothing left to follow. got: $result",
        )
        assertTrue(
            CliParser.parse(arrayOf("run", "--limit", "5", "pipeline.kts")) is CliParseResult.Rejected,
            "a truncated live transcript that cannot say it was truncated is the one output shape " +
                "this refuses to produce",
        )
    }
}
