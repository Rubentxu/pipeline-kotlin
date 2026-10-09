package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringWriter
import java.time.Instant

/**
 * OBS-H — the replay path and the live path must not be two encoders.
 *
 * ## What changed and why it needed certifying
 *
 * `MainObserveCli.replayEvents` used to call `RunObservationOutput.encode(events.toList(), …)` and
 * `out.print(…)` the resulting `String`. `run`'s live path calls `RunObservationOutput.writeTo`.
 * Both land in the same `Stream`, so they rendered the same bytes — which is why nothing failed when
 * the audit named `events.toList()` as reintroducing a full materialisation. The divergence was in
 * memory: `encode` takes a `List` and returns a `String`, so a replay held the run's whole history
 * twice while it produced one page of output.
 *
 * Replay now calls `writeTo`. This file is what proves that changed the memory and not the meaning.
 *
 * ## The law
 *
 * For the same events, view, format, query and budget, replay prints **byte-identical** output to
 * the streaming encoder. `RunObservationOutput.encode` is kept — `Main.kt` still uses it twice — so
 * the two entry points coexist, and a second authority that silently diverges is exactly what this
 * row exists to prevent.
 *
 * ## What is NOT asserted, and why
 *
 * Memory is not observable here and this file does not pretend otherwise. `Stream.write` folds EVERY
 * event into the renderer whether or not the query accepts it, because the stage scope a later line
 * resolves against lives in that fold. So both paths consume the sequence to its end, and a counting
 * sequence cannot tell them apart. The honest claim is narrower than "bounded replay": **replay no
 * longer holds two full copies of the run while it renders it.** Counting elements would have looked
 * like evidence and measured nothing.
 *
 * ## Fidelity
 *
 * HF1. [MainObserveCli.replay] is the production entry point, driven through the real [ObserveLanes]
 * port with no filesystem and no process. The comparison side calls [RunObservationOutput.writeTo]
 * directly, which is the same function the production `run` path uses.
 *
 * ## The mutations, with the rows each one actually turned RED
 *
 * - **M-H1b** — empty the stage-scope map inside `HumanConsoleRenderer.ConsoleStream.accept`.
 *   REDS **ENCODER-2** alone.
 * - **M-H2** — move `emitted++` above the query test in `Stream.accepts`, so the budget pays for
 *   SCANNED records instead of selected ones. REDS **ENCODER-3** alone.
 *
 * ## The false green this file paid for, and it is worth the space
 *
 * M-H1b was written against the first version of ENCODER-2 and **turned nothing RED**. The row
 * asserted with a regex that only required `[stage: build]` to appear somewhere before the echo,
 * and emptying the scope left that text untouched — because `EchoOutputCaptured` renders
 * `event.content` verbatim and `StageStarted` prints its own `stageName`; neither reads the scope.
 * Only `[step: …]` resolves its stage name through the scope.
 *
 * So the row was green, the claim in its name was untested, and a mutation that empties the fold
 * proved it. The assertion now sits on the step line, and M-H1b kills it. A guard that survives the
 * mutation of the thing it names is not a guard.
 */
@DisplayName("OBS-H — replay y la vía en vivo producen los mismos bytes por el mismo codificador")
class ObserveReplayEncoderEquivalenceTest {

    private val at: Instant = Instant.parse("2026-10-07T11:00:00Z")
    private val runId = "run-encoder"

    private fun events(): List<DomainEvent> = listOf(
        RunStarted("e0", runId, 0L, at, "/tmp/run.pipeline.kts"),
        StageStarted("e1", runId, 1L, at, 0, "build"),
        StepStarted("e2", runId, 2L, at, 0, 0, "compile", "sh"),
        EchoOutputCaptured("e3", runId, 3L, at, 0, "compiling..."),
        StageFinished("e4", runId, 4L, at, 0, "build", "SUCCESS"),
        RunFinished("e5", runId, 5L, at, "SUCCESS", emptyList()),
    )

    /** No output lane: this file is about the event lane, and a present one would need a store. */
    private class EventOnlyLanes(private val events: List<DomainEvent>) : ObserveLanes {
        override val hasEventStore: Boolean get() = true
        override val hasOutputPlane: Boolean get() = false
        override fun eventsOf(runId: String): Sequence<DomainEvent> = events.asSequence()
        override fun eventSliceOf(
            runId: String,
            after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
            limit: Int,
        ): dev.rubentxu.pipeline.v2.events.EventSlice? = null
        override fun outputTailsOf(runId: String): List<dev.rubentxu.pipeline.v2.output.OutputTailState?>? = null
        override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead? = null
        override fun tailOf(runId: String, tailBytes: Long): ObservationOutputRead? = null
    }

    private fun parsed(vararg args: String): ObservationParseResult.Parsed {
        val result = CliParser.parseObservation(arrayOf(*args))
        assertTrue(result is ObservationParseResult.Parsed, "expected admission, got: $result")
        return result as ObservationParseResult.Parsed
    }

    /** What the production replay entry point prints. */
    private fun replayed(args: Array<String>): String {
        val bytes = ByteArrayOutputStream()
        val diagnostics = ByteArrayOutputStream()
        PrintStream(bytes, true, "UTF-8").use { out ->
            PrintStream(diagnostics, true, "UTF-8").use { err ->
                val outcome = MainObserveCli.replay(EventOnlyLanes(events()), runId, parsed(*args), out, err)
                assertEquals(ObserveOutcome.Replayed, outcome)
            }
        }
        return bytes.toString("UTF-8")
    }

    /** What `run`'s live path prints, for the same parse. */
    private fun streamed(args: Array<String>): String {
        val p = parsed(*args)
        val writer = StringWriter()
        RunObservationOutput.writeTo(
            events().asSequence(),
            writer,
            p.view,
            p.format,
            p.compiled,
            p.budget,
        )
        return writer.toString()
    }

    @Test
    fun `ENCODER-1 every format and budget renders identically through replay and through the live path`() {
        val cases = listOf(
            "human text, everything" to arrayOf("--view", "normal", "--format", "text"),
            "human text, only the first stage" to arrayOf("--view", "normal", "--format", "text", "--stage", "build"),
            "human text, a budget that cuts inside the run" to
                arrayOf("--view", "normal", "--format", "text", "--limit", "2"),
            "machine lines, everything" to arrayOf("--view", "events", "--format", "jsonl"),
            "machine lines, filtered" to arrayOf("--view", "events", "--format", "jsonl", "--kind", "EchoOutputCaptured"),
            "a JSON document, everything" to arrayOf("--view", "events", "--format", "json"),
            "a JSON document, filtered" to arrayOf("--view", "events", "--format", "json", "--stage", "build"),
        )

        cases.forEach { (name, args) ->
            val replayedText = replayed(args)
            val streamedText = streamed(args)
            assertEquals(
                streamedText,
                replayedText,
                "$name: replay and the live path must not be two encoders. Replay:\n$replayedText\nLive:\n$streamedText",
            )
            assertTrue(replayedText.isNotEmpty(), "$name: an empty render would make the equality above vacuous")
        }
    }

    @Test
    fun `ENCODER-2 the human render is STATEFUL across events, which is what a per-record encoder loses`() {
        // The scope lives in the renderer, and only SOME lines read it. `StageStarted` prints its own
        // `stageName` and `EchoOutputCaptured` prints its content verbatim, so neither depends on the
        // fold; `[step: …]` resolves its stage name THROUGH the scope and is the line that dies when
        // the scope is not carried between events.
        //
        // The first version of this row asserted with a regex that only required "[stage: build]" to
        // appear somewhere before the echo, and M-H1b emptied the whole scope without turning it RED.
        // The scope does not exist for the echoes at all. The assertion below is therefore on the STEP
        // line, which is the line that actually reads the scope.
        val text = replayed(arrayOf("--view", "normal", "--format", "text"))

        assertTrue(
            text.contains("[step: build] compile"),
            "the step line must resolve its stage name through the scope established by an EARLIER " +
                "event, so it cannot be a function of the step event alone; got:\n$text",
        )
        assertTrue(
            !text.contains("[step: stage 0]"),
            "and the fallback `stage 0` means the scope was empty when the step arrived, which is " +
                "precisely what a per-record renderer produces; got:\n$text",
        )
    }

    @Test
    fun `ENCODER-3 a budget is spent on SELECTED records, not on scanned ones`() {
        // A budget without a filter is a prefix: the render is sequential and stateful, so what the
        // first records produced cannot be changed by the ones the budget stopped short of.
        val all = replayed(arrayOf("--view", "normal", "--format", "text"))
        val cut = replayed(arrayOf("--view", "normal", "--format", "text", "--limit", "2"))

        assertTrue(all.length > cut.length, "a budget must actually cut something; full:\n$all\ncut:\n$cut")
        assertTrue(
            all.startsWith(cut),
            "and the cut must be a prefix of the whole, because rendering is sequential; full:\n$all\ncut:\n$cut",
        )

        // The distinguishing row: WITH a filter, the budget counts records the query accepted. If it
        // counted scanned ones instead, the single permitted record would be `Run started` — the
        // first event of the run, which the query rejected.
        val filtered = replayed(arrayOf("--view", "normal", "--format", "text", "--stage", "build", "--limit", "1"))

        assertTrue(
            !filtered.contains("Run started"),
            "a budget spent on SCANNED records would have paid for `Run started`, which the query " +
                "rejected; the budget is spent on selected records; got:\n$filtered",
        )
        assertTrue(
            filtered.contains("[stage: build]"),
            "and it pays for the first record the query DID accept; got:\n$filtered",
        )
    }

    @Test
    fun `ENCODER-4 a query that selects nothing is empty, and says so by being empty`() {
        val none = replayed(arrayOf("--view", "normal", "--format", "text", "--stage", "no-such-stage"))

        assertEquals(
            streamed(arrayOf("--view", "normal", "--format", "text", "--stage", "no-such-stage")),
            none,
            "an empty selection must match the live path too",
        )
        assertTrue(
            none.none { it.isLetterOrDigit() },
            "and it must be empty of records. A renderer that emitted folded-but-rejected lines would " +
                "put the filter into the presentation of the survivors; got:\n$none",
        )
    }

    @Test
    fun `ENCODER-5 the JSON document is still a document, terminated exactly once`() {
        val document = replayed(arrayOf("--view", "events", "--format", "json"))

        assertEquals(1, document.trim().lines().size, "JSON is one document, not one per record; got:\n$document")
        assertTrue(document.trim().startsWith("["), "and it opens as an array; got:\n$document")
        assertTrue(document.trim().endsWith("]"), "and closes exactly once; got:\n$document")
    }
}