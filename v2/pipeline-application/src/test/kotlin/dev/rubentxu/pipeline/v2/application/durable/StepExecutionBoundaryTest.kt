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

        val result = StepExecutionBoundary(eventStore).execute(context) {
            CommonExecutionResult(
                outcome = StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "exit 7")),
                encodedOutput = null,
            )
        }
        val outcome = result.outcome

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

        val result = StepExecutionBoundary(eventStore).execute(context) {
            throw PluginStepException(failure)
        }
        val outcome = result.outcome

        assertEquals(StepOutcome.Failure(failure), outcome)
        val events = eventStore.eventsFor(context.runId).toList()
        assertEquals(1, events.filterIsInstance<StepStarted>().size)
        assertEquals(1, events.filterIsInstance<StepFailed>().size)
        assertEquals(1, events.filterIsInstance<StepFinished>().size)
        assertEquals(FailureKind.PLUGIN, events.filterIsInstance<StepFailed>().single().failureKind)
    }

    /**
     * UAT-JEP-002: sh exit 42 default mode yields `Failed(SCRIPT, exitCode=42)` and exactly one `StepFailed(kind=SCRIPT)`.
     */
    @Test
    fun `sh exit 42 in default mode emits exactly one StepFailed with SCRIPT kind`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val context = StepLifecycleContext(
            runId = "boundary-jep002-run",
            stageIndex = 0,
            stepIndex = 4,
            stepName = "build/shell",
            stepType = "sh",
        )

        val result = StepExecutionBoundary(eventStore).execute(context) {
            CommonExecutionResult(
                outcome = StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "shell exited with code 42")),
                encodedOutput = null,
            )
        }
        val outcome = result.outcome

        assertEquals(StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "shell exited with code 42")), outcome)
        val events = eventStore.eventsFor(context.runId).toList()
        assertEquals(1, events.filterIsInstance<StepStarted>().size)
        assertEquals(1, events.filterIsInstance<StepFailed>().size)
        assertEquals(1, events.filterIsInstance<StepFinished>().size)
        assertEquals(FailureKind.SCRIPT, events.filterIsInstance<StepFailed>().single().failureKind)
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
