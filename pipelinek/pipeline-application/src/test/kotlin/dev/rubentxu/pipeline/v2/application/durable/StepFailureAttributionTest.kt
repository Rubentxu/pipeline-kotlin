package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * El fallo de un step dice a qué stage pertenece.
 *
 * ## La garantía
 * Un consumidor del event lane atribuye un `StepFailed` a su stage **del propio evento de
 * fallo**. No lo deduce, y no tiene que unirlo con otro evento para averiguarlo.
 *
 * ## Por qué hace falta (medido, no supuesto)
 * El `StepFailed` declaraba el step y no el stage. La única forma de saber el stage era unir el
 * fallo con otro evento por `stepIndex` — y un índice de step significa un step distinto en cada
 * stage, así que esa unión no era unívoca entre stages: el mismo índice de step existe en todos
 * los stages del run.
 *
 * ## Lo que este test mira
 * Mira lo que el productor canónico escribe: el límite de ejecución del step, que es el dueño
 * del ciclo de vida y el que sabe en qué stage se está ejecutando. El stage del test **no es el
 * 0** a propósito: un productor que escribiera un 0 fijo pasaría un test con stage 0, y aquí no.
 */
class StepFailureAttributionTest {

    /**
     * El sink que graba lo que el productor emite. En memoria y sin disco: la garantía es sobre
     * lo que se publica, no sobre dónde se guarda.
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
        stageIndex = 3,
        stepIndex = 0,
        stepName = "stage/sh-0",
        stepType = "sh",
    )

    /** El evento de fallo que el límite emitió. */
    private fun RecordingSink.failure(): StepFailed = events.filterIsInstance<StepFailed>().last()

    /** El evento terminal del step: el último `StepFinished` que el límite emitió. */
    private fun RecordingSink.terminal(): StepFinished = events.filterIsInstance<StepFinished>().last()

    @Test
    @DisplayName("el fallo de un step dice el stage en el que ocurrió")
    fun `the failure a step emits names the stage it happened in`() = runBlocking {
        val sink = RecordingSink()

        StepExecutionBoundary(sink).execute(context()) {
            CommonExecutionResult(outcome = StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "exit 1")))
        }

        assertEquals(3, sink.failure().stageIndex)
    }

    @Test
    @DisplayName("un fallo lanzado como excepción del step también dice su stage")
    fun `a failure raised as a step exception names its stage too`() = runBlocking {
        val sink = RecordingSink()

        StepExecutionBoundary(sink).execute(context()) {
            throw PipelineStepException(PipelineFailure(FailureKind.USER, "the step refused"))
        }

        assertEquals(3, sink.failure().stageIndex)
    }

    @Test
    @DisplayName("el fallo y el terminal del step dicen el mismo stage")
    fun `the failure and the step's terminal name the same stage`() = runBlocking {
        val sink = RecordingSink()

        StepExecutionBoundary(sink).execute(context()) {
            CommonExecutionResult(outcome = StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "exit 1")))
        }

        // Los dos eventos del mismo step tienen que señalar el mismo stage. Si no, un consumidor
        // que los lea juntos vería un fallo en un stage y un terminal en otro, y no habría forma
        // de saber cuál de los dos dice la verdad.
        assertEquals(sink.terminal().stageIndex, sink.failure().stageIndex)
    }
}
