package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput

/**
 * LB-02 / G3-A4.3 — typed output carrier for `core.sh` handlers.
 *
 * This is the COLOCATED pair of:
 *  - the closed [ShellInvocationResult] ADT (what actually happened at the shell);
 *  - the canonical [StepOutcome] (what the journal+boundary should record).
 *
 * Carrying BOTH on the same carrier enforces the invariant that there is one and only one
 * outcome authority for `core.sh` (`toStepOutcome()` in
 * [dev.rubentxu.pipeline.v2.application.durable.ShellStepOutcomeClassifier]). The handler
 * is the only place where the two are computed together; downstream consumers always read
 * `outcome` from the carrier instead of re-classifying the ADT.
 *
 * The carrier intentionally does NOT carry `capturedStdout` or `durationMs`:
 *  - `capturedStdout` is derivable from `result` (the [ShellInvocationResult.Stdout.value] field).
 *  - `durationMs` is the substrate's concern and lives on [dev.rubentxu.pipeline.v2.domain.durable.OperationOutput].
 *
 * Implements [TypedStepOutput] so `CommonExecutionBoundary` can project `outcome` via
 * `produced as? TypedStepOutput` without needing to know about `core.sh`.
 */
data class CoreShellOutput(
    val result: ShellInvocationResult,
    override val outcome: StepOutcome,
) : TypedStepOutput
