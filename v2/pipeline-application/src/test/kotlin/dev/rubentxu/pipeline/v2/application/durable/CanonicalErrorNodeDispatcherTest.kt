package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalErrorNodeDispatcherTest {
    @Test
    fun `dispatches a canonical error node as a typed failure without owning lifecycle events`() {
        val command = CanonicalCoreStepCommand.Error(
            message = "deployment denied",
            failureKind = FailureKind.USER,
        )

        val outcome = CanonicalErrorNodeDispatcher().dispatch(command)

        assertEquals(
            StepOutcome.Failure(PipelineFailure(FailureKind.USER, "deployment denied")),
            outcome,
        )
    }
}
