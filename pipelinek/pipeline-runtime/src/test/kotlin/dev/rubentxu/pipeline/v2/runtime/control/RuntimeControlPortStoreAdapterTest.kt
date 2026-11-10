package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.runtime.durable.FencingToken
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseLiveness
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryPort
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryReport
import dev.rubentxu.pipeline.v2.output.store.SealOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

/**
 * Qué garantiza el cancel cuando el Output Plane falla.
 *
 * ## La garantía bajo prueba
 *
 * Un cancel cierra un run: escribe el hecho terminal del run, sella sus streams
 * de salida y deja constancia de la cancelación. La garantía es que **un run
 * cerrado tiene su salida sellada**, y que un cancel que no pudo completarse
 * **no deja el run cerrado a medias**.
 *
 * ## El defecto que estas filas persiguen
 *
 * El orden de las escrituras era: recibo, luego bookend, luego sellado. Con ese
 * orden, un fallo de sellado dejaba el run con su hecho terminal ya escrito —
 * el run estaba ABORTED — y sus streams sin sellar para siempre. El cancel
 * siguiente lo encontraba "ya cancelado" y no hacía nada, así que **nadie
 * sellaba Those bytes**: el Output Plane de un run abortado nunca terminaba, y
 * un lector que lo seguía se quedaba esperando para siempre.
 *
 * Peor: el cancel que falló devolvía una negativa ("no se pudo cancelar"),
 * cuando el run estaba de hecho abortado. El operador recibía dos respuestas
 * contradictorias —"falló" y "ya estaba cancelado"— sobre el mismo run.
 *
 * ## Por qué el orden es la corrección
 *
 * [CancelOutcome.Cancelled] dice lo que significa: "este call cerró el run, y
 * sus streams quedaron sellados". Ese contrato sólo es verdadero si el hecho
 * terminal se escribe DESPUÉS del sellado. Con el bookend al final, un fallo de
 * sellado deja el run **sin cerrar** — con el recibo de la intención, que es
 * justo lo que permite al cancel siguiente terminar el trabajo sin escribir un
 * segundo estado terminal.
 *
 * ## Por qué las autoridades responden desde memoria
 *
 * El adaptador compone puertos estrechos: el journal, la lease, el event lane,
 * el índice de frames y el sellado. Aquí todos contestan desde memoria y el
 * sellado es una función inyectada, que es donde cabe el fallo que estas filas
 * necesitan provocar. Nada toca un disco, un socket ni un reloj que corra solo.
 */
class RuntimeControlPortStoreAdapterTest {

    private val runId: String = "run-under-trial"
    private val stream: OutputStreamId = OutputStreamId("stdout-$runId-s0-step-0")

    /**
     * El camino feliz: el cancel cierra el run, sella su salida y lo dice.
     */
    @Test
    fun `un cancel cierra el run y sella su salida`() {
        val fixture = fixture()

        val outcome = fixture.adapter.cancel(runId, CancelReason.UserRequested)

        val cancelled = assertInstanceOf(CancelOutcome.Cancelled::class.java, outcome)
        assertEquals(
            1,
            cancelled.streamsSealed,
            "the cancel sealed the run's only stream",
        )
        assertEquals(
            1,
            fixture.lane.bookendsOf(runId).size,
            "the run's terminal fact is written exactly once",
        )
        assertEquals(
            "aborted",
            fixture.lane.bookendsOf(runId).single().outcome,
            "a cancelled run has no outcome of its own, and 'aborted' is the word the run's " +
                "vocabulary has for that",
        )
        assertEquals(
            1,
            fixture.journal.listForRun(runId).size,
            "the cancellation left one receipt in the journal",
        )
    }

    /**
     * El recibo de cancelación lleva la IDENTIDAD CANÓNICA de su operación.
     *
     * El recibo es una operación durable como cualquier otra, así que su identidad la calcula
     * la ÚNICA autoridad que la posee — `Fingerprint.compute` — y no una fórmula escrita en
     * este fichero. La fórmula que usaba antes hasheaba el operation id junto al input
     * codificado, bajo una configuración de JSON que vivía aquí, y la codificación escribía
     * los parámetros en el ORDEN DE ITERACIÓN: la identidad de un recibo dependía del orden en
     * que se armaron sus parámetros.
     *
     * Un recibo que ya está en un journal conserva el digest con el que se escribió —el camino
     * de conflicto del store preserva la columna `fingerprint`, y ningún lector recalcula la
     * identidad de un recibo—, así que unificar la fórmula no invalida lo ya journalizado.
     */
    @Test
    fun `el recibo de cancelacion lleva la identidad canonica`() {
        val fixture = fixture()

        fixture.adapter.cancel(runId, CancelReason.UserRequested)

        val receipt = fixture.journal.listForRun(runId).single { it.id == CancelReceipt.opIdOf(runId) }
        assertEquals(
            Fingerprint.compute(
                receipt.input,
                receipt.input.stepId,
                ReplayPolicy.RERUN,
                receipt.input.attempt,
            ),
            receipt.fingerprint,
            "el recibo es una operación durable como cualquier otra: su identidad la calcula la " +
                "autoridad que la posee, y no una fórmula local que dependía del orden en que se " +
                "armaron sus parámetros",
        )
    }

    /**
     * La fila del defecto: un fallo de sellado NO deja el run cerrado.
     *
     * Antes de la corrección el bookend estaba escrito antes del sellado, así
     * que este run quedaba ABORTED con su salida sin sellar, y el cancel
     * siguiente lo encontraba "ya cancelado" y no sellaba nunca.
     */
    @Test
    fun `un fallo de sellado no deja el run cerrado`() {
        val fixture = fixture()
        fixture.seals.failNext()

        val outcome = fixture.adapter.cancel(runId, CancelReason.UserRequested)

        val refused = assertInstanceOf(CancelOutcome.Refused::class.java, outcome)
        assertInstanceOf(
            CancelRefusal.StorageError::class.java,
            refused.refusal,
            "the output plane could not seal the run's streams, and that is a storage error",
        )
        assertEquals(
            0,
            fixture.lane.bookendsOf(runId).size,
            "the run was NOT closed: the seal failed, so the terminal fact must not exist. " +
                "A run that is aborted but whose bytes never end is a run nobody can finish",
        )
        assertEquals(
            1,
            fixture.journal.listForRun(runId).size,
            "the intent IS recorded, so the retry finishes the job instead of writing a second " +
                "terminal state",
        )
    }

    /**
     * La fila de la reconciliación: el cancel siguiente termina el trabajo.
     *
     * Un solo hecho terminal, un solo recibo, y los bytes sellados.
     */
    @Test
    fun `el cancel siguiente termina el trabajo que dejo el anterior a medias`() {
        val fixture = fixture()
        fixture.seals.failNext()

        fixture.adapter.cancel(runId, CancelReason.UserRequested)
        val outcome = fixture.adapter.cancel(runId, CancelReason.UserRequested)

        val cancelled = assertInstanceOf(CancelOutcome.Cancelled::class.java, outcome)
        assertEquals(
            1,
            cancelled.streamsSealed,
            "the retry sealed the stream the failed attempt could not seal",
        )
        assertEquals(
            1,
            fixture.lane.bookendsOf(runId).size,
            "the retry finished the job instead of writing a second terminal fact: N cancels " +
                "leave one terminal state",
        )
        assertEquals(
            1,
            fixture.journal.listForRun(runId).size,
            "the retry did not repeat the receipt either",
        )
    }

    /**
     * La invariante publicada: un cancel repetido es un no-op.
     *
     * El segundo cancel no vuelve a sellar ni a escribir: dice que el run ya
     * estaba cerrado, y el run sigue teniendo un solo estado terminal.
     */
    @Test
    fun `un cancel repetido no repite ni el sellado ni el estado terminal`() {
        val fixture = fixture()

        fixture.adapter.cancel(runId, CancelReason.UserRequested)
        val outcome = fixture.adapter.cancel(runId, CancelReason.UserRequested)

        assertInstanceOf(
            CancelOutcome.AlreadyCancelled::class.java,
            outcome,
            "the second cancel is a no-op, not a refusal: the run IS closed",
        )
        assertEquals(
            1,
            fixture.lane.bookendsOf(runId).size,
            "the run has one terminal fact, not two",
        )
        assertEquals(
            1,
            fixture.seals.calls.size,
            "the second cancel did not seal again: the stream was already sealed and sealing is " +
                "not free of consequences",
        )
    }

    /**
     * El hecho terminal es DURABLE antes de que la respuesta viaje.
     *
     * El lane escribe por un hilo escritor: un hecho recién añadido todavía
     * no lo ve nadie. Un cancel que respondiera "cancelled" sobre un hecho
     * que todavía estaba en cola dejaría abierta la ventana en la que el
     * cancel siguiente no encuentra terminal y escribe un segundo.
     */
    @Test
    fun `el hecho terminal del cancel es durable antes de la respuesta`() {
        val fixture = fixture()

        fixture.adapter.cancel(runId, CancelReason.UserRequested)

        assertEquals(
            1,
            fixture.durability.calls.size,
            "el cancel confirma su hecho terminal antes de responder",
        )
        assertEquals(
            fixture.lane.bookendsOf(runId).single().eventId,
            fixture.durability.calls.single(),
            "la barrera se pregunta por el hecho terminal de ESTE cancel, y no por el lane entero",
        )
        assertEquals(
            1,
            fixture.lane.bookendsOf(runId).size,
            "el hecho terminal es visible para un lector en cuanto la respuesta viaja",
        )
    }

    /**
     * Un hecho terminal que no puede ser durable NO cierra el run.
     *
     * El run queda con el recibo de la intención y sin estado terminal, que
     * es el estado que el cancel siguiente termina: un solo hecho terminal y
     * un solo estado.
     */
    @Test
    fun `un hecho terminal que no puede ser durable deja el run sin cerrar`() {
        val fixture = fixture()
        fixture.durability.failNext()

        val refused = assertInstanceOf(
            CancelOutcome.Refused::class.java,
            fixture.adapter.cancel(runId, CancelReason.UserRequested),
        )
        assertInstanceOf(
            CancelRefusal.StorageError::class.java,
            refused.refusal,
            "el store no pudo confirmar el hecho terminal, y eso es un fallo del almacenamiento",
        )
        assertEquals(
            0,
            fixture.lane.bookendsOf(runId).size,
            "el run NO está cerrado: un hecho que nadie puede leer no es un estado terminal",
        )
        assertEquals(
            1,
            fixture.journal.listForRun(runId).size,
            "la intención SÍ queda registrada, para que el cancel siguiente termine el trabajo",
        )

        val retried = fixture.adapter.cancel(runId, CancelReason.UserRequested)
        assertInstanceOf(
            CancelOutcome.Cancelled::class.java,
            retried,
            "el cancel siguiente termina el trabajo que el anterior dejó a medias",
        )
        assertEquals(
            1,
            fixture.lane.bookendsOf(runId).size,
            "N cancels dejan un solo estado terminal",
        )
    }

    /**
     * La lease se toma y se devuelve, también en el camino que falla.
     *
     * Un cancel que se niega debe devolver la lease igual: si no, el run queda
     * con un dueño fantasma y el siguiente cancel no puede tomarla.
     */
    @Test
    fun `la lease se devuelve tambien cuando el cancel falla`() {
        val fixture = fixture()
        fixture.seals.failNext()

        fixture.adapter.cancel(runId, CancelReason.UserRequested)

        assertEquals(
            listOf(RuntimeControlPortStoreAdapter.receiptOpId(runId)),
            fixture.journal.listForRun(runId).map { it.id },
            "the receipt is the cancel's own record, under the id the product reserves for it",
        )
        assertEquals(
            1,
            fixture.lease.releases.size,
            "the lease went back, even though the cancel did not finish",
        )
    }

    /**
     * El terminal que aparece MIENTRAS se toma la lease no se reescribe.
     *
     * Los hechos de la decisión se leen bajo la lease, que es la exclusión
     * sobre el run. Un run que alcanza su propio desenlace en esa ventana
     * tiene un terminal, y escribir el suyo encima dejaría DOS estados
     * terminales para una ejecución —que es la ambigüedad que este puerto
     * existe para quitar—.
     *
     * Sin la lectura bajo la lease, la decisión se tomaría con el terminal
     * leído antes de la adquisición, que era ninguno, y el cancel escribiría
     * su bookend encima del ajeno.
     */
    @Test
    fun `el terminal que aparece mientras se toma la lease no se reescribe`() {
        val fixture = fixture()
        fixture.lease.onAcquire = { fixture.lane.finishTheRun(runId, "success") }

        val refused = assertInstanceOf(
            CancelOutcome.Refused::class.java,
            fixture.adapter.cancel(runId, CancelReason.UserRequested),
        )
        assertInstanceOf(
            CancelRefusal.RunTerminal::class.java,
            refused.refusal,
            "el run alcanzó su propio desenlace mientras el cancel tomaba la lease, y un cancel " +
                "no pisa el desenlace de un run",
        )
        assertEquals(
            1,
            fixture.lane.bookendsOf(runId).size,
            "una ejecución tiene un estado terminal, y el cancel no escribe el suyo encima",
        )
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /**
     * El adaptador bajo prueba, con las autoridades que compone.
     */
    private class Fixture(
        val adapter: RuntimeControlPortStoreAdapter,
        val journal: InMemoryOperationJournal,
        val lane: FakeEventLane,
        val lease: FakeLease,
        val seals: SealPlan,
        val durability: DurabilityPlan,
    )

    /**
     * Un reloj que no corre: el cancel sólo necesita un instante para fechar
     * sus escrituras, y un reloj que avanza haría depender el resultado del
     * orden en que se consultó.
     */
    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-10-21T10:00:00Z")
    }

    private fun fixture(): Fixture {
        val clock = FixedClock()
        val journal = InMemoryOperationJournal(clock)
        val lane = FakeEventLane()
        val lease = FakeLease()
        val seals = SealPlan(stream)
        val durability = DurabilityPlan(lane)
        val adapter = RuntimeControlPortStoreAdapter(
            journal = journal,
            lease = lease,
            events = lane,
            eventReader = lane,
            frames = SingleStreamFrameIndex(stream),
            outputRecovery = NoOutputRecovery,
            sealStream = seals::seal,
            clock = clock,
            ownerId = RunOwnerId.of("operator")!!,
            makeTerminalDurable = { eventId -> durability.makeDurable(eventId) },
        )
        return Fixture(adapter, journal, lane, lease, seals, durability)
    }

    /**
     * El sellado, como función inyectada: es donde cabe el fallo que estas
     * filas necesitan provocar, y por eso el adaptador lo recibe como
     * parámetro y no lo busca por su cuenta.
     */
    private class SealPlan(private val stream: OutputStreamId) {
        val calls = mutableListOf<OutputStreamId>()
        private var failing = false

        /** El próximo sellado falla, como un disco lleno. */
        fun failNext() {
            failing = true
        }

        fun seal(id: OutputStreamId): SealOutcome {
            calls += id
            if (failing) {
                failing = false
                return SealOutcome.Failure(cause = IOException("no space left on device"))
            }
            return if (id == stream) SealOutcome.Sealed(end = 42L) else SealOutcome.NeverOpened
        }
    }

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    /**
     * El event lane: escribe y lee. La secuencia la asigna el store, como en
     * producción, así que el bookend que el cancel escribe con secuencia cero
     * sale con la secuencia que el lane le da.
     */
    /**
     * El lane, con la MISMA forma que el store de verdad: los hechos se
     * escriben en cola y sólo son visibles cuando son durables.
     *
     * El lane real escribe por un hilo escritor y confirma por lotes, así que
     * un hecho recién añadido todavía no lo ve nadie. Un fake que publica al
     * instante no puede distinguir "el cancel escribió el hecho terminal" de
     * "el cancel dejó el run cerrado", y esa es exactamente la diferencia que
     * esta fila persigue.
     */
    private class FakeEventLane : EventSink, EventRecordReadPort {
        private val pending = mutableListOf<DomainEvent>()
        private val committed = mutableListOf<DomainEvent>()
        private var nextSequence = 1L

        override fun append(event: DomainEvent) {
            val sequenced = if (event is RunFinished) {
                event.copy(sequence = nextSequence++)
            } else {
                event
            }
            pending += sequenced
        }

        /** Los hechos en cola se vuelven durables: ahora sí los ve un lector. */
        fun commit() {
            committed += pending
            pending.clear()
        }

        /** Los hechos en cola se pierden: el store no los pudo guardar. */
        fun discardPending() {
            pending.clear()
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> =
            committed.asSequence().filter { it.runId == runId }

        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult {
            val afterSequence = after?.lastSequence ?: 0L
            val page = committed
                .filter { it.runId == runId && it.sequence > afterSequence }
                .take(limit)
            return EventRecordReadResult.Page(
                EventRecordSlice(
                    records = page.map { EventRecordRead.Decoded(it) },
                    nextCursor = EventCursor(runId, page.lastOrNull()?.sequence ?: afterSequence),
                    hasMore = false,
                ),
            )
        }

        /** Los hechos terminales que el lane guarda de este run. */
        fun bookendsOf(runId: String): List<RunFinished> =
            committed.filterIsInstance<RunFinished>().filter { it.runId == runId }

        /**
         * El run alcanza su propio desenlace, y el hecho queda visible.
         *
         * Es lo que otro proceso —o el hijo de este run— deja en el lane
         * mientras esta cancelación está tomando la lease.
         */
        fun finishTheRun(runId: String, outcome: String) {
            append(
                RunFinished(
                    eventId = "terminal-of-another-writer",
                    runId = runId,
                    sequence = 0L,
                    occurredAt = Instant.parse("2026-10-21T09:59:00Z"),
                    outcome = outcome,
                    diagnostics = emptyList(),
                ),
            )
            commit()
        }
    }

    /**
     * La durabilidad, como función inyectada: es donde cabe el fallo que estas
     * filas necesitan provocar, y por eso el adaptador la recibe como
     * parámetro y no la busca por cuenta propia.
     */
    private class DurabilityPlan(private val lane: FakeEventLane) {
        val calls = mutableListOf<String>()
        private var failing = false

        /** La próxima durabilidad falla, como un disco que no puede confirmar. */
        fun failNext() {
            failing = true
        }

        /**
         * Confirma lo que el lane tenía en cola, y dice a QUIÉN se le pregunta.
         *
         * La barrera se pregunta por el hecho que el cancel quiere durable, y
         * no por el lane entero: un lane que rechazó el hecho de otro
         * productor no rechazó este.
         */
        fun makeDurable(eventId: String) {
            calls += eventId
            if (failing) {
                failing = false
                lane.discardPending()
                throw IOException("the store could not confirm the fact")
            }
            lane.commit()
        }
    }

    /**
     * Una lease que nadie tiene, que cuenta lo que le pidieron.
     */
    private class FakeLease : RunLeasePort {
        val releases = mutableListOf<RunOwnerId>()

        /**
         * Lo que pasa MIENTRE se toma la lease.
         *
         * Es la ventana en la que otro escritor puede dejar el run con su
         * propio desenlace, y la que hace que los hechos de la decisión
         * tengan que leerse bajo la lease y no antes.
         */
        var onAcquire: (() -> Unit)? = null

        override fun acquire(request: LeaseRequest): LeaseAcquisition {
            onAcquire?.invoke()
            return LeaseAcquisition.Acquired(
                record = LeaseRecord(
                    runId = request.runId,
                    ownerId = request.ownerId,
                    fencingToken = FencingToken.FIRST,
                    ownerAlive = true,
                ),
                fencingToken = FencingToken.FIRST,
            )
        }

        override fun release(ownerId: RunOwnerId) {
            releases += ownerId
        }

        override fun isKnown(runId: RunId): Boolean = true

        override fun observe(runId: RunId): LeaseRecord? = null

        override fun liveness(runId: RunId): LeaseLiveness = LeaseLiveness.Free
    }

    /**
     * Un output store con nada que reconciliar.
     */
    private object NoOutputRecovery : OutputRecoveryPort {
        override fun recover(): OutputRecoveryReport = OutputRecoveryReport(
            streamsReconciled = 0,
            streamsOwned = 0,
            committedBytes = 0,
            bytesReleased = 0,
            reservationsReleased = 0,
            bytesUnbacked = 0,
        )
    }

    /**
     * Un índice de frames que declara un stream y nada más.
     */
    private class SingleStreamFrameIndex(private val stream: OutputStreamId) : OutputFrameIndex {
        override fun declareStream(stream: OutputStreamId, channel: OutputChannel) = Unit

        override fun append(
            stream: OutputStreamId,
            channel: OutputChannel,
            from: Long,
            to: Long,
        ): OutputFrame = error("the cancel must not append frames")

        override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int): List<OutputFrame> =
            emptyList()

        override fun lastOrdinal(runId: String): Long? = null

        override fun streamsOfRun(runId: String): List<OutputStreamId> = listOf(stream)

        override fun recoverUnframedBytes(): List<OutputFrame> = emptyList()
    }
}
