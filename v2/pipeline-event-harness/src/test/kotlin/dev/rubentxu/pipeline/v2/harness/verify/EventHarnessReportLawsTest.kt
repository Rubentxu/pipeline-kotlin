package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector
import dev.rubentxu.pipeline.v2.harness.model.EventConstraint
import dev.rubentxu.pipeline.v2.harness.model.EventContract
import dev.rubentxu.pipeline.v2.harness.model.EventSelector
import dev.rubentxu.pipeline.v2.harness.model.ExpectedRunOutcome
import dev.rubentxu.pipeline.v2.harness.model.PipelineOutcome
import dev.rubentxu.pipeline.v2.harness.model.VerificationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * `report`, `observedTerminal` y `lastSegment`: el borde de la aceptacion.
 *
 * `verify` y `accept` ya tenian cobertura. Lo que no la tenia era la capa que decide QUE se
 * verifica y QUE se dice que se observo, y son las dos funciones que un informe de UAT usa para
 * decir "esto cumple el contrato" y "esto fue lo que paso al final".
 *
 * `observedTerminal` tiene una asimetria que conviene que quede escrita: un `RunFinished` cuyo
 * `outcome` no es uno de los tres de `PipelineOutcome` devuelve `null` en vez de fallar. Eso es
 * lo correcto para un informe —no se puede afirmar un terminal que no se sabe—, pero es
 * exactamente el caso que un consumidor ingenuo leeria como "el run no llego a terminarse", que
 * no es lo mismo que "el terminal que vi no lo entiendo".
 *
 * `lastSegment` corta desde el ULTIMO `RunStarted`, no desde el primero, y por un motivo concreto
 * que esta escrito en el propio harness: bajo la deuda INC-EVT3-1 el reuse durable re-anade
 * eventos esqueleto con secuencias que reinician, asi que el ultimo segmento es un sufijo
 * contiguo solo si se corta por el ultimo arranque. Cortar por el primero daria una historia con
 * dos arranques y un final, que no es una ejecucion.
 */
class EventHarnessReportLawsTest {

    private var seq = 0L

    private fun next(): Long = ++seq

    private fun ts(): Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun ev(event: DomainEvent): TypedEvent = TypedEvent(EnvelopeProjector.project(event), event)

    private fun runStarted() = RunStarted("e${next()}", "r1", next(), ts(), "p.kts")

    private fun runFinished(outcome: String) = RunFinished("e${next()}", "r1", next(), ts(), outcome, emptyList())

    private fun stepStarted(stage: Int) = StepStarted("e${next()}", "r1", next(), ts(), stage, 0, "s$stage", "sh")

    /** Dos segmentos: el primero termino en FAILURE, el reintento en SUCCESS. */
    private fun dosSegmentos(): List<TypedEvent> = listOf(
        runStarted(), stepStarted(0), runFinished("FAILURE"),
        runStarted(), runFinished("SUCCESS"),
    ).map { ev(it) }

    private fun contrato(name: String, vararg constraints: EventConstraint) = EventContract(
        version = EventContract.CURRENT_VERSION,
        name = name,
        expect = ExpectedRunOutcome.SUCCESS,
        constraints = constraints.toList(),
    )

    // ------------------------------------------------------------------ observedTerminal

    @Test
    fun `sin RunFinished no hay terminal observado`() {
        val history = listOf(runStarted(), stepStarted(0)).map { ev(it) }

        assertNull(
            EventHarness.observedTerminal(history),
            "una historia sin RunFinished no tiene terminal: afirmar uno seria inventarlo",
        )
    }

    @Test
    fun `el terminal observado es el del ULTIMO RunFinished`() {
        val history = dosSegmentos()

        assertEquals(
            PipelineOutcome.SUCCESS,
            EventHarness.observedTerminal(history),
            "tras un reintento manda el ultimo RunFinished, no el primero",
        )
    }

    @Test
    fun `el terminal observado no distingue mayusculas porque el cable las trae minusculas`() {
        val history = listOf(runStarted(), runFinished("success")).map { ev(it) }

        assertEquals(
            PipelineOutcome.SUCCESS,
            EventHarness.observedTerminal(history),
            "el outcome del cable llega en minusculas y debe mapear al enum sinSensitivity",
        )
    }

    @Test
    fun `un outcome desconocido se reporta como desconocido y no como terminal ausente`() {
        // La asimetria que hace que este test exista. Un outcome que el harness no reconoce no es
        // lo mismo que un run que no termino: uno es "no se que paso", el otro es "no llego al
        // final". Un informe que los mezclara declararia un run en curso como uno sin cerrar.
        val history = listOf(runStarted(), runFinished("ABORTED")).map { ev(it) }

        assertNull(
            EventHarness.observedTerminal(history),
            "ABORTED no pertenece a PipelineOutcome, asi que el terminal observado es desconocido",
        )
        assertNotNull(
            history.last().event as RunFinished,
            "el RunFinished SI existia: la historia lo contiene y lo que falta es la comprension",
        )
    }

    // ------------------------------------------------------------------ lastSegment

    @Test
    fun `sin RunStarted el segmento es la historia entera`() {
        val history = listOf(stepStarted(0), runFinished("SUCCESS")).map { ev(it) }

        assertEquals(
            history.size,
            EventHarness.lastSegment(history).size,
            "sin ningun RunStarted no hay por donde cortar, y recortar a la nada perderia la historia",
        )
    }

    @Test
    fun `el segmento corta desde el ULTIMO RunStarted y no desde el primero`() {
        val history = dosSegmentos()

        val segmento = EventHarness.lastSegment(history)

        assertEquals(2, segmento.size, "el ultimo segmento es el reintempo: arranque y fin")
        assertEquals("RunStarted", segmento.first().kind, "el segmento empieza en un arranque")
        assertEquals("RunFinished", segmento.last().kind, "y termina en un cierre")
    }

    // ------------------------------------------------------------------ report

    @Test
    fun `el informe nombra el contrato y repite el resultado de la verificacion`() {
        val history = dosSegmentos()
        val c = contrato("protocolo-de-retry")

        val informe = EventHarness.report(history, c)

        assertEquals("protocolo-de-retry", informe.contract, "el informe debe nombrar el contrato evaluado")
        assertEquals(
            EventHarness.verify(history, c),
            informe.result,
            "report no puede tener su propio criterio: el resultado es el de verify",
        )
        assertEquals(PipelineOutcome.SUCCESS, informe.observedTerminalOutcome)
    }

    @Test
    fun `el informe observa el terminal incluso cuando el contrato se incumple`() {
        // Las dos columnas del informe son independientes a proposito. Un contrato incumplido no
        // borra lo que se observo: un operador necesita las dos, porque "que paso" y "si debia
        // pasar" son preguntas distintas y el informe es donde se responden las dos.
        val history = dosSegmentos()
        val c = contrato(
            "nunca-debe-haber-pasos",
            EventConstraint.Never(EventSelector("StepStarted")),
        )

        val informe = EventHarness.report(history, c)

        assertTrue(
            informe.result is VerificationResult.Invalid,
            "StepStarted aparece en la historia completa, asi que el contrato se incumple",
        )
        assertEquals(
            PipelineOutcome.SUCCESS,
            informe.observedTerminalOutcome,
            "el terminal observado se reporta igual aunque el veredicto sea Invalid",
        )
    }

    @Test
    fun `el alcance del informe cambia que se verifica, no solo que se imprime`() {
        // La fila que mas trabajo cuesta fijar. `report` recibe un `scope` y debe propagarlo a
        // lo que verifica, no solo al recorte que imprime. Si alguien "simplificara" report()
        // delegando en verify con el alcance por defecto, el informe volveria a mirar la historia
        // completa y daria Invalid sobre un reintento que SI cumple el contrato.
        val history = dosSegmentos()
        val c = contrato(
            "nunca-debe-haber-pasos",
            EventConstraint.Never(EventSelector("StepStarted")),
        )

        val sobreTodo = EventHarness.report(history, c, EventHarness.Scope.WHOLE)
        val sobreElUltimo = EventHarness.report(history, c, EventHarness.Scope.AFTER_LAST_RUN_STARTED)

        assertTrue(
            sobreTodo.result is VerificationResult.Invalid,
            "en la historia completa hay un StepStarted del primer segmento y el contrato se incumple",
        )
        assertEquals(
            VerificationResult.Valid,
            sobreElUltimo.result,
            "el ultimo segmento no tiene ningun StepStarted, asi que ahi el contrato se cumple",
        )
        assertEquals(
            sobreTodo.observedTerminalOutcome,
            sobreElUltimo.observedTerminalOutcome,
            "el terminal observado es el mismo: el ultimo RunFinished de la historia esta en el ultimo segmento",
        )
    }
}
