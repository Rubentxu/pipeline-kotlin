package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * LB-02 / G3-A4.3.4 / A4.3.7 — public classifier from the closed [ShellInvocationResult] ADT
 * into the canonical [StepOutcome].
 *
 * This is the SAME table that the legacy `ShExecution.runShellCommandTyped` path uses
 * (extracted from the private `ShExecution.toStepOutcome` extension so the registry-routed
 * `core.sh` handler can call it without depending on `ShExecution` directly).
 *
 * Mapping:
 *  - [ShellInvocationResult.UnitValue] / [ShellInvocationResult.Stdout] / [ShellInvocationResult.Status]
 *    -> [StepOutcome.Success]
 *  - [ShellInvocationResult.Failed]
 *    -> [StepOutcome.Failure] with the original typed [PipelineFailure] (kind, message, cause)
 *  - [ShellInvocationResult.Interrupted]
 *    -> [StepOutcome.Failure] with `FailureKind.TIMEOUT` and the interruption message.
 *    The full typed `InterruptionRecord` (including the original [dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind])
 *    is preserved on the typed `ShellOutput` so a Step consumer can recover it.
 *
 * This function is the SINGLE classifier authority for `core.sh`. It MUST stay aligned with
 * the `when` previously defined inside `ShExecution.runShellCommandTyped`; do not duplicate
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
