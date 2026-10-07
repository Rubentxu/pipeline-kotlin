package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Behaviour tests for the live console sink.
 *
 * ## Harness fidelity
 *
 * HF0 with a hand-written delegate. [ConsolePrintingEventSink] is the
 * production type and the decorator is exercised through its real interface, so
 * the ordering and delegation laws below are genuine. What is NOT proven here:
 * that `Main` installs it, that it sits above redaction, or that the terminal
 * sees lines incrementally. Those belong to the installed-distribution UAT that
 * the live-console slice still owes.
 *
 * ## Mutation
 *
 * - `LIVE-1` (emits on append, not at the end) → buffer and flush in
 *   `eventsFor`.
 * - `LIVE-2` (delegates every event) → drop the `delegate.append` call.
 * - `SCOPE-1` (live step lines name their stage) → stop tracking StageStarted.
 */
class ConsolePrintingEventSinkTest {

    private val at: Instant = Instant.parse("2026-10-06T10:00:00Z")

    private class RecordingSink : EventSink {
        val appended = mutableListOf<DomainEvent>()
        override fun append(event: DomainEvent) { appended += event }
        override fun eventsFor(runId: String): Sequence<DomainEvent> = appended.asSequence()
    }

    private fun started() = RunStarted("e1", "run-1", 1, at, "demo.kts")
    private fun stage(name: String) = StageStarted("e2", "run-1", 2, at, 0, name)
    private fun step(name: String) = StepStarted("e3", "run-1", 3, at, 0, 0, name, "core.echo")
    private fun echo(text: String) = EchoOutputCaptured("e4", "run-1", 4, at, 0, text)
    private fun note() = DirEntered("e5", "run-1", 5, at, "nested", ".")
    private fun finished() = RunFinished("e6", "run-1", 6, at, "SUCCESS", emptyList())

    private class Harness(view: ObservationView, query: ObservationQuery = ObservationQuery()) {
        val delegate = RecordingSink()
        val emitted = mutableListOf<String>()
        val sink = ConsolePrintingEventSink(delegate, view, compileOk(query)) { emitted += it }

        fun feed(vararg events: DomainEvent) = events.forEach(sink::append)
    }

    private companion object {
        /** The CLI compiles the query once before any effect; a bad one never reaches the sink. */
        fun compileOk(query: ObservationQuery): CompiledObservationQuery =
            (compileQuery(query) as SelectorCompileResult.Ok).value
    }

    @Test
    fun `LIVE-1 a line is emitted as the event arrives, not at the end`() {
        val h = Harness(ObservationView.NORMAL)

        h.feed(started())

        // The decisive assertion: something is already visible before any
        // terminal read, which is what "live" means and what the old
        // collect-then-print shape could never satisfy.
        assertEquals(1, h.emitted.size, "expected a line immediately on append")
        assertTrue(h.emitted.single().contains("Run started"))
    }

    @Test
    fun `LIVE-2 every event still reaches the durable store`() {
        val h = Harness(ObservationView.NORMAL)

        h.feed(started(), stage("build"), echo("hi"), finished())

        assertEquals(4, h.delegate.appended.size, "the observer must not swallow events")
        assertEquals(listOf("RunStarted", "StageStarted", "EchoOutputCaptured", "RunFinished"),
            h.delegate.appended.map { it.kind })
    }

    @Test
    fun `LIVE-3 a render fault cannot lose the event`() {
        // Rendering happens BEFORE delegation precisely so that a projection
        // failure cannot drop an event on its way to the store.
        val delegate = RecordingSink()
        val exploding = object : EventSink by delegate {
            override fun append(event: DomainEvent) = delegate.append(event)
        }
        val sink = ConsolePrintingEventSink(exploding, ObservationView.NORMAL, compileOk(ObservationQuery())) { error("boom") }

        runCatching { sink.append(started()) }

        assertEquals(1, delegate.appended.size, "event must survive a failing emitter")
    }

    @Test
    fun `SCOPE-1 live step lines name their stage`() {
        val h = Harness(ObservationView.NORMAL)

        h.feed(stage("compile"), step("echo"))

        assertTrue(
            h.emitted.any { it.contains("[step: compile] echo") },
            "live step line must carry the stage name, got ${h.emitted}",
        )
    }

    @Test
    fun `VIEW-1 the view projection also applies to the live stream`() {
        val h = Harness(ObservationView.NORMAL)

        h.feed(note(), echo("visible"))

        assertFalse(h.emitted.any { it.contains("dir entered") }, "NOTE must be hidden in NORMAL")
        assertEquals(1, h.emitted.size)
    }

    @Test
    fun `VIEW-2 the events view shows strictly more when live`() {
        val normal = Harness(ObservationView.NORMAL).apply { feed(note()) }.emitted.size
        val all = Harness(ObservationView.EVENTS).apply { feed(note()) }.emitted.size

        assertTrue(all > normal, "events view must include NOTE ($all vs $normal)")
    }

    @Test
    fun `ORDER-1 live lines arrive in append order`() {
        val h = Harness(ObservationView.NORMAL)

        h.feed(started(), stage("build"), echo("one"), echo("two"), finished())

        assertTrue(h.emitted.first().contains("Run started"))
        assertTrue(h.emitted.last().contains("Finished"))
        assertEquals(listOf("one", "two"),
            h.emitted.filter { it == "one" || it == "two" })
    }

    @Test
    fun `PASS-1 eventsFor delegates to the inner sink`() {
        val h = Harness(ObservationView.NORMAL)
        h.feed(started())

        assertEquals(1, h.sink.eventsFor("run-1").count())
    }

    /**
 * The query is a READ filter. A `--grep` that also dropped events from the store would make
 * the run unreplayable and would turn a display preference into a durability decision.
 *
 * The markers are deliberately non-overlapping: `Literal` is substring semantics, and "unwanted"
 * contains "wanted", so overlapping fixtures would make this row pass for the wrong reason.
 *
 * Mutation that kills it: move the `query.accepts` guard above `delegate.append`, so a filtered
 * event is dropped on its way to the store.
 */
@Test
    fun `FILTER-1 a filtered event is not printed but is still stored`() {
        val h = Harness(
            ObservationView.NORMAL,
            ObservationQuery(lines = LineSelector.Only(listOf(TextSelector.Literal("keep me")))),
        )

        h.feed(started(), echo("keep me"), echo("drop me"))

        assertTrue(
    h.emitted.any { it == "keep me" },
            "the matching message must be printed, got ${h.emitted}",
        )
        assertFalse(
            h.emitted.any { it.contains("drop me") },
            "the non-matching message must not be printed, got ${h.emitted}",
        )
        assertEquals(listOf("RunStarted", "EchoOutputCaptured", "EchoOutputCaptured"),
            h.delegate.appended.map { it.kind },
            "every event must still be stored, whatever the filter selected")
    }

    /**
     * The stream carries the stage-name scope that later lines resolve against. Here the query
     * selects ONLY step lines, so the `StageStarted` that feeds that scope is itself filtered out
     * — and must still be folded, or the one selected line loses its stage.
     *
     * Mutation that kills it: early-return on `!query.accepts(event)` before `stream.accept`.
     */
    @Test
    fun `FILTER-2 a filtered-out stage still feeds the scope of selected lines`() {
        val h = Harness(
            ObservationView.NORMAL,
            ObservationQuery(eventKinds = setOf("StepStarted")),
        )

        h.feed(stage("build"), step("echo"))

        assertEquals(listOf("[PipelineK] [step: build] echo"), h.emitted,
            "the selected step line must name the stage declared by a filtered-out event")
    }
}