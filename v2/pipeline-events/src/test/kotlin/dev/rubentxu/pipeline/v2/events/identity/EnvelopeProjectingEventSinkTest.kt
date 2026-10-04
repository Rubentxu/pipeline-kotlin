package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.AT
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RUN_ID
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.identity.cambiarSecuencia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decorador de escritura: el store sigue siendo la autoridad de la secuencia y
 * lo que se publica es el sobre del evento ASIGNADO.
 *
 * La ley que este test protege es la de WU-LPR-105. La proyeccion consume el
 * reconocimiento explicito de escritura (`appendAssigned` devuelve el evento con
 * la secuencia que el store decidio) y nunca vuelve a leer el modelo de lectura
 * para descubrir metadatos de escritura. Cuando se hacia asi, el escritor por
 * lotes todavia no habia confirmado la fila y se publicaba `sequence = 0`: la
 * secuencia que observaba un consumidor externo no era la que el store habia
 * decidido, y no habia forma de saberlo desde fuera.
 *
 * Por eso el store de test asigna una secuencia distinta de la que trae el
 * evento, y el store de la ruta heredada NO asigna ninguna: son las dos rutas
 * reales que el decorador tiene que atravesar.
 */
class EnvelopeProjectingEventSinkTest {

    private val publicador = PublicadorDePrueba()

    /** Publicador de test: guarda lo publicado para poder afirmar sobre ello. */
    private class PublicadorDePrueba : EventPublisher {
        val publicados = mutableListOf<PipelineEventEnvelope>()

        override fun publish(event: PipelineEventEnvelope) {
            publicados.add(event)
        }
    }

    @Test
    fun `publica el sobre con la secuencia que el store asigno`() {
        val store = RecordingEventSink()
        val sink = EnvelopeProjectingEventSink(store, publicador)

        // El evento entra con sequence = 0, que es la senal de "el store decide".
        val asignado = sink.appendAssigned(
            StepStarted(
                eventId = "e1", runId = RUN_ID, sequence = 0L, occurredAt = AT,
                stageIndex = 0, stepIndex = 1, stepName = "compila", stepType = "core.sh",
            ),
        )

        assertEquals(1L, asignado.sequence, "el store es la autoridad de la secuencia")
        assertEquals(1, publicador.publicados.size, "un append publica exactamente un sobre")
        assertEquals(1L, publicador.publicados.single().sequence, "el sobre publicado lleva la secuencia asignada")
        assertEquals("StepStarted", publicador.publicados.single().kind)
    }

    @Test
    fun `varios eventos reciben secuencias distintas y crecientes`() {
        val sink = EnvelopeProjectingEventSink(RecordingEventSink(), publicador)

        val asignadas = (1..3).map { n ->
            sink.appendAssigned(
                RunStarted(
                    eventId = "e$n", runId = RUN_ID, sequence = 0L, occurredAt = AT,
                    scriptPath = "/ws/x$n.pipeline.kts",
                ),
            ).sequence
        }

        assertEquals(listOf(1L, 2L, 3L), asignadas, "el store decide una secuencia monotona por ejecucion")
        assertEquals(
            listOf(1L, 2L, 3L),
            publicador.publicados.map { it.sequence },
            "cada sobre lleva la secuencia que su propio evento recibio",
        )
    }

    @Test
    fun `append delega en appendAssigned y tambien publica`() {
        val store = RecordingEventSink()
        val sink = EnvelopeProjectingEventSink(store, publicador)

        sink.append(
            RunStarted(
                eventId = "e1", runId = RUN_ID, sequence = 0L, occurredAt = AT, scriptPath = "/ws/x.pipeline.kts",
            ),
        )

        assertEquals(1, publicador.publicados.size, "append no puede ser una ruta muda")
        assertEquals(1L, publicador.publicados.single().sequence)
        assertEquals(1, store.eventsFor(RUN_ID).count(), "el evento llego al store interior")
    }

    @Test
    fun `un store que no reasigna secuencia conserva la que trae el evento`() {
        // Ruta heredada: `EventStore.appendAssigned` por defecto es `append` + eco.
        // El decorador debe respectar esa decision y no inventar una secuencia.
        val store = StoreSinReasignar()
        val sink = EnvelopeProjectingEventSink(store, publicador)
        val evento: DomainEvent = RunStarted(
            eventId = "e1", runId = RUN_ID, sequence = 42L, occurredAt = AT, scriptPath = "/ws/x.pipeline.kts",
        )

        val asignado = sink.appendAssigned(evento)

        assertEquals(evento, asignado, "un store que no reasigna devuelve el mismo evento")
        assertEquals(42L, publicador.publicados.single().sequence, "la secuencia publicada es la del evento")
    }

    @Test
    fun `el decorador no altera lo que se lee del store`() {
        val store = RecordingEventSink()
        val sink = EnvelopeProjectingEventSink(store, publicador)
        val evento = RunStarted(
            eventId = "e1", runId = RUN_ID, sequence = 0L, occurredAt = AT, scriptPath = "/ws/x.pipeline.kts",
        )

        sink.appendAssigned(evento)
        val leidos = sink.eventsFor(RUN_ID).toList()

        assertEquals(1, leidos.size, "el decorador es transparente para la lectura")
        assertEquals(1L, leidos.single().sequence, "la lectura ve la secuencia asignada, no la de entrada")
    }

    @Test
    fun `el sobre publicado y el del store describen el mismo evento`() {
        val store = RecordingEventSink()
        val sink = EnvelopeProjectingEventSink(store, publicador)
        val asignado = sink.appendAssigned(
            StepStarted(
                eventId = "e1", runId = RUN_ID, sequence = 0L, occurredAt = AT,
                stageIndex = 0, stepIndex = 1, stepName = "compila", stepType = "core.sh",
            ),
        )
        val publicado = publicador.publicados.single()

        assertEquals(asignado.eventId, publicado.eventRef.id.value)
        assertEquals(asignado.kind, publicado.kind)
        assertEquals(asignado.occurredAt, publicado.occurredAt)
        assertEquals(
            cambiarSecuencia(asignado, asignado.sequence),
            cambiarSecuencia(asignado, publicado.sequence),
            "el sobre y el evento describen la misma secuencia",
        )
        assertTrue(
            publicado.subject.canonicalText().endsWith("/step/1"),
            "el sujeto del sobre se deriva del evento: ${publicado.subject.canonicalText()}",
        )
    }

    /** Store minimo que se apoya en el `appendAssigned` por defecto de [EventStore]. */
    private class StoreSinReasignar : EventSink {
        private val guardados = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            guardados.add(event)
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> =
            guardados.filter { it.runId == runId }.asSequence()
    }
}
