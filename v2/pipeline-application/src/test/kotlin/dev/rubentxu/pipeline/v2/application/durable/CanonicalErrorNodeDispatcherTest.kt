package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFailed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalErrorNodeDispatcherTest {
    @Test
    fun `dispatches a canonical error node as a typed failure and records it`() {
        val eventStore = InMemoryEventStore()
        val runId = "canonical-error-run"
        val command = CanonicalCoreStepCommand.Error(
            message = "deployment denied",
            failureKind = FailureKind.USER,
        )

        val outcome = CanonicalErrorNodeDispatcher().dispatch(
            command,
            CanonicalErrorDispatchContext(
                runId = runId,
                stepIndex = 0,
                eventSink = eventStore,
            ),
        )

        assertEquals(
            StepOutcome.Failure(PipelineFailure(FailureKind.USER, "deployment denied")),
            outcome,
        )
        assertEquals(
            "deployment denied",
            (eventStore.eventsFor(runId).single() as StepFailed).message,
        )
    }
}
