package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.CompensationFinished
import dev.rubentxu.pipeline.v2.events.CompensationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.WorkflowLoaded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * ONE vocabulary says which kind belongs to which domain type, and the writer and the reader ask
 * IT instead of keeping their own copies.
 *
 * ## The guarantee
 *
 * A row's `kind` is a string the compiler cannot compare with anything, so a type whose own kind
 * literal stopped naming its constant would travel under a kind no reader could decode, and it
 * would do so silently. The matrix holds that comparison, and these tests hold the matrix:
 *
 *  - the vocabulary is TOTAL over its own constants, and CLOSED over the wire: every constant is
 *    its own wire, resolves back to itself, and is known to the reader;
 *  - the reader and the vocabulary are ONE list: a wire the reader accepts is a wire the
 *    vocabulary holds, and a wire neither holds is version skew rather than corruption;
 *  - the rule itself refuses a disagreement and says WHICH two words disagreed;
 *  - the kind that reaches the wire is the kind the matrix certifies, for every shape an event can
 *    take on the wire.
 *
 * ## What the compiler already holds
 *
 * The matrix binds every domain type exhaustively, so a type nobody entered does not compile, and
 * the decoder's table is exhaustive over the vocabulary, so a constant with no decoder arm does not
 * compile either. These tests are about what the compiler CANNOT hold: the wiring, and the two
 * copies of the words that used to exist beside it.
 */
class EventKindMatrixTest {

    private val occurredAt: Instant = Instant.parse("2026-01-01T00:00:00Z")

    // --- the vocabulary is total over itself ------------------------------------

    @Test
    fun `every constant is its own wire and resolves back to itself`() {
        for (kind in EventKind.entries) {
            assertEquals(
                kind,
                EventKindMatrix.fromWire(kind.wire),
                "the wire of ${kind.name} must resolve to ${kind.name}; a constant that " +
                    "resolves to another one means two kinds share a word",
            )
        }
    }

    @Test
    fun `the reader and the vocabulary are one list, not two`() {
        for (kind in EventKind.entries) {
            assertEquals(
                EventKindMatrix.knows(kind.wire),
                JsonEventLog.knowsKind(kind.wire),
                "the reader accepted ${kind.name} differently from the vocabulary; " +
                    "the two lists are the thing this consolidation removed",
            )
        }
    }

    // --- an unknown wire is version skew, not corruption ------------------------

    @Test
    fun `a kind this binary does not know is unknown to the vocabulary and to the reader`() {
        assertNull(
            EventKindMatrix.fromWire("FromTheFuture"),
            "the vocabulary cannot answer for a word it does not hold",
        )
        assertFalse(EventKindMatrix.knows("FromTheFuture"), "the vocabulary does not hold it")
        assertFalse(
            JsonEventLog.knowsKind("FromTheFuture"),
            "the reader does not hold it either; an unknown kind and an unreadable field are " +
                "different facts and the operator acts on them differently",
        )
    }

    // --- the matrix binds a type to its constant ----------------------------------

    @Test
    fun `the matrix binds each sampled type to its own constant`() {
        assertEquals(EventKind.RunStarted, EventKindMatrix.of(runStarted()))
        assertEquals(EventKind.CompilationStarted, EventKindMatrix.of(compilationStarted()))
        assertEquals(EventKind.WorkflowLoaded, EventKindMatrix.of(workflowLoaded()))
        assertEquals(EventKind.PluginEventEmitted, EventKindMatrix.of(pluginEventEmitted()))
        assertEquals(EventKind.CompensationStarted, EventKindMatrix.of(compensationStarted()))
    }

    @Test
    fun `the rule accepts the kind the type travels as and refuses another one`() {
        assertTrue(
            EventKindMatrix.declares(EventKind.RunStarted, "RunStarted"),
            "the rule accepts the word the type declares",
        )
        assertFalse(
            EventKindMatrix.declares(EventKind.RunStarted, "RunStartedTypo"),
            "the rule refuses a word that is not the one; this predicate is where a drifted " +
                "literal is caught, because the compiler cannot compare two strings",
        )
    }

    @Test
    fun `the writer's refusal names the two words that disagreed`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            EventKindMatrix.certify(EventKind.RunStarted, "RunStartedTypo")
        }

        val message = failure.message.orEmpty()
        assertTrue(
            message.contains("RunStarted") && message.contains("RunStartedTypo"),
            "the refusal must name both words, or an operator cannot tell which one drifted; " +
                "got \"$message\"",
        )
    }

    @Test
    fun `the writer's refusal returns the constant when the two words agree`() {
        assertEquals(
            EventKind.RunStarted,
            EventKindMatrix.certify(EventKind.RunStarted, "RunStarted"),
            "an agreement is not a failure; the writer needs the constant back",
        )
    }

    // --- the kind that reaches the wire is the kind the matrix certifies --------

    @Test
    fun `the kind on the wire is the kind the matrix certifies, in every event shape`() {
        val shapes: List<DomainEvent> = listOf(
            runStarted(),
            compilationStarted(),
            workflowLoaded(),
            pluginEventEmitted(),
            compensationStarted(),
        )

        for (event in shapes) {
            val certified = EventKindMatrix.of(event)
            val row = JsonEventLog.encodeOne(event)

            assertTrue(
                EventKindMatrix.declares(certified, event.kind),
                "${event.kind} does not name the constant the matrix binds its type to",
            )
            assertTrue(
                row.contains("\"kind\":\"${certified.wire}\""),
                "the row of ${certified.name} must travel under the word the matrix certifies; " +
                    "got $row",
            )
        }
    }

    @Test
    fun `every sampled shape survives the round trip under the very same kind`() {
        val shapes: List<DomainEvent> = listOf(
            runStarted(),
            compilationStarted(),
            workflowLoaded(),
            pluginEventEmitted(),
            compensationStarted(),
        )

        for (event in shapes) {
            val decoded = JsonEventLog.decode(JsonEventLog.encode(listOf(event)))

            assertEquals(1, decoded.size, "the row of ${event.kind} did not decode: ${JsonEventLog.encode(listOf(event))}")
            assertEquals(
                event,
                decoded.single(),
                "the round trip of ${event.kind} changed the event",
            )
            assertEquals(
                EventKindMatrix.of(event).wire,
                decoded.single().kind,
                "the round trip of ${event.kind} changed the kind the row travels under",
            )
        }
    }

    @Test
    fun `the versioned field of the plugin carrier survives the round trip`() {
        // The plugin carrier is the one event whose identity is VERSIONED: a payload read under
        // the wrong schema version is a different observation, so the version has to travel.
        val decoded = JsonEventLog.decode(JsonEventLog.encode(listOf(pluginEventEmitted())))

        val carrier = decoded.single() as PluginEventEmitted
        assertEquals(3, carrier.schemaVersion, "the schema version is part of the identity")
        assertEquals("acme.validated", carrier.registryKind)
    }

    // --- the family reads the vocabulary instead of its own copy of the words -----

    @Test
    fun `the compensation family decodes the kind the vocabulary gives it`() {
        // The family used to spell out its own three kinds. A kind renamed in the vocabulary
        // would leave those words behind and the family would answer null for a kind the
        // dispatcher had already recognised -- an event that vanishes without a word. These
        // cases feed the family the word the VOCABULARY gives, so a drift between the two
        // cannot pass unnoticed.
        val started = SagaLoopCodec.decode(
            kind = EventKindMatrix.of(compensationStarted()).wire,
            s = compensationStartedPayload,
            eventId = "e-1",
            runId = "run-42",
            sequence = 7L,
            occurredAt = occurredAt,
        )

        assertEquals(compensationStarted(), started, "the family must answer for the word the vocabulary holds")

        val finished = SagaLoopCodec.decode(
            kind = EventKindMatrix.of(compensationFinished()).wire,
            s = compensationFinishedPayload,
            eventId = "e-1",
            runId = "run-42",
            sequence = 8L,
            occurredAt = occurredAt,
        )

        assertEquals(compensationFinished(), finished, "the family must answer for the word the vocabulary holds")

        val terminal = SagaLoopCodec.decode(
            kind = EventKindMatrix.of(sagaTerminal()).wire,
            s = sagaTerminalPayload,
            eventId = "e-1",
            runId = "run-42",
            sequence = 9L,
            occurredAt = occurredAt,
        )

        assertEquals(sagaTerminal(), terminal, "the family must answer for the word the vocabulary holds")
    }

    @Test
    fun `the compensation family answers for its own kinds only`() {
        // The payload below is a well-formed compensation body, so a family that dispatched on
        // the PAYLOAD's shape instead of on the kind would answer with a compensation. It
        // answers null, because the kind it was asked about belongs to another family.
        val answer = SagaLoopCodec.decode(
            kind = EventKindMatrix.of(workflowLoaded()).wire,
            s = compensationStartedPayload,
            eventId = "e-1",
            runId = "run-42",
            sequence = 7L,
            occurredAt = occurredAt,
        )

        assertNull(
            answer,
            "the family must not answer for a kind it does not own, whatever the payload looks like",
        )
    }

    // --- the events the tests above travel as ------------------------------------

    private fun runStarted() = RunStarted(
        eventId = "e-1",
        runId = "run-42",
        sequence = 7L,
        occurredAt = occurredAt,
        scriptPath = "/pipeline.kts",
    )

    private fun compilationStarted() = CompilationStarted(
        eventId = "e-1",
        runId = "run-42",
        sequence = 7L,
        occurredAt = occurredAt,
    )

    private fun workflowLoaded() = WorkflowLoaded(
        eventId = "e-1",
        runId = "run-42",
        sequence = 7L,
        occurredAt = occurredAt,
        path = "extra/pipeline.kts",
        stepCount = 2,
        sha256 = "a".repeat(64),
    )

    private fun pluginEventEmitted() = PluginEventEmitted(
        eventId = "e-1",
        runId = "run-42",
        sequence = 7L,
        occurredAt = occurredAt,
        registryKind = "acme.validated",
        schemaVersion = 3,
        payload = "{}",
        emittedBy = "acme.plugin",
    )

    private fun compensationStarted() = CompensationStarted(
        eventId = "e-1",
        runId = "run-42",
        sequence = 7L,
        occurredAt = occurredAt,
        sagaId = "saga-1",
        actionId = "action-1",
        compensationId = "comp-1",
        resourceRef = "resource-1",
        slot = "step:compensate",
    )

    private fun compensationFinished() = CompensationFinished(
        eventId = "e-1",
        runId = "run-42",
        sequence = 8L,
        occurredAt = occurredAt,
        sagaId = "saga-1",
        actionId = "action-1",
        compensationId = "comp-1",
        slot = "step:compensate",
        outcome = "CONFIRMED",
    )

    private fun sagaTerminal() = SagaTerminal(
        eventId = "e-1",
        runId = "run-42",
        sequence = 9L,
        occurredAt = occurredAt,
        sagaId = "saga-1",
        state = "SETTLED",
        compensatedActions = emptyList(),
    )

    /** A compensation body, written by hand so this file does not lean on the writer. */
    private val compensationStartedPayload: String =
        """{"eventId":"e-1","runId":"run-42","sequence":7,"kind":"CompensationStarted",""" +
            """"occurredAt":"2026-01-01T00:00:00Z","sagaId":"saga-1","actionId":"action-1",""" +
            """"compensationId":"comp-1","resourceRef":"resource-1","slot":"step:compensate"}"""

    private val compensationFinishedPayload: String =
        """{"eventId":"e-1","runId":"run-42","sequence":8,"kind":"CompensationFinished",""" +
            """"occurredAt":"2026-01-01T00:00:00Z","sagaId":"saga-1","actionId":"action-1",""" +
            """"compensationId":"comp-1","slot":"step:compensate","outcome":"CONFIRMED"}"""

    private val sagaTerminalPayload: String =
        """{"eventId":"e-1","runId":"run-42","sequence":9,"kind":"SagaTerminal",""" +
            """"occurredAt":"2026-01-01T00:00:00Z","sagaId":"saga-1","state":"SETTLED",""" +
            """"compensatedActions":[]}"""
}
