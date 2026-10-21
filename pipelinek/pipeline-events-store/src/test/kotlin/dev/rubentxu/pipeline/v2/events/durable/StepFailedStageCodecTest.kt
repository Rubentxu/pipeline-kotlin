package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.StepFailed
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * El stage de un fallo sobrevive al log durable.
 *
 * ## La garantía
 * El stage que el fallo declara es **durable**: se escribe en la fila, se relee y sigue siendo el
 * mismo. Un evento que dice «stage 2» en memoria y nada en el disco es un stage que el consumidor
 * externo no puede leer, y el store es el único sitio donde el hecho queda para siempre.
 *
 * ## Por qué esta fila y no la de producción
 * La fila del store es JSON en una columna `TEXT`. El campo nuevo no necesita migración de
 * esquema — no hay columnas nuevas — pero sí necesita dos diccionarios honestos:
 *  - **escribir**: el stage viaja; un stage ausente viaja como la convención de ausencia de
 *    enteros del propio log, no como un 0 que se confunda con el primer stage;
 *  - **leer**: una fila escrita por un build anterior, que no publicaba el stage, se lee **sin**
 *    stage. No se inventa uno, y sobre todo no se lee como «stage 0».
 *
 * Puro y total: sin base de datos, sin reloj.
 */
class StepFailedStageCodecTest {

    private fun stepFailed(stageIndex: Int?) = StepFailed(
        eventId = "e-1",
        runId = "run-42",
        sequence = 9L,
        occurredAt = Instant.ofEpochMilli(1_700_000_000_000),
        stageIndex = stageIndex,
        stepIndex = 2,
        stepName = "build/sh-2",
        stepType = "sh",
        failureKind = FailureKind.SCRIPT,
        message = "shell exited with code 1",
    )

    private fun roundTrip(stageIndex: Int?): StepFailed {
        val encoded = JsonEventLog.encodeOne(stepFailed(stageIndex))
        val decoded = JsonEventLog.decode("[$encoded]").single()
        return decoded as StepFailed
    }

    @Test
    @DisplayName("el stage de un fallo sobrevive a la escritura y a la lectura")
    fun `the stage of a failure survives the durable log`() {
        assertEquals(2, roundTrip(2).stageIndex)
    }

    @Test
    @DisplayName("un stage ausente se relee como ausente, no como el stage 0")
    fun `an absent stage is read back as absent`() {
        // El stage 0 es un stage como cualquier otro: un fallo en el primer stage y un fallo
        // cuyo stage nadie registró son dos hechos distintos, y solo uno de los dos es un stage.
        assertNull(roundTrip(null).stageIndex)
    }

    @Test
    @DisplayName("una fila de un build anterior se lee sin stage, no con un stage inventado")
    fun `a row written before the stage existed decodes without one`() {
        // A row as an older build wrote it: same event, no `stageIndex` field at all.
        val legacy = """[{"eventId":"e-1","runId":"run-42","sequence":9,"kind":"StepFailed",""" +
            """"occurredAt":"2023-11-14T22:13:20Z","stepIndex":2,""" +
            """"stepName":"build/sh-2","stepType":"sh","failureKind":"SCRIPT",""" +
            """"message":"shell exited with code 1"}]"""

        val decoded = JsonEventLog.decode(legacy).single() as StepFailed

        assertNull(decoded.stageIndex)
    }
}
