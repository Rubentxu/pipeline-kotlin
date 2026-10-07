package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

/**
 * OBS-E5b — `observe` as ONE verb over both durable authorities, refusing what it cannot promise.
 *
 * ## What this pins
 *
 * The rows that matter here are the refusals, not the rendering. A reader that answers "there is
 * nothing to see" for a lane it never had a store for is indistinguishable, to whoever reads its
 * output, from a run that genuinely produced nothing — and that is how a hole becomes a clean
 * finish. So each of the three absences gets its own row and its own named outcome.
 *
 * ```text
 * OBSERVE-1  the event lane renders through the SAME encoder `run` uses
 * OBSERVE-2  the console lane renders committed bytes, in frame order
 * OBSERVE-3  --channel reaches the console lane through the shared query
 * OBSERVE-4  no event store is REFUSED, not answered with an empty list
 * OBSERVE-5  no output plane is REFUSED, not answered with empty text
 * OBSERVE-6  a store that says "I cannot" is REFUSED, not answered with empty text
 * OBSERVE-7  --view full is refused at admission for this verb too
 * OBSERVE-8  --view console is deliverable HERE and still refused for `run`
 * OBSERVE-9  --resume is refused by name, not ignored
 * ```
 *
 * OBSERVE-8 is the row that keeps the two verbs from drifting: availability is a property of a
 * READER, and `ObservationView.available` used to hard-code it as a property of the view.
 *
 * Mutations, with the rows each one actually turned RED — measured, not predicted. The first draft
 * of the last one said "only that one row" and was wrong, for the second time in this branch:
 *
 * - `M-O1` (a missing lane answers "nothing") → replace the absence refusals with an empty page /
 *   an empty event list. REDS **OBSERVE-4, OBSERVE-5, OBSERVE-6**, because all three collapse to
 *   the same clean-looking empty output. That is the whole failure: one wrong line makes three
 *   different absences indistinguishable.
 * - `M-O2` (the console lane ignores the query) → drop the `parsed.compiled.accepts(it)` filter.
 *   REDS **OBSERVE-3**.
 * - `M-O3` (`run` gains `console`) → widen the deliverable set to every view. REDS **OBSERVE-7 AND
 *   OBSERVE-8**, not OBSERVE-8 alone: `entries.toSet()` also makes `full` deliverable, and
 *   `full` is the one view whose refusal has nothing to do with which lane a reader can see.
 *   Measuring it is what caught the over-narrow claim.
 */
class ObsE5ObserveReplayTest {

    private val at: Instant = Instant.parse("2026-10-07T10:00:00Z")
    private val runId = "run-1"

    // ---- fixtures -------------------------------------------------------------------------------

    private fun outputRecord(ordinal: Long, channel: OutputChannel, text: String): ObservationRecord.Output {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ObservationRecord.Output(
            OutputFrame(ordinal, OutputStreamId("$runId/s0-0/${channel.token}"), channel, 0, bytes.size.toLong()),
            bytes,
            text,
        )
    }

    private fun events(): List<DomainEvent> = listOf(
        StageStarted("e1", runId, 0, at, 0, "build"),
        EchoOutputCaptured("e2", runId, 1, at, 0, "a script message"),
        StageFinished("e3", runId, 2, at, 0, "build", "SUCCESS"),
    )

    private class Lanes(
        private val events: List<DomainEvent>?,
        private val pages: List<ObservationOutputPage>,
        private val refusal: OutputRefusal? = null,
    ) : ObserveLanes {
        var reads = 0
            private set

        override val hasEventStore: Boolean get() = events != null
        override val hasOutputPlane: Boolean get() = pages.isNotEmpty() || refusal != null
        override fun eventsOf(runId: String): Sequence<DomainEvent> = events.orEmpty().asSequence()

        override fun eventSliceOf(
            runId: String,
            after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
            limit: Int,
        ): dev.rubentxu.pipeline.v2.events.EventSlice? = null

        override fun outputTailsOf(runId: String): List<dev.rubentxu.pipeline.v2.output.OutputTailState?>? = null

        override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead? {
            reads++
            refusal?.let { return ObservationOutputRead.Refused(it) }
            val next = pages.indexOfFirst { it.lastOrdinal > afterOrdinal }
            return ObservationOutputRead.Page(
                if (next < 0) ObservationOutputPage(emptyList(), afterOrdinal, false)
                else pages[next].copy(
                    records = pages[next].records.take(frameLimit),
                    moreFrames = pages.drop(next + 1).isNotEmpty(),
                ),
            )
        }
    }

    private fun parsed(vararg args: String): ObservationParseResult.Parsed {
        val result = CliParser.parseObservation(arrayOf(*args))
        assertTrue(result is ObservationParseResult.Parsed, "expected admission, got: $result")
        return result as ObservationParseResult.Parsed
    }

    private class Sink {
        val bytes = ByteArrayOutputStream()
        val stream = PrintStream(bytes, true, "UTF-8")
        val diagnostics = ByteArrayOutputStream()
        val diagnosticStream = PrintStream(diagnostics, true, "UTF-8")
        fun stdout(): String = bytes.toString("UTF-8")
        fun stderr(): String = diagnostics.toString("UTF-8")
    }

    // ---- rows ------------------------------------------------------------------------------------

    @Test
    fun `OBSERVE-1 the event lane renders through the same encoder run uses`() {
        val lanes = Lanes(events(), emptyList())
        val sink = Sink()

        val outcome = MainObserveCli.replay(lanes, runId, parsed("--view", "normal"), sink.stream, sink.diagnosticStream)

        assertEquals(ObserveOutcome.Replayed, outcome)
        val stdout = sink.stdout()
        assertTrue(stdout.contains("[PipelineK] [stage: build]"), "got:\n$stdout")
        assertTrue(stdout.contains("a script message"), "got:\n$stdout")
    }

    @Test
    fun `OBSERVE-2 the console lane renders committed bytes`() {
        val page = ObservationOutputPage(
            listOf(
                outputRecord(0, OutputChannel.STDOUT, "compiling\n"),
                outputRecord(1, OutputChannel.STDERR, "warning\n"),
            ),
            lastOrdinal = 1,
            moreFrames = false,
        )
        val lanes = Lanes(null, listOf(page))
        val sink = Sink()

        val outcome = MainObserveCli.replay(lanes, runId, parsed("--view", "console"), sink.stream, sink.diagnosticStream)

        assertEquals(ObserveOutcome.Replayed, outcome)
        assertEquals("compiling\nwarning\n", sink.stdout())
    }

    @Test
    fun `OBSERVE-3 channel reaches the console lane through the shared query`() {
        val page = ObservationOutputPage(
            listOf(
                outputRecord(0, OutputChannel.STDOUT, "compiling\n"),
                outputRecord(1, OutputChannel.STDERR, "warning\n"),
            ),
            lastOrdinal = 1,
            moreFrames = false,
        )
        val lanes = Lanes(null, listOf(page))
        val sink = Sink()

        MainObserveCli.replay(
            lanes,
            runId,
            parsed("--view", "console", "--channel", "stderr"),
            sink.stream,
            sink.diagnosticStream,
        )

        assertEquals("warning\n", sink.stdout(), "the shared query must filter the output lane too")
    }

    @Test
    fun `OBSERVE-4 no event store is refused, not answered with an empty list`() {
        val lanes = Lanes(null, emptyList())
        val sink = Sink()

        val outcome = MainObserveCli.replay(lanes, runId, parsed("--view", "normal"), sink.stream, sink.diagnosticStream)

        assertEquals(ObserveOutcome.Refused(ObserveRefusal.NoDurableEventStore), outcome)
        assertEquals("", sink.stdout(), "a refused lane must not also print an empty success")
    }

    @Test
    fun `OBSERVE-5 no output plane is refused, not answered with empty text`() {
        val lanes = Lanes(events(), emptyList())
        val sink = Sink()

        val outcome = MainObserveCli.replay(lanes, runId, parsed("--view", "console"), sink.stream, sink.diagnosticStream)

        assertEquals(ObserveOutcome.Refused(ObserveRefusal.NoOutputPlane), outcome)
        assertEquals("", sink.stdout())
    }

    @Test
    fun `OBSERVE-6 a store that says I cannot is refused, not answered with empty text`() {
        val lanes = Lanes(null, emptyList(), refusal = OutputRefusal.RecoveryNotCompleted)
        val sink = Sink()

        val outcome = MainObserveCli.replay(lanes, runId, parsed("--view", "console"), sink.stream, sink.diagnosticStream)

        assertEquals(ObserveOutcome.Refused(ObserveRefusal.PlaneRefused(OutputRefusal.RecoveryNotCompleted)), outcome)
        assertEquals("", sink.stdout())
    }

    @Test
    fun `OBSERVE-7 the full view is refused at admission for this verb too`() {
        val result = CliParser.parseObservation(arrayOf("--view", "full"))

        assertEquals(ObservationParseResult.Rejected(CliError.UnavailableView(ObservationView.FULL)), result)
    }

    @Test
    fun `OBSERVE-8 console is deliverable here and still refused for run`() {
        assertTrue(
            CliParser.parseObservation(arrayOf("--view", "console")) is ObservationParseResult.Parsed,
            "observe reads the output lane, so console is a view it can deliver",
        )
        assertEquals(
            CliParseResult.Rejected(CliError.UnavailableView(ObservationView.CONSOLE)),
            CliParser.parse(arrayOf("run", "--view", "console", "/path/to/script.kts")),
            "run streams the event lane only. Availability is a property of a READER, and the two " +
                "readers are allowed to disagree about it.",
        )
    }

    @Test
    fun `OBSERVE-9 an execution option is refused by name, not ignored`() {
        val result = CliParser.parseObservation(arrayOf("--resume"))

        assertEquals(ObservationParseResult.Rejected(CliError.OptionNotReadable("--resume")), result)
    }
}