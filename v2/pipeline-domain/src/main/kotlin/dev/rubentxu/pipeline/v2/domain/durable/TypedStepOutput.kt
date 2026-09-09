package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * LB-02 / G3-A4.3 — marker interface for Step-produced outputs that carry a canonical
 * [StepOutcome] alongside the durable payload.
 *
 * The pre-decode durable substrate [OperationOutput] stays untouched: it represents ONLY
 * what the journal needs to persist (`result`, `durationMs`, `finishedAt`). Whether the
 * Step ran successfully or failed is an orthogonal concern that the typed handler computes
 * from its domain-specific ADT (e.g. [dev.rubentxu.pipeline.v2.domain.ShellInvocationResult]).
 *
 * Contract:
 *  - `outcome` MUST be derived from the same domain ADT that produced [OperationOutput.result]
 *    via the single, reusable classifier for that Step (see e.g. `ShellStepOutcomeClassifier`).
 *  - `outcome` MUST be stable for the lifetime of this output (it is the input the
 *    [CommonExecutionBoundary] projects into `CommonExecutionResult.outcome`).
 *  - Implementations MUST NOT duplicate or override the classifier inside the Step handler;
 *    that would create two parallel outcome authorities and risk drift.
 *
 * [CommonExecutionBoundary] uses `produced as? TypedStepOutput` to project `outcome` without
 * needing to know the concrete Step type. Coordinator stays Step-agnostic.
 */
interface TypedStepOutput {
    val outcome: StepOutcome
}
