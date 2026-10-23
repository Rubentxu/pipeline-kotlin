package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What the console may and may not take with it when it fails.
 *
 * ## The guarantee
 * The run's record survives a broken drawing. Drawing is a PRESENTATION of
 * something the run already decided, and a presentation is allowed to stop: the
 * renderer refuses on purpose when its routing table and a family handler
 * disagree, because a console that draws a plausible wrong line is worse than
 * a console that stops. What must not happen is the console taking the
 * decision with it.
 *
 * ## The failure this covers
 * The drawing used to happen BEFORE the event reached the store, and the code
 * said the opposite — it claimed that drawing first kept a drawing fault from
 * swallowing the event. It did the reverse: a drawing that threw left the
 * store without the event, so the run had decided something, the console
 * failed to show it, and the record of the run looked like it had never
 * happened.
 *
 * ## Why the instrument is this cheap
 * The sink is a function of its delegate, its drawing and its filter. Nothing
 * here needs a process, a clock or a file, so the cheapest thing that can fail
 * is a direct call with a drawing that refuses.
 */
class ConsolePrintingEventSinkTest {

    @Test
    @DisplayName("the decision is written before it is drawn, and a broken drawing does not take it with it")
    fun `a broken drawing does not take the record with it`() {
        val order = mutableListOf<String>()
        val recorded = mutableListOf<DomainEvent>()
        val sink = ConsolePrintingEventSink(
            delegate = RecordingSink(recorded, order),
            view = ObservationView.NORMAL,
            query = identityQuery(),
            emit = { order += "draw-to-terminal" },
            render = {
                order += "draw"
                error("the drawing failed")
            },
        )
        val event = echo("the run decided this")

        assertThrows(IllegalStateException::class.java) { sink.append(event) }

        assertEquals(
            listOf("record", "draw"),
            order,
            "the drawing happened before the decision was written, so a drawing fault takes " +
                "the record of the run with it",
        )
        assertEquals(
            listOf(event),
            recorded,
            "the decision did not reach the store, so the run looks like it never happened",
        )
    }

    private fun identityQuery(): CompiledObservationQuery =
        (compileQuery(ObservationQuery()) as SelectorCompileResult.Ok).value

    private fun echo(content: String): EchoOutputCaptured =
        EchoOutputCaptured(
            eventId = "e-1",
            runId = RUN_ID,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            stepIndex = 0,
            content = content,
        )

    /**
     * A store that remembers what it was given, and when it was given it.
     * The order matters here: the guarantee is about WHICH of the two comes
     * first, and a fake that only remembered the outcome could not tell.
     */
    private class RecordingSink(
        private val recorded: MutableList<DomainEvent>,
        private val order: MutableList<String>,
    ) : EventSink {
        override fun append(event: DomainEvent) {
            recorded += event
            order += "record"
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = recorded.asSequence()
    }

    private companion object {
        const val RUN_ID = "run-console-printing"
    }
}
