package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventId
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRef
import dev.rubentxu.pipeline.v2.events.identity.EventTail
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La paginación que sostiene el seam externo de streaming.
 *
 * ## La garantía
 * Un cliente externo lee la historia por cursor: pide una página, recibe hechos y un cursor, se
 * desconecta, vuelve con el cursor y sigue. Lo que la garantía promete es que entre una lectura
 * y la siguiente **no se salta ningún hecho y no se repite ninguno**. El cursor es la posición
 * del cliente, y la posición del cliente sólo sirve si el.store no la adelanta.
 *
 * ## Por qué esto NO lo puede observar la capa ejecutable
 * El verbo `events` escribe los hechos en `stdout` y el cursor en `stderr`. El lanzador de
 * ejemplos lee el código de salida y, como mucho, una subcadena de `stderr`: no abre `stdout`,
 * así que no puede contar hechos ni compararlos entre dos invocaciones. La garantía vive en un
 * canal que la capa ejecutable no mira, y por eso necesita un instrumento que sí lo mire: un
 * doble en memoria del puerto de lectura, que deja construir la historia que hace falta.
 *
 * ## La asimetría que esta historia expone
 * La página del almacén está limitada por **filas**, y el límite del verbo cuenta **hechos que
 * coinciden con el filtro**. Son dos cuentas distintas sobre la misma página, y por eso el
 * recorrido puede parar a mitad de página: cuando los hechos coincididos acumulados llegan al
 * límite, el resto de la página —que el almacén ya leyó— se queda sin entregar. Si el cursor que
 * se devuelve es el de la última FILA de la página, esos hechos sobrantes quedan **detrás** del
 * cursor, y el cliente que vuelve no los verá jamás.
 *
 * La historia de este test es mínima y está construida para que eso ocurra: la fila 2 coincide,
 * la 3 coincide, y la 4 también. Con un límite de dos, la primera página (filas 1 y 2) entrega un
 * hecho, y la segunda (filas 3 y 4) entrega otro y agota el límite en su **primera** fila: la
 * cuarta se leyó y no se entregó.
 */
class EventPageDrainTest {

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

    /** La historia que hace la pérdida posible: 2, 3 y 4 coinciden con el filtro. */
    private val history = listOf(
        row(1, "Other"),
        row(2, "Wanted"),
        row(3, "Wanted"),
        row(4, "Wanted"),
        row(5, "Other"),
    )

    @Test
    @DisplayName("una lectura paginada con filtro entrega los hechos que el cursor salta")
    fun aFilteredPagedReadDeliversEveryMatchingFact() {
        val tail = FakeTail(history)
        val query = EventQuery.ByKind("Wanted")

        // El cliente lee, corta y vuelve: dos llamadas, como dos procesos distintos.
        val first = EventPageDrain.drain(tail, run, query, null, limit = 2)
        val second = EventPageDrain.drain(tail, run, query, first.page.nextCursor, limit = 2)

        val delivered = first.page.envelopes.map { it.sequence } + second.page.envelopes.map { it.sequence }

        assertEquals(
            listOf(2L, 3L, 4L),
            delivered,
            "el cursor no puede adelantar a los hechos que el cliente todavía no ha visto",
        )
    }

    @Test
    @DisplayName("el cursor de continuación es la posición del último hecho entregado")
    fun theContinuationIsThePositionOfTheLastDeliveredFact() {
        val tail = FakeTail(history)

        val outcome = EventPageDrain.drain(tail, run, EventQuery.ByKind("Wanted"), null, limit = 2)
        val cursor = outcome.page.nextCursor

        assertEquals(
            listOf(2L, 3L),
            outcome.page.envelopes.map { it.sequence },
            "la primera lectura entrega hasta agotar el límite",
        )
        assertTrue(
            cursor != null && cursor.lastSequence == 3L,
            "el cursor debe apuntar al último hecho entregado (3), no más allá: si apunta más " +
                "lejos, los hechos que el cliente no ha visto quedan detrás del cursor y se " +
                "pierden. Cursor emitido: ${outcome.page.nextCursor}",
        )
    }

    @Test
    @DisplayName("sin filtro, la paginación entrega la historia entera sin repetir ni saltar")
    fun withoutAFilterTheWholeHistoryArrivesWithoutRepeatsOrGaps() {
        val tail = FakeTail(history)

        var cursor: EventCursor? = null
        val delivered = mutableListOf<Long>()
        var rounds = 0
        while (rounds < 10) {
            rounds++
            val outcome = EventPageDrain.drain(tail, run, EventQuery.All, cursor, limit = 2)
            delivered.addAll(outcome.page.envelopes.map { e -> e.sequence })
            val next = outcome.page.nextCursor
            if (next == null || next == cursor) break
            cursor = next
        }

        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L),
            delivered,
            "sin filtro, leer por cursor entrega cada fila exactamente una vez",
        )
    }

    /**
     * La otra mitad de la continuación: la bandera, no sólo el cursor.
     *
     * ## La garantía
     * El cliente decide si vuelve a preguntar leyendo `hasMore`. Esa bandera describe lo que queda
     * **por delante del cliente**, no lo que queda por delante del almacén: son dos horizontes
     * distintos cuando el corte es por límite, porque la página está acotada por filas y el límite
     * cuenta hechos que coinciden. Un hecho que el almacén leyó, que coincide con el filtro y que
     * el corte del límite dejó sin entregar sigue estando por delante del cliente, aunque el
     * almacén haya terminado de leer. Copiar la bandera del almacén en ese corte le dice al
     * cliente «no queda nada» con un hecho vivo detrás, y el cliente no vuelve: el hecho se pierde
     * con un código de salida cero.
     *
     * ## Por qué esta historia y no la del cursor
     * La historia del cursor tiene una fila 5 tras la cortada, así que la bandera del almacén
     * sigue en true y el defecto queda enmascarado. Ésta termina justo en la página del corte: el
     * almacén agota la historia en la misma página en la que el límite del cliente corta, que es
     * el único caso en el que las dos banderas se separan.
     */
    @Test
    @DisplayName("un hecho cortado por el límite deja la bandera en true aunque el almacén terminó")
    fun aLimitCutMatchingFactKeepsTheFlagUp() {
        val tail = FakeTail(
            listOf(
                row(1, "Other"),
                row(2, "Wanted"),
                row(3, "Wanted"),
                row(4, "Wanted"),
            ),
        )

        val outcome = EventPageDrain.drain(tail, run, EventQuery.ByKind("Wanted"), null, limit = 2)

        assertEquals(
            listOf(2L, 3L),
            outcome.page.envelopes.map { it.sequence },
            "la lectura entrega hasta agotar el límite",
        )
        assertTrue(
            outcome.page.hasMore,
            "la fila 4 se leyó, coincide y no se entregó: la bandera debe decir que queda " +
                "trabajo por delante del cliente aunque el almacén haya terminado de leer. " +
                "Bandera emitida: ${outcome.page.hasMore}",
        )
    }

    /**
     * La misma bandera, la misma regla, la otra proyección.
     *
     * El drenador tipado tiene su propio bucle sobre otro puerto, y una regla de paginación que
     * existe en una proyección y no en la otra es la divergencia que sobrevive a la revisión.
     */
    @Test
    @DisplayName("la proyección tipada también levanta la bandera cuando el corte dejó un hecho atrás")
    fun theTypedProjectionRaisesTheFlagToo() {
        val store = InMemoryEventStore()
        store.append(RunStarted("evt-1", "run-1", 0L, Instant.EPOCH, "p.pipeline.kts"))
        store.append(StageStarted("evt-2", "run-1", 0L, Instant.EPOCH, 0, "stage-0"))
        store.append(StageStarted("evt-3", "run-1", 0L, Instant.EPOCH, 1, "stage-1"))
        store.append(StageStarted("evt-4", "run-1", 0L, Instant.EPOCH, 2, "stage-2"))

        val outcome = EventPageDrain.drainTyped(store, run, EventQuery.ByKind("StageStarted"), null, limit = 2)

        assertEquals(
            listOf(2L, 3L),
            outcome.page.events.map { it.sequence },
            "la lectura entrega hasta agotar el límite",
        )
        assertTrue(
            outcome.page.hasMore,
            "el StageStarted de la secuencia 4 se leyó, coincide y no se entregó: la bandera " +
                "debe decir que queda trabajo por delante del cliente aunque el almacén haya " +
                "terminado de leer. Bandera emitida: ${outcome.page.hasMore}",
        )
    }

    /**
     * La misma garantía en la proyección tipada.
     *
     * El drenador tipado lee por otro puerto (`EventStore.readRecords` en vez de `EventTail`) y
     * tiene su propio bucle, pero le debe al cliente **el mismo cursor**. Una regla de paginación
     * que existe en una proyección y no en la otra es exactamente la divergencia que sobrevive a
     * la revisión: aquí el doble es el almacén real en memoria, que tiene la misma ley de
     * paginación que el de disco (página limitada por filas, continuación en la última fila).
     */
    @Test
    @DisplayName("la proyección tipada tampoco pierde hechos al reanudar")
    fun theTypedProjectionLosesNoFactsEither() {
        val store = InMemoryEventStore()
        store.append(RunStarted("evt-1", "run-1", 0L, Instant.EPOCH, "p.pipeline.kts"))
        store.append(StageStarted("evt-2", "run-1", 0L, Instant.EPOCH, 0, "stage-0"))
        store.append(StageStarted("evt-3", "run-1", 0L, Instant.EPOCH, 1, "stage-1"))
        store.append(StageStarted("evt-4", "run-1", 0L, Instant.EPOCH, 2, "stage-2"))
        store.append(StageFinished("evt-5", "run-1", 0L, Instant.EPOCH, 3, "stage-3", "success"))

        val query = EventQuery.ByKind("StageStarted")
        val first = EventPageDrain.drainTyped(store, run, query, null, limit = 2)
        val second = EventPageDrain.drainTyped(store, run, query, first.page.nextCursor, limit = 2)

        val delivered = first.page.events.map { it.sequence } + second.page.events.map { it.sequence }

        assertEquals(
            listOf(2L, 3L, 4L),
            delivered,
            "el cursor no puede adelantar a los hechos que el cliente todavía no ha visto",
        )
    }
}
