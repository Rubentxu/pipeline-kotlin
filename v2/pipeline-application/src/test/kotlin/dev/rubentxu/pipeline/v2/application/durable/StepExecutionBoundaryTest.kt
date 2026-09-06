package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StepExecutionBoundaryTest {
    @Test
    fun `a failed step emits one complete lifecycle from the boundary`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val context = StepLifecycleContext(
            runId = "boundary-failure-run",
            stageIndex = 0,
            stepIndex = 1,
            stepName = "build/fail",
            stepType = "sh",
        )

        val outcome = StepExecutionBoundary(eventStore).execute(context) {
            StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "exit 7"))
        }

        assertEquals(StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "exit 7")), outcome)
        val events = eventStore.eventsFor(context.runId).toList()
        assertEquals(1, events.filterIsInstance<StepStarted>().size)
        assertEquals(1, events.filterIsInstance<StepFailed>().size)
        assertEquals(1, events.filterIsInstance<StepFinished>().size)
        assertEquals(FailureKind.SCRIPT, events.filterIsInstance<StepFailed>().single().failureKind)
    }

    @Test
    fun `a typed handler exception becomes one failed step outcome`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val context = StepLifecycleContext(
            runId = "boundary-exception-run",
            stageIndex = 0,
            stepIndex = 2,
            stepName = "build/plugin",
            stepType = "plugin",
        )
        val failure = PipelineFailure(FailureKind.PLUGIN, "plugin contract rejected input")

        val outcome = StepExecutionBoundary(eventStore).execute(context) {
            throw PluginStepException(failure)
        }

        assertEquals(StepOutcome.Failure(failure), outcome)
        val events = eventStore.eventsFor(context.runId).toList()
        assertEquals(1, events.filterIsInstance<StepStarted>().size)
        assertEquals(1, events.filterIsInstance<StepFailed>().size)
        assertEquals(1, events.filterIsInstance<StepFinished>().size)
        assertEquals(FailureKind.PLUGIN, events.filterIsInstance<StepFailed>().single().failureKind)
    }

    @Test
    fun `an engine invariant propagates without a StepFailed event`() {
        val eventStore = InMemoryEventStore()
        val context = StepLifecycleContext(
            runId = "boundary-invariant-run",
            stageIndex = 0,
            stepIndex = 3,
            stepName = "build/invariant",
            stepType = "engine",
        )

        assertThrows(EngineInvariantViolation::class.java) {
            runBlocking {
                StepExecutionBoundary(eventStore).execute(context) {
                    throw EngineInvariantViolation("scope stack underflow")
                }
            }
        }

        val events = eventStore.eventsFor(context.runId).toList()
        assertEquals(1, events.filterIsInstance<StepStarted>().size)
        assertEquals(0, events.filterIsInstance<StepFailed>().size)
        assertEquals(1, events.filterIsInstance<StepFinished>().size)
    }
}
