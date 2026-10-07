package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.application.observation.RecordBudget
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
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
 * OBS-E5d — `--limit`, a budget over SELECTED records.
 *
 * ## The rule these rows defend
 *
 * A limit is not a filter and must not behave like one. It counts what the reader SHOWED, so
 * `--stage build --limit 5` shows five records matching `--stage build` whether the run committed
 * five or ten thousand — a budget spent on rejected records would make the number a second, invisible
 * filter, and the same five lines would appear for queries with wildly different selectivity.
 *
 * And a follower that has spent its budget has not finished: it did not see a terminal fact and
 * nobody stopped it. Reporting either of the two existing reasons would tell a caller the run ended,
 * or that we gave up, when we were told exactly how much to show.
 *
 * ```text
 * LIMIT-1  `--limit N` stops the event lane after N emitted records
 * LIMIT-2  the limit counts SELECTED records, not scanned ones
 * LIMIT-3  a follower that spends its budget reports ReachedRecordBudget
 * LIMIT-4  the output lane stops at the budget instead of paging the whole plane
 * LIMIT-5  no budget is the identity: every record, exactly as before
 * LIMIT-6  a limit that is not a count is refused, never clamped
 * LIMIT-7  `run --limit` is refused rather than silently truncating a live transcript
 * ```
 *
 * Mutations, with the rows each one was MEASURED to flip:
 *
 * - `M-L1` (the budget counts scanned records) → check the budget BEFORE the query. REDS LIMIT-2
 *   alone, which is why that row exists: without it this reads as a harmless reordering.
 * - `M-L2` (a spent budget looks like a finished run) → return `ReachedRunFinish`. REDS LIMIT-3.
 * - `M-L3` (the budget is advisory) → drop every check from the OUTPUT lane only. REDS LIMIT-4
 *   alone. It was predicted to also red LIMIT-1 and did not: the event lane enforces the budget
 *   inside the presentation, so removing the output lane's checks leaves the event lane intact.
 * - `M-L4` (the presentation ignores the budget) → REDS LIMIT-1. Written because M-L3 had left
 *   LIMIT-1 with NO killing mutation: it was a characterisation row until this one existed.
 *
 * LIMIT-5, LIMIT-6, LIMIT-7 and LIMIT-8 are characterisation and say so. They pin an identity
 * (no budget changes nothing), two refusals, and the purity of the budget value — properties no
 * single wrong-turn mutation removes. A row that cannot be killed is worth keeping only when its
 * name says it is pinning, not defending.
 */
class ObsE5LimitTest {

    private val runId = "run-1"
    private val at: Instant = Instant.parse("2026-10-07T10:00:00Z")

    private fun stage(id: String, sequence: Long, name: String) = StageStarted(id, runId, sequence, at, 0, name)

    private class Scripted(private val slices: List<EventSlice>) : ObserveLanes {
        override val hasEventStore = true
        override val hasOutputPlane = false
        override fun eventsOf(runId: String): Sequence<DomainEvent> = slices.flatMap { it.events }.asSequence()
        override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead? = null
        override fun eventSliceOf(runId: String, after: EventCursor?, limit: Int): EventSlice? {
            val next = slices.indexOfFirst { it.nextCursor.lastSequence > (after?.lastSequence ?: 0L) }
            return if (next < 0) EventSlice(emptyList(), after ?: EventCursor(runId, 0), false) else slices[next]
        }

        override fun outputTailsOf(runId: String): List<OutputTailState?>? = null
    }

    private class Control(private val stopAfter: Int) : FollowControl {
        var rounds = 0
            private set

        override fun shouldStop(): Boolean = ++rounds >= stopAfter
        override fun idle() = Unit
    }

    private class Sink {
        val bytes = ByteArrayOutputStream()
        val out = PrintStream(bytes, true, "UTF-8")
        val diagnostics = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
        fun stdout(): String = bytes.toString("UTF-8")
    }

    private fun parsed(vararg args: String): ObservationParseResult.Parsed {
        val result = CliParser.parseObservation(arrayOf(*args))
        assertTrue(result is ObservationParseResult.Parsed, "expected admission, got: $result")
        return result as ObservationParseResult.Parsed
    }

    @Test
    fun `LIMIT-1 the event lane stops after N emitted records`() {
        val slices = listOf(
            EventSlice(
                listOf(stage("e1", 1, "build"), stage("e2", 2, "test")),
                EventCursor(runId, 2),
                true,
            ),
            EventSlice(
                listOf(stage("e3", 3, "lint"), RunFinished("e4", runId, 4, at, "SUCCESS", emptyList())),
                EventCursor(runId, 4),
                false,
            ),
        )
        val sink = Sink()

        val outcome = MainObserveCli.replay(
            Scripted(slices), runId, parsed("--view", "events", "--limit", "2"), sink.out, sink.diagnostics,
        )

        assertEquals(ObserveOutcome.Replayed, outcome)
        val out = sink.stdout()
        assertTrue(out.contains("[stage: build]"), "got:\n$out")
        assertTrue(out.contains("[stage: test]"), "got:\n$out")
        assertTrue(!out.contains("[stage: lint]"), "the third record is past the budget. got:\n$out")
    }

    @Test
    fun `LIMIT-2 the budget counts selected records, not scanned ones`() {
        // Ten stages, of which only two are named `build`. A budget spent on scanned records would
        // stop after five scanned and show one `build` line; a budget spent on emitted records shows
        // both and then stops.
        val many = (1..10).map { stage("e$it", it.toLong(), if (it % 5 == 1) "build" else "test") }
        val sink = Sink()

        MainObserveCli.replay(
            Scripted(listOf(EventSlice(many, EventCursor(runId, 10), false))),
            runId,
            parsed("--view", "events", "--stage", "build", "--limit", "2"),
            sink.out,
            sink.diagnostics,
        )

        val out = sink.stdout()
        assertEquals(2, out.lines().count { it.contains("[stage: build]") }, "got:\n$out")
        assertTrue(!out.contains("[stage: test]"), "the query already excluded these. got:\n$out")
    }

    @Test
    fun `LIMIT-3 a follower that spends its budget reports ReachedRecordBudget`() {
        val slices = listOf(
            EventSlice(
                listOf(stage("e1", 1, "build"), stage("e2", 2, "test")),
                EventCursor(runId, 2),
                false,
            ),
        )
        val sink = Sink()

        val outcome = MainObserveCli.follow(
            Scripted(slices),
            runId,
            parsed("--follow", "--view", "events", "--limit", "2"),
            Control(99),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(
            ObserveOutcome.Followed(FollowOutcome.ReachedRecordBudget),
            outcome,
            "the run committed no RunFinished and the consumer never asked to stop; either other " +
                "answer would be a lie",
        )
        assertTrue(!sink.stdout().contains("[Finished"), "and nothing past the budget leaked out")
    }

    @Test
    fun `LIMIT-4 the output lane stops at the budget instead of paging the whole plane`() {
        val records = (0..9).map {
            ObservationRecord.Output(
                OutputFrame(it.toLong(), OutputStreamId("$runId/s0-0/stdout"), OutputChannel.STDOUT, 0, 4),
                "line\n".toByteArray(),
                "line\n",
            )
        }
        var reads = 0
        val lanes = object : ObserveLanes {
            override val hasEventStore = false
            override val hasOutputPlane = true
            override fun eventsOf(runId: String): Sequence<DomainEvent> = emptySequence()
            override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead {
                reads++
                val from = (afterOrdinal + 1).toInt()
                if (from >= records.size) return ObservationOutputRead.Page(ObservationOutputPage(emptyList(), afterOrdinal, false))
                val page = records.drop(from).take(frameLimit)
                return ObservationOutputRead.Page(
                    ObservationOutputPage(page, page.last().frame.ordinal, from + frameLimit < records.size),
                )
            }

            override fun eventSliceOf(runId: String, after: EventCursor?, limit: Int): EventSlice? = null
            override fun outputTailsOf(runId: String): List<OutputTailState?> = listOf(OutputTailState.Sealed(40))
        }
        val sink = Sink()

        val outcome = MainObserveCli.replay(
            lanes, runId, parsed("--view", "console", "--limit", "3"), sink.out, sink.diagnostics,
        )

        assertEquals(ObserveOutcome.Replayed, outcome)
        assertEquals(3, sink.stdout().lines().count { it == "line" }, "got:\n${sink.stdout()}")
        assertTrue(reads <= 2, "it must stop paging a plane it will not print; reads=$reads")
    }

    @Test
    fun `LIMIT-5 no budget is the identity`() {
        val slices = listOf(
            EventSlice(
                listOf(stage("e1", 1, "build"), RunFinished("e2", runId, 2, at, "SUCCESS", emptyList())),
                EventCursor(runId, 2),
                false,
            ),
        )
        val unbounded = Sink()
        val defaulted = Sink()

        MainObserveCli.replay(Scripted(slices), runId, parsed("--view", "events"), unbounded.out, unbounded.diagnostics)
        MainObserveCli.replay(
            Scripted(slices), runId, parsed("--view", "events", "--limit", "500"), defaulted.out, defaulted.diagnostics,
        )

        assertEquals(unbounded.stdout(), defaulted.stdout(), "a budget larger than the run changes nothing")
        assertEquals(RecordBudget.All, parsed("--view", "events").budget)
    }

    @Test
    fun `LIMIT-6 a limit that is not a count is refused`() {
        for (bad in listOf("abc", "0", "-3", "1.5", "")) {
            val result = CliParser.parseObservation(arrayOf("--view", "events", "--limit", bad))
            assertTrue(
                result is ObservationParseResult.Rejected && result.error is CliError.InvalidLimit,
                "--limit '$bad' must be refused, never clamped to a default. got: $result",
            )
        }
        assertTrue(
            CliParser.parseObservation(arrayOf("--view", "events", "--limit")) is
                ObservationParseResult.Rejected,
            "a flag with no value is missing its value, not a limit of nothing",
        )
    }

    @Test
    fun `LIMIT-7 run refuses --limit rather than silently truncating`() {
        val result = CliParser.parse(arrayOf("run", "--limit", "5", "pipeline.kts"))

        assertTrue(
            result is CliParseResult.Rejected && result.error is CliError.OptionBelongsToObserve,
            "run has no way to say the transcript it printed was cut short. got: $result",
        )
    }

    @Test
    fun `LIMIT-8 the budget is pure, so two readers cannot disagree about how much they showed`() {
        // The property the rest of the row set depends on: spending a budget must not change it.
        // A counter carried inside the query would make "how much have I shown" depend on the order
        // in which readers happened to ask, and two readers of the same run would then disagree
        // about the same query without either of them being wrong about its own output.
        val budget = RecordBudget.UpTo(2)

        assertEquals(listOf(1, 2), budget.bounded(listOf(1, 2, 3)))
        assertEquals(listOf(1, 2), budget.bounded(listOf(1, 2, 3)), "asking twice must not spend it twice")
        assertTrue(budget.allows(0))
        assertTrue(budget.allows(1))
        assertTrue(!budget.allows(2), "position 2 is the third record")
        assertTrue(RecordBudget.All.allows(1_000_000))

        // And it is a closed set: a budget is either unbounded or exactly N, never a third shape
        // that behaves like one of them. Position 1 is the first at which they can differ — at 0
        // both pay out, which is why the difference has to be checked where a limit can actually bite.
        val shapes: List<RecordBudget> = listOf(RecordBudget.All, RecordBudget.UpTo(1))
        assertTrue(shapes.all { it.allows(0) }, "the first record is always paid for")
        assertEquals(2, shapes.map { it.allows(1) }.distinct().size, "the two cases differ where they must")
    }
}
