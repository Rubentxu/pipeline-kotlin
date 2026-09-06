package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/** Decodes canonical `core.error` nodes into their typed step outcome. */
class CanonicalErrorNodeDispatcher {
    fun dispatch(command: CanonicalCoreStepCommand.Error): StepOutcome =
        StepOutcome.Failure(PipelineFailure(command.failureKind, command.message))
}
