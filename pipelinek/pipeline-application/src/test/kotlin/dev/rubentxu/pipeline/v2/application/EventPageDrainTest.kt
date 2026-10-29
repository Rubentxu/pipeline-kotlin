package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.EventStore
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.UndecodableReason
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
 * del cliente, y la posición del cliente sólo sirve si el store no la adelanta.
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
 *
 * ## La segunda mitad: los rechazos también son posiciones
 * Una fila ilegible no es un evento, pero es una FILA: ocupa su lugar en la secuencia y el almacén
 * la cuenta. El cliente la recibe como un rechazo, y un rechazo es una entrega — así que el
 * cursor tiene que cubrirlo, igual que cubre el último hecho. Y al revés: el cursor no puede
 * adelantarse al rechazo para cubrirlo si con eso salta un hecho coincidente que quedó sin
 * entregar. Las dos mitades se resuelven con una sola regla, y esta clase la ejerce en las dos
 * proyecciones: la de envelopes y la tipada.
 *
 * Por eso el doble tiene que saber fabricar FILAS y no sólo envelopes: una historia sin una fila
 * ilegible no puede observar la ley de los rechazos, y una ley sin instrumento es una intención.
 */
class EventPageDrainTest {

    /**
     * Una fila durable tal como el almacén la corta: un envelope legible o un rechazo.
     *
     * El rechazo ocupa su lugar en la secuencia igual que un envelope: no es un evento, pero es
     * una FILA, y la página se corta por filas. Un doble que sólo sabe fabricar envelopes no
     * puede representar el caso que la ley de los rechazos trata, que es precisamente el de una
     * fila que existe y no se puede leer.
     */
    private sealed interface TailRow {
        val sequence: Long

        data class Valid(val envelope: PipelineEventEnvelope) : TailRow {
            override val sequence: Long get() = envelope.sequence
        }

        data class Refused(val refusal: EventRecordRead.Undecodable) : TailRow {
            override val sequence: Long get() = refusal.sequence
        }
    }

    /**
     * El almacén en memoria, con las reglas del almacén real: la página se corta por FILAS, y el
     * cursor es la secuencia de la última fila de la página — legible o no. Un rechazo que ocupa
     * la última fila avanza el cursor igual que un envelope: dejarlo fuera contaría la página dos
     * veces.
     */
    private class FakeTail(
        private val rows: List<TailRow>,
        private val maxReads: Int = 16,
    ) : EventTail {
        /**
         * Cuántas páginas se han pedido.
         *
         * El número dice si el drenador AVANZÓ: una página que no entrega ningún hecho no es el
         * final de la historia, así que el drenador tiene que pedir otra. Y un bucle que vuelve a
         * preguntar sin consumir se corta aquí, con un error, en vez de dejar la prueba colgada:
         * una prueba que se cuelga no es una prueba, es un proceso huérfano esperando un tope
         * que nadie puso.
         */
        var reads: Int = 0
            private set

        override fun readAfter(
            run: ResourceRef,
            cursor: EventCursor?,
            limit: Int,
        ): EventPage {
            reads++
            check(reads <= maxReads) {
                "el drenador pidió $reads páginas y el almacén ya no contesta: el bucle no avanza"
            }
            val after = cursor?.lastSequence ?: 0L
            val cut = rows.filter { it.sequence > after }.take(limit)
            val lastCut = cut.lastOrNull()?.sequence ?: after
            return EventPage(
                envelopes = cut.mapNotNull { (it as? TailRow.Valid)?.envelope },
                nextCursor = EventCursor(run.segments.last(), lastCut),
                hasMore = rows.any { it.sequence > lastCut },
                refusals = cut.mapNotNull { (it as? TailRow.Refused)?.refusal },
            )
        }
    }

    /**
     * El mismo almacén por el otro puerto: el que devuelve REGISTROS, con la fila ilegible
     * intercalada en su sitio. El corte es por filas y el cursor es la última fila de la página,
     * legible o no — la misma ley que el almacén real, porque un doble que la rompe no mide nada.
     */
    private class FakeRecordStore(
        private val records: List<EventRecordRead>,
        private val maxReads: Int = 16,
    ) : EventStore {
        /** Cuántas páginas se han pedido; el mismo papel que en el doble de envelopes. */
        var reads: Int = 0
            private set

        override fun append(event: DomainEvent) = Unit

        override fun eventsFor(runId: String): Sequence<DomainEvent> =
            records.mapNotNull { (it as? EventRecordRead.Decoded)?.event }.asSequence()

        override fun readRecords(runId: String, after: EventCursor?, limit: Int): EventRecordSlice {
            reads++
            check(reads <= maxReads) {
                "el drenador pidió $reads páginas y el almacén ya no contesta: el bucle no avanza"
            }
            val afterSequence = after?.lastSequence ?: 0L
            val cut = records.filter { it.sequence > afterSequence }.take(limit)
            val lastCut = cut.lastOrNull()?.sequence ?: afterSequence
            return EventRecordSlice(
                records = cut,
                nextCursor = EventCursor(runId, lastCut),
                hasMore = records.any { it.sequence > lastCut },
            )
        }
    }

    /**
     * El almacén que promete más de lo que entrega: su continuación no se mueve.
     *
     * Es una avería de un tercero, no un caso del producto: los dos almacenes que hay cortan la
     * página por filas y su continuación avanza con la última fila leída. Aquí el doble rompe
     * esa ley a propósito, porque la pregunta que hace falta responder es qué contesta el
     * drenador cuando el almacén no deja avanzar y todavía dice que queda más.
     */
    private class StuckTail : EventTail {
        /** Cuántas páginas se han pedido; el tope corta el bucle en vez de dejarlo colgado. */
        var reads: Int = 0
            private set

        override fun readAfter(
            run: ResourceRef,
            cursor: EventCursor?,
            limit: Int,
        ): EventPage {
            reads++
            check(reads <= 8) {
                "el drenador volvió a preguntar sin que la continuación cambiara: gira en vacío"
            }
            return EventPage(
                envelopes = emptyList(),
                nextCursor = EventCursor(run.segments.last(), cursor?.lastSequence ?: 0L),
                hasMore = true,
                refusals = emptyList(),
            )
        }
    }

    /** El mismo almacén atascado por el otro puerto: el que devuelve registros. */
    private class StuckRecordStore : EventStore {
        /** Cuántas páginas se han pedido; el tope corta el bucle en vez de dejarlo colgado. */
        var reads: Int = 0
            private set

        override fun append(event: DomainEvent) = Unit

        override fun eventsFor(runId: String): Sequence<DomainEvent> = emptySequence()

        override fun readRecords(runId: String, after: EventCursor?, limit: Int): EventRecordSlice {
            reads++
            check(reads <= 8) {
                "el drenador volvió a preguntar sin que la continuación cambiara: gira en vacío"
            }
            return EventRecordSlice(
                records = emptyList(),
                nextCursor = EventCursor(runId, after?.lastSequence ?: 0L),
                hasMore = true,
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

    /** Una fila que existe y no se puede leer: el almacén la cuenta y la secuencia la nombra. */
    private fun refusal(sequence: Long, kind: String = "GhostKind"): EventRecordRead.Undecodable =
        EventRecordRead.Undecodable(
            sequence = sequence,
            kind = kind,
            eventId = "evt-$sequence",
            reason = UndecodableReason.UnknownKind(kind),
        )

    /** La historia que hace la pérdida posible: 2, 3 y 4 coinciden con el filtro. */
    private val history = listOf(
        row(1, "Other"),
        row(2, "Wanted"),
        row(3, "Wanted"),
        row(4, "Wanted"),
        row(5, "Other"),
    )

    private fun valid(rows: List<PipelineEventEnvelope>): List<TailRow> =
        rows.map { TailRow.Valid(it) }

    @Test
    @DisplayName("una lectura paginada con filtro entrega los hechos que el cursor salta")
    fun aFilteredPagedReadDeliversEveryMatchingFact() {
        val tail = FakeTail(valid(history))
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
        val tail = FakeTail(valid(history))

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
        val tail = FakeTail(valid(history))

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
            valid(
                listOf(
                    row(1, "Other"),
                    row(2, "Wanted"),
                    row(3, "Wanted"),
                    row(4, "Wanted"),
                ),
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

    /**
     * La historia que separa las dos mitades de la ley de los rechazos.
     *
     * ## El reparto
     * Las filas 1, 2, 5, 6 y 7 coinciden con el filtro; la 3 y la 4 no. La fila 8 no se puede
     * leer: existe, ocupa su lugar en la secuencia y el almacén la cuenta. Con un límite de
     * cuatro, la primera página (filas 1 a 4) entrega dos hechos y la segunda (filas 5 a 8)
     * entrega dos más y agota el límite: la **fila 7 coincide y no se entrega**, y detrás de ella
     * está la fila 8, que es un rechazo.
     *
     * ## Por qué esta historia y no una más simple
     * El rechazo tiene que quedar DETRÁS de un hecho coincidente que el corte deja sin entregar.
     * Si el rechazo fuese la última fila y no hubiera nada coincidente sin entregar detrás, el
     * cursor podría cubrirlo sin perder nada y el caso no distinguiría ninguna ley. Con esta
     * historia, un cursor que avanza hasta el rechazo (8) salta el hecho 7 y lo pierde; un
     * cursor que se queda en el hecho entregado (6) deja el rechazo sin informar, y un rechazo
     * informado sin cursor que lo cubra se vuelve a informar en la lectura siguiente.
     */
    private fun rowsWithATrailingRefusal(): List<TailRow> = listOf(
        TailRow.Valid(row(1, "Wanted")),
        TailRow.Valid(row(2, "Wanted")),
        TailRow.Valid(row(3, "Other")),
        TailRow.Valid(row(4, "Other")),
        TailRow.Valid(row(5, "Wanted")),
        TailRow.Valid(row(6, "Wanted")),
        TailRow.Valid(row(7, "Wanted")),
        TailRow.Refused(refusal(8)),
    )

    /**
     * ## La garantía
     * Un rechazo se informa **una vez** y el cursor que lo acompaña lo cubre.
     *
     * ## Por qué la notificación y el cursor no pueden separarse
     * El cursor es la posición del cliente: lo que está por detrás ya se le entregó o ya se
     * consideró. Un rechazo informado es una entrega: si el cursor no lo cubre, la lectura
     * siguiente vuelve a encontrar la fila y vuelve a informar el mismo rechazo. Y al revés: si
     * el cursor se adelanta al rechazo para cubrirlo, se salta el hecho que coincide y quedó
     * sin entregar, y ese hecho se pierde con un código de salida cero.
     *
     * ## La regla que resuelve las dos cosas a la vez
     * Un rechazo puede informarse **cuando no hay ningún hecho coincidente sin entregar por
     * delante de él**. Entonces, y sólo entonces, el cursor puede cubrirlo: nadie queda detrás.
     *
     * ## Por qué esto NO lo puede observar la capa ejecutable
     * El CLI informa los rechazos por `stderr` una línea por rechazo, y una fila ilegible sólo
     * existe si alguien la escribió: no hay verbo que produzca una. Un escenario de ejemplos
     * que fabricase esa fila estaría probando su propio generador, no el producto. La garantía
     * necesita un instrumento que la pueda construir: un doble del puerto de lectura.
     */
    @Test
    @DisplayName("un rechazo detrás de un hecho sin entregar no se informa todavía")
    fun aRefusalBehindAnUndeliveredFactIsNotNotifiedYet() {
        val tail = FakeTail(rowsWithATrailingRefusal())

        val first = EventPageDrain.drain(tail, run, EventQuery.ByKind("Wanted"), null, limit = 4)

        assertEquals(
            listOf(1L, 2L, 5L, 6L),
            first.page.envelopes.map { it.sequence },
            "la primera lectura entrega los hechos que coinciden hasta agotar el límite",
        )
        assertEquals(
            emptyList<Long>(),
            first.page.refusals.map { it.sequence },
            "el rechazo de la secuencia 8 está detrás del hecho 7, que coincide y no se entregó: " +
                "informarlo ahora obligaría al cursor a adelantarse hasta el 8 y perdería el 7. " +
                "Rechazos emitidos: ${first.page.refusals.map { it.sequence }}",
        )
        assertTrue(
            first.page.nextCursor?.lastSequence == 6L,
            "el cursor no puede pasar del último hecho entregado (6): ni al 7, que no se entregó, " +
                "ni al 8, que es un rechazo todavía sin informar. Cursor emitido: " +
                "${first.page.nextCursor}",
        )
        assertTrue(
            first.page.hasMore,
            "el hecho 7 sigue por delante del cliente: la bandera debe decir que queda trabajo",
        )
    }

    /**
     * La mitad que cierra la ley: reanudar no duplica el rechazo ni lo omite.
     *
     * ## La garantía
     * Cuando el cliente vuelve con el cursor que recibió, el rechazo pendiente se informa **una
     * sola vez** y el cursor nuevo lo cubre, de modo que una tercera lectura no vuelve a
     * encontrarlo.
     */
    @Test
    @DisplayName("reanudar informa el rechazo una sola vez y el cursor lo cubre")
    fun resumingNotifiesTheRefusalOnceAndTheCursorCoversIt() {
        val tail = FakeTail(rowsWithATrailingRefusal())
        val query = EventQuery.ByKind("Wanted")

        val first = EventPageDrain.drain(tail, run, query, null, limit = 4)
        val second = EventPageDrain.drain(tail, run, query, first.page.nextCursor, limit = 4)

        assertEquals(
            listOf(7L),
            second.page.envelopes.map { it.sequence },
            "la segunda lectura entrega el hecho coincidente que quedó atrás",
        )
        assertEquals(
            listOf(8L),
            second.page.refusals.map { it.sequence },
            "ya no hay ningún hecho coincidente sin entregar por delante: el rechazo se informa",
        )
        assertTrue(
            second.page.nextCursor?.lastSequence == 8L,
            "el cursor debe cubrir el rechazo que se acaba de informar; si no lo cubre, la " +
                "lectura siguiente lo encontrará otra vez y lo informará otra vez. Cursor " +
                "emitido: ${second.page.nextCursor}",
        )
        assertEquals(
            1,
            first.page.refusals.size + second.page.refusals.size,
            "el rechazo se informa exactamente una vez entre las dos lecturas",
        )
        assertTrue(
            !second.page.hasMore,
            "no queda nada por delante del cliente: la historia terminó con el rechazo",
        )
    }

    /**
     * La misma ley en la proyección tipada.
     *
     * El drenador tipado lee por otro puerto (`EventStore.readRecords`) y tiene su propio bucle,
     * pero la ley del rechazo es la misma: un rechazo informado, un cursor que lo cubre. Aquí el
     * doble es un almacén que devuelve REGISTROS, con la fila ilegible intercalada en su sitio,
     * que es la única forma de que la fila exista.
     */
    @Test
    @DisplayName("la proyección tipada cumple la misma ley de rechazos")
    fun theTypedProjectionObeysTheSameRefusalLaw() {
        val store = FakeRecordStore(
            listOf(
                EventRecordRead.Decoded(StageStarted("evt-1", "run-1", 1L, Instant.EPOCH, 0, "stage-0")),
                EventRecordRead.Decoded(StageStarted("evt-2", "run-1", 2L, Instant.EPOCH, 1, "stage-1")),
                EventRecordRead.Decoded(StageFinished("evt-3", "run-1", 3L, Instant.EPOCH, 2, "stage-2", "success")),
                EventRecordRead.Decoded(StageFinished("evt-4", "run-1", 4L, Instant.EPOCH, 3, "stage-3", "success")),
                EventRecordRead.Decoded(StageStarted("evt-5", "run-1", 5L, Instant.EPOCH, 4, "stage-4")),
                EventRecordRead.Decoded(StageStarted("evt-6", "run-1", 6L, Instant.EPOCH, 5, "stage-5")),
                EventRecordRead.Decoded(StageStarted("evt-7", "run-1", 7L, Instant.EPOCH, 6, "stage-6")),
                refusal(8),
            ),
        )
        val query = EventQuery.ByKind("StageStarted")

        val first = EventPageDrain.drainTyped(store, run, query, null, limit = 4)
        val second = EventPageDrain.drainTyped(store, run, query, first.page.nextCursor, limit = 4)

        assertEquals(
            listOf(1L, 2L, 5L, 6L),
            first.page.events.map { it.sequence },
            "la primera lectura entrega los hechos que coinciden hasta agotar el límite",
        )
        assertEquals(
            emptyList<Long>(),
            first.page.refusals.map { it.sequence },
            "el rechazo de la secuencia 8 está detrás del hecho 7, que coincide y no se entregó: " +
                "informarlo ahora obligaría al cursor a adelantarse hasta el 8 y perdería el 7. " +
                "Rechazos emitidos: ${first.page.refusals.map { it.sequence }}",
        )
        assertEquals(
            listOf(7L),
            second.page.events.map { it.sequence },
            "la segunda lectura entrega el hecho coincidente que quedó atrás",
        )
        assertEquals(
            listOf(8L),
            second.page.refusals.map { it.sequence },
            "ya no hay ningún hecho coincidente sin entregar por delante: el rechazo se informa",
        )
        assertTrue(
            second.page.nextCursor?.lastSequence == 8L,
            "el cursor debe cubrir el rechazo que se acaba de informar; si no lo cubre, la " +
                "lectura siguiente lo encontrará otra vez y lo informará otra vez. Cursor " +
                "emitido: ${second.page.nextCursor}",
        )
        assertEquals(
            1,
            first.page.refusals.size + second.page.refusals.size,
            "el rechazo se informa exactamente una vez entre las dos lecturas",
        )
    }

    /**
     * Una página que no entrega ningún hecho porque todas sus filas son ilegibles.
     *
     * ## La garantía
     * Una página de sólo rechazos no es una página vacía: son filas que existen y que el cliente
     * tiene que conocer. El drenador que la toma por el final de la historia se queda con los
     * rechazos a medias, y el cliente reanuda desde un cursor que no cubre la última fila: vuelve
     * a encontrar esa fila y vuelve a informar el mismo rechazo.
     *
     * El drenador que, en cambio, la trata como trabajo pendiente sigue pidiendo páginas hasta
     * que el almacén dice que no hay más, y entonces informa todos los rechazos.
     *
     * ## Por qué esto NO lo puede observar la capa ejecutable
     * Ningún verbo escribe una fila ilegible: el almacén en disco sólo la tiene si otro proceso
     * escribió un tipo de evento que este binario no conoce. Un escenario de ejemplos que la
     * fabricase probaría su propio generador, no el producto. La garantía necesita un
     * instrumento que sepa construir la fila, que es el doble del puerto de lectura.
     */
    @Test
    @DisplayName("una página de sólo rechazos avanza y los informa todos")
    fun aPageOfOnlyRefusalsAdvancesAndReportsThemAll() {
        val tail = FakeTail(
            listOf(
                TailRow.Refused(refusal(1)),
                TailRow.Refused(refusal(2)),
                TailRow.Refused(refusal(3)),
            ),
        )

        val outcome = EventPageDrain.drain(tail, run, EventQuery.All, null, limit = 2)

        assertEquals(
            2,
            tail.reads,
            "la primera página no entrega ningún hecho, así que el drenador tiene que pedir la " +
                "segunda: quedarse en la primera deja la fila 3 sin conocer",
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            outcome.page.refusals.map { it.sequence },
            "las tres filas ilegibles se informan: cada una ocupa su sitio en la secuencia",
        )
        assertEquals(
            emptyList<Long>(),
            outcome.page.envelopes.map { it.sequence },
            "ninguna fila de esta historia es un hecho entregable",
        )
        assertTrue(
            outcome.page.nextCursor?.lastSequence == 3L,
            "sin hechos entregados, el cursor es la posición observada del almacén: la última " +
                "fila leída. Cursor emitido: ${outcome.page.nextCursor}",
        )
        assertTrue(
            !outcome.page.hasMore,
            "el almacén agotó la historia: no queda nada por delante del cliente",
        )
    }

    /**
     * Un filtro que no casa con ninguna fila.
     *
     * ## La garantía
     * Un filtro que no coincide con nada no puede devolver una respuesta vacía como si fuera el
     * final de la historia: el almacén puede tener páginas enteras por delante y el cliente tiene
     * que preguntar hasta que el almacén conteste que no hay más. El drenador que interpretara
     * «ningún hecho en esta página» como «no hay más» perdería la historia entera con un código de
     * salida cero.
     *
     * Y el que no consumiera una página en cada ronda se quedaría girando: el almacén contesta
     * «queda más» mientras queden filas, así que un bucle que no avanza no termina nunca.
     *
     * ## Por qué el doble cuenta las lecturas
     * El número de páginas leídas es la prueba de las dos cosas: si el drenador se parase antes
     * de tiempo contaría menos, y si girara el doble se negaría a contestar una cuarta vez y la
     * prueba se caería con un error en vez de con un silencio.
     */
    @Test
    @DisplayName("un filtro sin coincidencias agota la historia y no se queda girando")
    fun aFilterWithoutMatchesExhaustsTheHistoryWithoutSpinning() {
        val tail = FakeTail(valid(history))

        val outcome = EventPageDrain.drain(tail, run, EventQuery.ByKind("Absent"), null, limit = 2)

        assertEquals(
            3,
            tail.reads,
            "la historia tiene cinco filas y el corte es de dos: hacen falta tres páginas para " +
                "recorrerla entera. Menos, y el drenador se paró antes de tiempo; más, y giró",
        )
        assertEquals(
            emptyList<Long>(),
            outcome.page.envelopes.map { it.sequence },
            "ninguna fila coincide con el filtro",
        )
        assertTrue(
            outcome.page.nextCursor?.lastSequence == 5L,
            "el cursor es la última posición observada del almacén. Cursor emitido: " +
                "${outcome.page.nextCursor}",
        )
        assertTrue(
            !outcome.page.hasMore,
            "el almacén agotó la historia: no queda nada por delante del cliente",
        )
    }

    /**
     * El almacén que promete más y no avanza.
     *
     * ## La garantía
     * Hay una respuesta que el drenador no puede dar: la que no sabe. Cuando el almacén dice
     * «queda más» y la continuación no se mueve, no hay hecho que entregar ni final que
     * anunciar: seguir preguntando es girar en vacío, y cortar ahí es truncar en silencio. La
     * respuesta honesta es un tercer caso, distinto de «respondí» y de «no queda nada»: el
     * drenador se declara atascado, dice en qué posición se quedó y deja la bandera en alto para
     * que el cliente sepa que puede reintentar.
     *
     * ## Por qué esto NO lo puede observar la capa ejecutable
     * Los dos almacenes que hay nunca hacen esto: el corte avanza con la última fila leída o el
     * almacén dice que no queda nada. Un almacén atascado es una avería de un tercero, y aquí la
     * garantía se ejerce contra un doble que la provoca a propósito.
     */
    @Test
    @DisplayName("un almacén que promete más y no avanza deja al drenador atascado")
    fun aStoreThatPromisesMoreAndDoesNotAdvanceLeavesTheDrainStuck() {
        val tail = StuckTail()
        val start = EventCursor("run-1", 7L)

        val outcome = EventPageDrain.drain(tail, run, EventQuery.All, start, limit = 2)

        val stalled = assertInstanceOf(EventPageDrain.Outcome.Stalled::class.java, outcome)
        assertEquals(
            1,
            tail.reads,
            "la continuación no se movió: preguntar otra vez daría la misma página",
        )
        assertTrue(
            stalled.page.hasMore,
            "el almacén dijo que queda más: la bandera no puede decir que no",
        )
        assertTrue(
            stalled.stuckAfter?.lastSequence == 7L,
            "el drenador tiene que decir en qué posición se quedó. Posición emitida: " +
                "${stalled.stuckAfter}",
        )
    }

    /**
     * La misma garantía en la proyección tipada.
     *
     * El drenador tipado lee por otro puerto y tiene su propio bucle, así que el guard de
     * atasco puede existir en una proyección y no en la otra. Y el fallo de esa divergencia no
     * es un resultado equivocado: es un bucle que no termina, que es el peor modo de fallar
     * porque una prueba colgada parece una prueba que nunca terminó.
     */
    @Test
    @DisplayName("la proyección tipada también declara que se atascó")
    fun theTypedProjectionAlsoReportsTheStall() {
        val store = StuckRecordStore()
        val start = EventCursor("run-1", 7L)

        val outcome = EventPageDrain.drainTyped(store, run, EventQuery.All, start, limit = 2)

        val stalled = assertInstanceOf(EventPageDrain.TypedOutcome.Stalled::class.java, outcome)
        assertEquals(
            1,
            store.reads,
            "la continuación no se movió: preguntar otra vez daría la misma página",
        )
        assertTrue(
            stalled.page.hasMore,
            "el almacén dijo que queda más: la bandera no puede decir que no",
        )
        assertTrue(
            stalled.stuckAfter?.lastSequence == 7L,
            "el drenador tiene que decir en qué posición se quedó. Posición emitida: " +
                "${stalled.stuckAfter}",
        )
    }
}
