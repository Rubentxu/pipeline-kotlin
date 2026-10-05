package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Leyes de [NullEventSink], el objeto nulo que el producto usa cuando no hay historial.
 *
 * No es cobertura por cobertura. Un null object que devuelve una secuencia VACIA en vez de
 * lanzar es la diferencia entre "este run todavía no tiene eventos" y un crash en un producto
 * que aún no ha empezado a correr, y esa distinción no aparece en ningún otro sitio del
 * módulo: si esta clase no está probada, la diferencia no está probada en ninguna parte.
 *
 * La fila que más trabajo cuesta es la de `appendAssigned`. La interfaz ofrece un default que
 * hace eco del evento, y heredarlo aquí es correcto —no hay secuencia que asignar— pero sólo
 * porque el default devuelve el MISMO objeto en vez de uno con `sequence = 0`. Un null object
 * que devolviera un evento nuevo con la secuencia a cero sería indistinguible de "no ha
 * pasado nada todavía". Se fija aquí de forma explícita.
 */
class NullEventSinkLawsTest {

    private fun runStarted(sequence: Long = 1L) = RunStarted(
        eventId = "evt-1",
        runId = "r1",
        sequence = sequence,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        scriptPath = "p.kts",
    )

    @Test
    fun `append no lanza y no deja el evento pasar`() {
        val sink = NullEventSink
        val evento = runStarted()

        sink.append(evento)

        // Un null object que se tragase el evento no tendría nada que comprobar aquí; lo que se
        // comprueba es que la lectura posterior no lo devuelve, que es la propiedad observable.
        assertTrue(
            sink.eventsFor("r1").none { it === evento },
            "NullEventSink no debe devolver por la puerta de atrás lo que se suponía tragado",
        )
    }

    @Test
    fun `appendAssigned devuelve el mismo evento y no lanza`() {
        val sink = NullEventSink
        val evento = runStarted(sequence = 7L)

        val asignado = sink.appendAssigned(evento)

        assertSame(
            evento,
            asignado,
            "el default de la interfaz hace eco del evento; el null object lo acepta, y quien " +
                "recibe el valor debe saber que nadie le asignó secuencia",
        )
    }

    @Test
    fun `appendAssigned de un sink nulo no inventa una secuencia asignada`() {
        // El riesgo real del default: un store que asigna secuencias DEBE sobrescribirlo, y un
        // null object que devolviera el evento con `sequence = 0` sería indistinguible de "no ha
        // pasado nada todavía". El valor devuelto es el mismo objeto, con su secuencia intacta.
        val original = runStarted(sequence = 42L)

        val asignado = NullEventSink.appendAssigned(original)

        assertEquals(42L, asignado.sequence, "el null object no es autoridad de secuencia: no inventa ni reescribe una")
    }

    @Test
    fun `la lectura de cualquier run esta vacia y es repetible`() {
        val sink = NullEventSink

        // Tres lecturas del mismo run, y de un run que no existe: un null object que se
        // consumiera en la primera llamada fallaría en la segunda, y eso sólo aparece si la
        // secuencia se genera nueva cada vez.
        repeat(3) {
            assertEquals(0, sink.eventsFor("r1").count(), "la lectura debe estar vacía y ser repetible")
        }
        assertEquals(0, sink.eventsFor("otro-run-inexistente").count(), "tampoco hay eventos de un run que no existe")
    }
}
