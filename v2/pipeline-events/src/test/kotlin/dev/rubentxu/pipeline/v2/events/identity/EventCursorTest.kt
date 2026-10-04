package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Leyes de ida y vuelta de [EventCursor]: un token que el propio codificador
 * produce tiene que ser decodificable por el propio decodificador.
 *
 * La fila que importa es la de los dos puntos. `encode()` escapaba antes el
 * `runId` con `URLEncoder` mientras `decode()` divide por `:` y exige tres partes
 * exactas, de modo que cualquier identificador de ejecucion con dos puntos
 * generaba un token que su propio decodificador rechazaba. Un identificador de
 * ejecucion construido por la via normal SI lleva dos puntos, porque
 * `ResourceRef.canonicalText()` es `v<version>:<kind>:<segmentos>`.
 */
class EventCursorTest {

    @Test
    fun `ida y vuelta con un runId simple`() {
        val cursor = EventCursor("run-42", 7L)

        assertEquals("evt-cursor-v1:run-42:7", cursor.encode())
        assertEquals(cursor, EventCursor.decode(cursor.encode()))
    }

    @Test
    fun `ida y vuelta con un runId con dos puntos construido como ResourceRef`() {
        // Regresion: este identificador viene de la via real de produccion
        // (canonicalText de un ResourceRef) y antes de la correccion producia un
        // token de seis partes que `decode` rechazaba por tener mas de tres.
        val runId = ResourceRefs.run("run-42").canonicalText()
        val cursor = EventCursor(runId, 11L)
        val token = cursor.encode()

        assertEquals("evt-cursor-v1:v1%3Arun%3Apipeline%2Frun%2Frun-42:11", token)
        assertEquals(3, token.split(":").size, "el token codificado debe tener exactamente tres partes")
        assertEquals(cursor, EventCursor.decode(token))
    }

    @Test
    fun `ida y vuelta con un runId con barra y espacio`() {
        val cursor = EventCursor("pipeline/run-42 con espacio", 3L)
        val token = cursor.encode()

        assertEquals("evt-cursor-v1:pipeline%2Frun-42+con+espacio:3", token)
        assertEquals(cursor, EventCursor.decode(token))
    }

    @Test
    fun `un token mal formado se rechaza en vez de decodificar a otra ejecucion`() {
        val rechazados = listOf(
            "op-cursor-v1:run-42:7" to "prefijo equivocado",
            "evt-cursor-v2:run-42:7" to "version de prefijo distinta",
            "evt-cursor-v1:run-42" to "faltan partes",
            "evt-cursor-v1" to "solo el prefijo",
            "evt-cursor-v1:run-42:7:9" to "sobran partes",
            "evt-cursor-v1::7" to "runId vacio",
            "evt-cursor-v1:+:7" to "runId en blanco",
            "evt-cursor-v1:run-42:siete" to "secuencia no numerica",
            "evt-cursor-v1:run-42:" to "secuencia vacia",
            "" to "token vacio",
        )

        rechazados.forEach { (token, motivo) ->
            assertNull(EventCursor.decode(token), "decode debe rechazar: $motivo ($token)")
        }
    }

    @Test
    fun `un runId en blanco no sobrevive al viaje de ida y vuelta`() {
        // `encode` no valida y `decode` si: el contrato observable es que un token
        // con identificador en blanco no se puede recuperar como cursor, en vez de
        // volver como una ejecucion vacia que el llamador creeria real.
        assertEquals("evt-cursor-v1::0", EventCursor("", 0L).encode())
        assertNull(EventCursor.decode(EventCursor("", 0L).encode()))
    }
}
