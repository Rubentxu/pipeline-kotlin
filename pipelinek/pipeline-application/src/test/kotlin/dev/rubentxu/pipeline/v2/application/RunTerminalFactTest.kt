package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.EventStore
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventId
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRef
import dev.rubentxu.pipeline.v2.events.identity.EventTail
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * El drain dice que el run terminó, y lo dice por las filas que el almacén devolvió.
 *
 * ## La garantía
 * Un follow con filtro termina cuando el run termina. El filtro decide qué hechos recibe el
 * cliente; no decide si el run se acabó. La respuesta se lee en las filas del almacén, que es
 * donde vive el hecho terminal, y no en los hechos entregados, que es donde el filtro decide.
 *
 * ## El fallo que esta forma tapa
 * El follow buscaba el hecho terminal entre los hechos que acababa de entregar. Con un filtro que
 * lo excluía —`--kind StepFinished`— no lo veía nunca, así que no terminaba nunca: el cursor del
 * almacén nunca es nulo, con lo que la salida de «no hay más filas» era inalcanzable, y el follow
 * sondeaba para siempre sobre un run que había acabado hacía minutos. Medido contra la
 * distribución: `exit 124`; el mismo follow sin filtro salía con 0 enseguida.
 *
 * ## Por qué esto NO lo puede observar la capa ejecutable
 * El verbo `events` imprime los hechos en `stdout` y el cursor en `stderr`. El lanzador de
 * ejemplos lee el código de salida, y un follow que no termina se ve como un timeout: se puede
 * observar, pero sólo como un «no terminó», sin poder distinguir por qué. La garantía de QUÉ
 *rows se miraron para decidir está en un canal que la capa ejecutable no lee, y por eso necesita
 * un instrumento que sí lo lea: un doble en memoria del puerto de lectura, que deja construir la
 * historia que hace falta.
 */
class RunTerminalFactTest {

    /**
     * El almacén en memoria, con las reglas del almacén real: la página se corta por FILAS, y el
     * cursor es la secuencia de la última fila de la página.
     */
    private class FakeTail(
        private val rows: List<PipelineEventEnvelope>,
    ) : EventTail {
        override fun readAfter(
            run: ResourceRef,
            cursor: EventCursor?,
            limit: Int,
        ): EventPage {
            val after = cursor?.lastSequence ?: 0L
            val slice = rows.filter { it.sequence > after }.take(limit)
            return EventPage(
                envelopes = slice,
                nextCursor = EventCursor(run.segments.last(), slice.lastOrNull()?.sequence ?: after),
                hasMore = rows.any { it.sequence > (slice.lastOrNull()?.sequence ?: after) },
            )
        }
    }

    /**
     * El almacén tipado, con las mismas reglas: la página se corta por FILAS y el cursor es la
     * secuencia de la última fila.
     */
    private class FakeStore(
        private val events: List<DomainEvent>,
    ) : EventStore {
        override fun readRecords(
            runId: String,
            after: EventCursor?,
            limit: Int,
        ): EventRecordSlice {
            val from = after?.lastSequence ?: 0L
            val slice = events.filter { it.sequence > from }.take(limit)
            return EventRecordSlice(
                records = slice.map { EventRecordRead.Decoded(it) },
                nextCursor = EventCursor(runId, slice.lastOrNull()?.sequence ?: from),
                hasMore = events.any { it.sequence > (slice.lastOrNull()?.sequence ?: from) },
            )
        }

        override fun append(event: DomainEvent) = Unit

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    private val run: ResourceRef = ResourceRefs.run("run-1")

    private fun row(sequence: Long, kind: String): PipelineEventEnvelope {
        val source = ResourceRefs.run("run-1")
        return PipelineEventEnvelope(
            version = PipelineEventEnvelope.VERSION,
            eventRef = EventRef(source, EventId("evt-$sequence")),
            kind = kind,
            occurredAt = Instant.EPOCH,
            sequence = sequence,
            subject = source,
        )
    }

    /** La historia de un run que ya terminó: el hecho terminal es la última fila. */
    private val finishedHistory = listOf(
        row(1, "RunStarted"),
        row(2, "StepStarted"),
        row(3, "StepFinished"),
        row(4, "RunFinished"),
    )

    private fun stepEvent(sequence: Long): DomainEvent = StepFinished(
        eventId = "evt-$sequence",
        runId = "run-1",
        sequence = sequence,
        occurredAt = Instant.EPOCH,
        stageIndex = 0,
        stepIndex = 0,
        stepName = "sh-0",
        stepType = "sh",
        outcome = "success",
    )

    @Test
    @DisplayName("el drain dice que el run terminó aunque el filtro excluya el hecho terminal")
    fun `the drain reports that the run ended even when the filter excludes the terminal fact`() {
        val outcome = EventPageDrain.drain(
            FakeTail(finishedHistory),
            run,
            EventQuery.ByKind("StepFinished"),
            cursor = null,
            limit = 10,
        )

        // El cliente recibe lo que pidió: un solo hecho, el del step.
        assertEquals(1, outcome.page.envelopes.size)
        assertEquals("StepFinished", outcome.page.envelopes.single().kind)
        // Y el drain sabe, sin embargo, que el run se acabó: lo leyó en las filas del almacén.
        assertTrue(outcome.runEnded, "the store's rows hold the run's terminal fact")
    }

    @Test
    @DisplayName("el drain no dice que el run terminó mientras sigue vivo")
    fun `the drain does not report that the run ended while it is still running`() {
        val alive = listOf(row(1, "RunStarted"), row(2, "StepStarted"))

        val outcome = EventPageDrain.drain(
            FakeTail(alive),
            run,
            EventQuery.ByKind("StepFinished"),
            cursor = null,
            limit = 10,
        )

        assertFalse(outcome.runEnded, "no terminal fact has been written yet")
    }

    @Test
    @DisplayName("el hecho terminal se lee de las filas leídas, no de las entregadas")
    fun `the terminal fact is read from the rows read, not from the facts handed out`() {
        // Un límite de uno: el drain entrega el primer hecho que coincide y PARA, así que no llega
        // a leer la fila 4. El hecho terminal no se inventa: se dice sólo de lo que se leyó, y el
        // follow sigue hacia delante con la fila 3 como cursor.
        val outcome = EventPageDrain.drain(
            FakeTail(finishedHistory),
            run,
            EventQuery.ByKind("StepFinished"),
            cursor = null,
            limit = 1,
        )

        assertEquals(1, outcome.page.envelopes.size)
        assertFalse(outcome.runEnded, "the terminal fact was not read yet")
        assertEquals(3L, outcome.page.nextCursor?.lastSequence, "the cursor is the last fact handed out")
    }

    @Test
    @DisplayName("el drain tipado dice lo mismo que el de sobres")
    fun `the typed drain reports the same fact as the envelope one`() {
        // Las dos proyecciones owe al cliente el mismo cursor y la misma regla; un cuerpo de
        // verdad que las separe es la divergencia que este test existe para cazar.
        val events = listOf(
            RunStarted("evt-1", "run-1", 1L, Instant.EPOCH, "pipeline.kts"),
            stepEvent(3),
            RunFinished("evt-4", "run-1", 4L, Instant.EPOCH, "success", emptyList()),
        )

        val outcome = EventPageDrain.drainTyped(
            FakeStore(events),
            run,
            EventQuery.ByKind("StepFinished"),
            cursor = null,
            limit = 10,
        )

        assertEquals(1, outcome.page.events.size)
        assertTrue(outcome.runEnded, "the store's records hold the run's terminal fact")
    }
}
