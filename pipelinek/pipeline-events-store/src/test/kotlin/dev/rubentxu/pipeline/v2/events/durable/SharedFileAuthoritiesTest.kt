package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Dos autoridades de posición en un mismo fichero, y la promesa de que no se mezclan.
 *
 * ## La topología que hay que mirar
 * La historia de hechos y la posición durable de replay se escriben en el MISMO fichero SQLite,
 * bajo la MISMA clave: el identificador de la ejecución. Cada autoridad numera con su propia
 * numeración — la secuencia que asigna el almacén para los hechos, el índice de etapa para la
 * posición durable — y las dos arrancan donde les toca, así que conviven números distintos bajo
 * la misma clave, en el mismo fichero, sin que nada en el disco los separe más que el nombre de
 * su tabla y un filtro por ejecución.
 *
 * ## Por qué el fichero es real y no dos dobles en memoria
 * Con dos almacenes en memoria la garantía sería VACUA: serían dos objetos distintos, y ningún
 * error podría mezclarlos porque no hay nada que mezclar. El peligro vive exactamente en el
 * fichero compartido, donde las dos autoridades se distinguen por una tabla y por un filtro, y
 * donde una lectura sin filtro entregaría la fila de la OTRA ejecución. Un doble en memoria
 * afirmaría la idea del código sobre la garantía, no la garantía.
 *
 * ## Lo que queda demostrado
 * Que leer la historia de una ejecución no crea su posición durable; que avanzar la posición
 * durable no mueve la continuación de hechos; que cada autoridad devuelve las filas de SU
 * ejecución y sólo las suyas; y que sus coordenadas no son intercambiables, porque el índice de
 * etapa de una ejecución no existe dentro de su espacio de secuencias.
 */
class SharedFileAuthoritiesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("pipeline.db").toString()

    @Test
    @DisplayName("en un mismo fichero, la posición durable y la continuación de hechos no se mezclan")
    fun theDurablePositionAndTheEventContinuationShareAFileWithoutMixing() {
        val store = SqliteEventStore(dbPath())
        try {
            val cursors =
                SqliteReplayCursorStoreImpl(store.underlyingConnectionFactory(), FixedClock(Instant.EPOCH))

            store.append(RunStarted("evt-1", "run-1", 0L, Instant.EPOCH, "p.pipeline.kts"))
            store.append(StageStarted("evt-2", "run-1", 0L, Instant.EPOCH, 0, "stage-0"))
            store.append(RunStarted("evt-3", "run-2", 0L, Instant.EPOCH, "p.pipeline.kts"))
            store.flush()

            val before = store.readRecords("run-1", null, 10)

            assertNull(
                cursors.load("run-1"),
                "leer la historia de una ejecución no crea su posición durable: son dos " +
                    "autoridades distintas, no dos vistas de la misma",
            )

            cursors.advance("run-1", "op-1", 3)
            cursors.advance("run-2", "op-2", 7)

            val after = store.readRecords("run-1", null, 10)

            assertEquals(
                listOf("RunStarted", "StageStarted"),
                after.decoded.map { it.kind },
                "la página de hechos de una ejecución trae SUS hechos. La fila durable de esa " +
                    "misma ejecución comparte fichero y clave, así que si la historia la leyera, " +
                    "aparecería aquí",
            )
            assertEquals(
                0,
                after.refusals.size,
                "y ni siquiera como fila ilegible: la fila durable no es una fila de hechos, y " +
                    "una historia que la leyera la contaría como un rechazo sin motivo",
            )
            assertEquals(
                before.nextCursor.lastSequence,
                after.nextCursor.lastSequence,
                "avanzar la posición durable no mueve la continuación de hechos",
            )
            assertEquals(
                2L,
                after.nextCursor.lastSequence,
                "la continuación nombra la secuencia de la última fila entregada",
            )
            assertEquals(
                listOf(1L, 2L),
                after.decoded.map { it.sequence },
                "las secuencias de la ejecución son suyas",
            )

            val first = cursors.load("run-1")
            val second = cursors.load("run-2")
            assertEquals(
                "op-1",
                first?.lastOpId,
                "la posición durable de una ejecución es la suya, no la de la otra que comparte " +
                    "fichero y clave",
            )
            assertEquals(
                "op-2",
                second?.lastOpId,
                "y cada ejecución tiene la suya",
            )
            assertEquals(
                3,
                first?.stageIndex,
                "el índice de etapa es una coordenada de etapa: el 3 no existe entre las " +
                    "secuencias 1 y 2 de esta ejecución, así que las dos numeraciones no son la " +
                    "misma aunque vivan en la misma fila y en la misma clave",
            )
            assertEquals(
                7,
                second?.stageIndex,
                "y la de la otra ejecución tampoco es una secuencia de la primera",
            )
        } finally {
            store.close()
        }
    }

    /**
     * Un reloj fijo: la posición durable guarda una marca de tiempo y esta fila no quiere reloj.
     */
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
