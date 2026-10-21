package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.FencingToken
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
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
        )
        return Fixture(adapter, journal, lane, lease, seals)
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
    private class FakeEventLane : EventSink, EventRecordReadPort {
        private val events = mutableListOf<DomainEvent>()
        private var nextSequence = 1L

        override fun append(event: DomainEvent) {
            val sequenced = if (event is RunFinished) {
                event.copy(sequence = nextSequence++)
            } else {
                event
            }
            events += sequenced
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> =
            events.asSequence().filter { it.runId == runId }

        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult {
            val afterSequence = after?.lastSequence ?: 0L
            val page = events
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
            events.filterIsInstance<RunFinished>().filter { it.runId == runId }
    }

    /**
     * Una lease que nadie tiene, que cuenta lo que le pidieron.
     */
    private class FakeLease : RunLeasePort {
        val releases = mutableListOf<RunOwnerId>()

        override fun acquire(request: LeaseRequest): LeaseAcquisition = LeaseAcquisition.Acquired(
            record = LeaseRecord(
                runId = request.runId,
                ownerId = request.ownerId,
                fencingToken = FencingToken.FIRST,
                ownerAlive = true,
            ),
            fencingToken = FencingToken.FIRST,
        )

        override fun release(ownerId: RunOwnerId) {
            releases += ownerId
        }

        override fun isKnown(runId: String): Boolean = true

        override fun observe(runId: String): LeaseRecord? = null
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
