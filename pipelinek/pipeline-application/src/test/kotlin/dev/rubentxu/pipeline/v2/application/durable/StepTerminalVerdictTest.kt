package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.application.observation.ObservedOutcome
import dev.rubentxu.pipeline.v2.application.observation.outcomeOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * El veredicto de un step, publicado en el evento que lo termina.
 *
 * ## La garantía
 * Un consumidor del event lane lee el resultado de un step **del evento terminal de ese step**.
 * No lo deduce de la ausencia de otro evento, y no tiene que unir dos eventos para saber si el
 * step falló.
 *
 * ## Por qué hace falta (medido, no supuesto)
 * El `StepFinished` se emite en el `finally` del límite de ejecución: se emite **también** cuando el
 * step falló. Un lector que traducía `StepFinished` a «el step terminó bien» —que es lo que hace
 * el traductor de Fabric— affirmaba algo que el event lane no dice. Y el `StepFinished` no llevaba
 * ningún resultado, así que la única forma de saber el veredicto era unirlo con el `StepFailed`:
 * una unión por `stepIndex`, que ni siquiera es unívoca entre stages.
 *
 * ## Lo que este test mira, y lo que no
 * Mira lo que un lector lee: [outcomeOf] es el lector que sirve la columna `OUTCOME` de `inspect`.
 * No mira la forma del evento ni el nombre del campo, para que el test no se case con la
 * representación: lo que ata es que **el veredicto se puede leer**, no dónde vive.
 */
class StepTerminalVerdictTest {

    /**
     * El sink que graba lo que el productorde. En memoria y sin disco: la garantía es sobre lo que
     * se publica, no sobre dónde se guarda.
     */
    private class RecordingSink : EventSink {
        val events: MutableList<DomainEvent> = mutableListOf()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    private fun context() = StepLifecycleContext(
        runId = "run-1",
        stageIndex = 0,
        stepIndex = 0,
        stepName = "stage/sh-0",
        stepType = "sh",
    )

    /** El evento terminal del step: el último `StepFinished` que el límite emitió. */
    private fun RecordingSink.terminal(): StepFinished =
        events.filterIsInstance<StepFinished>().last()

    @Test
    @DisplayName("un step que termina bien dice que terminó bien")
    fun `a successful step's terminal event reads as success`() = runBlocking {
        val sink = RecordingSink()

        StepExecutionBoundary(sink).execute(context()) {
            CommonExecutionResult(outcome = StepOutcome.Success)
        }

        assertEquals(ObservedOutcome.Success, outcomeOf(sink.terminal()))
    }

    @Test
    @DisplayName("un step que falla lo dice en su propio evento terminal")
    fun `a failed step's terminal event reads as failure`() = runBlocking {
        val sink = RecordingSink()
        val failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "shell exited with code 3")

        StepExecutionBoundary(sink).execute(context()) {
            CommonExecutionResult(outcome = StepOutcome.Failure(failure))
        }

        assertEquals(ObservedOutcome.Failure, outcomeOf(sink.terminal()))
    }

    @Test
    @DisplayName("un step inestable lo dice, y no lo disfraza de éxito")
    fun `an unstable step's terminal event reads as unstable`() = runBlocking {
        val sink = RecordingSink()

        StepExecutionBoundary(sink).execute(context()) {
            CommonExecutionResult(outcome = StepOutcome.Unstable)
        }

        assertEquals(ObservedOutcome.Unstable, outcomeOf(sink.terminal()))
    }

    @Test
    @DisplayName("un step cuya ejecución estalló no deja veredicto: eso es desconocido, no éxito")
    fun `a step that blew up unexpectedly carries no verdict at all`() = runBlocking {
        val sink = RecordingSink()

        val thrown = runCatching {
            StepExecutionBoundary(sink).execute(context()) {
                throw IllegalStateException("the engine blew up")
            }
        }.exceptionOrNull()

        // La excepción inesperada se propaga: no se reclasifica como un fallo del step.
        assertEquals(IllegalStateException::class.java, thrown?.let { it::class.java })
        // Y el terminal que se emitió no afirma nada sobre el resultado.
        assertNull(outcomeOf(sink.terminal()))
    }

    @Test
    @DisplayName("un fallo del step también sigue siendo un fallo del step, con su evidencia")
    fun `a pipeline step exception is still a failure, and still carries its evidence`() = runBlocking {
        val sink = RecordingSink()
        val failure = PipelineFailure(kind = FailureKind.USER, message = "the step refused")

        StepExecutionBoundary(sink).execute(context()) {
            throw PipelineStepException(failure)
        }

        assertEquals(ObservedOutcome.Failure, outcomeOf(sink.terminal()))
        assertEquals(
            "the step refused",
            sink.events.filterIsInstance<dev.rubentxu.pipeline.v2.events.StepFailed>().single().message,
        )
    }
}
