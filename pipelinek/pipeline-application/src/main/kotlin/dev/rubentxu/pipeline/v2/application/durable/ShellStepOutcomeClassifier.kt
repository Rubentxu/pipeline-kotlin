package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * Public classifier from the closed [ShellInvocationResult] ADT
 * into the canonical [StepOutcome].
 * This is the ONE table that classifies the result of a shell step: it used to
 * be duplicated as a `when` inside `ShExecution`, and the copy that lived
 * beside the execution code is gone with the rest of the legacy projections.
 * Mapping:
 *  - [ShellInvocationResult.UnitValue] / [ShellInvocationResult.Stdout] / [ShellInvocationResult.Status]
 *    -> [StepOutcome.Success]
 *  - [ShellInvocationResult.Failed]
 *    -> [StepOutcome.Failure] with the original typed [PipelineFailure] (kind, message, cause)
 *  - [ShellInvocationResult.Interrupted]
 *    -> [StepOutcome.Failure] with `FailureKind.TIMEOUT` and the interruption message.
 *    The full typed `InterruptionRecord` (including the original [dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind])
 *    is preserved on the typed `ShellOutput` so a Step consumer can recover it.
 * This function is the SINGLE classifier authority for `core.sh`. Do not duplicate
 * or branch on `ShellInvocationResult` shape anywhere else.
*/
fun ShellInvocationResult.toStepOutcome(): StepOutcome = when (this) {
    ShellInvocationResult.UnitValue,
    is ShellInvocationResult.Stdout,
    is ShellInvocationResult.Status,
    -> StepOutcome.Success

    is ShellInvocationResult.Failed -> StepOutcome.Failure(failure)
    is ShellInvocationResult.Interrupted -> StepOutcome.Failure(
        PipelineFailure(FailureKind.TIMEOUT, interruption.message),
    )
}
