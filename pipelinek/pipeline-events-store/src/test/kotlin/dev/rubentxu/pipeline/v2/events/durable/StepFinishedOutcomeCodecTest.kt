package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.StepFinished
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * El veredicto de un step sobrevive al log durable.
 *
 * ## La garantía
 * El veredicto que el límite de ejecución publica es **durable**: se escribe en la fila, se
 * relee y sigue siendo el mismo. Un evento que dice «success» en memoria y nada en el disco es
 * un veredicto que el consumidor externo no puede leer, y el store es el único sitio donde el
 * hecho queda para siempre.
 *
 * ## Por qué esta fila y no la de producción
 * La fila del store es JSON en una columna `TEXT`. El campo nuevo no necesita migración de
 * esquema — no hay columnas nuevas — pero sí necesita dosCodingarios honestos:
 *  - **escribir**: el veredicto viaja; un veredicto ausente viaja como la convención de ausencia
 *    del propio log, no como una cadena vacía que se pueda confundir con un veredicto;
 *  - **leer**: una fila escrita por un build anterior, que no publicaba veredicto, se lee **sin**
 *    veredicto. No se inventa uno, y sobre todo no se lee como «success».
 *
 * Puro y total: sin base de datos, sin reloj.
 */
class StepFinishedOutcomeCodecTest {

    private fun stepFinished(outcome: String?) = StepFinished(
        eventId = "e-1",
        runId = "run-42",
        sequence = 7L,
        occurredAt = Instant.ofEpochMilli(1_700_000_000_000),
        stageIndex = 1,
        stepIndex = 2,
        stepName = "build/sh-2",
        stepType = "sh",
        outcome = outcome,
    )

    private fun roundTrip(outcome: String?): StepFinished {
        val encoded = JsonEventLog.encodeOne(stepFinished(outcome))
        val decoded = JsonEventLog.decode("[$encoded]").single()
        return decoded as StepFinished
    }

    @Test
    @DisplayName("un veredicto de fallo sobrevive a la escritura y a la lectura")
    fun `a failure verdict survives the durable log`() {
        assertEquals(
            StepOutcome.FAILURE_TOKEN,
            roundTrip(StepOutcome.FAILURE_TOKEN).outcome,
        )
    }

    @Test
    @DisplayName("un veredicto de éxito también, porque el más común es el que se pierde")
    fun `a success verdict survives the durable log`() {
        assertEquals(
            StepOutcome.SUCCESS_TOKEN,
            roundTrip(StepOutcome.SUCCESS_TOKEN).outcome,
        )
    }

    @Test
    @DisplayName("un veredicto ausente se relee como ausente, no como éxito")
    fun `an absent verdict is read back as absent`() {
        assertNull(roundTrip(null).outcome)
    }

    @Test
    @DisplayName("una fila de un build anterior se lee sin veredicto, no con un veredicto inventado")
    fun `a row written before the verdict existed decodes without one`() {
        // A row as an older build wrote it: same event, no `outcome` field at all.
        val legacy = """[{"eventId":"e-1","runId":"run-42","sequence":7,"kind":"StepFinished",""" +
            """"occurredAt":"2023-11-14T22:13:20Z","stageIndex":1,"stepIndex":2,""" +
            """"stepName":"build/sh-2","stepType":"sh"}]"""

        val decoded = JsonEventLog.decode(legacy).single() as StepFinished

        assertNull(decoded.outcome)
    }
}
