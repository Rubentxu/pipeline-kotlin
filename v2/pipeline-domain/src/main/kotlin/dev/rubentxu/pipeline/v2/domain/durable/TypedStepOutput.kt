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

/**
 * S4-D2 — the ONE authority for `typed output -> StepOutcome`.
 *
 * This is the rule `CommonExecutionBoundary` has always applied inline:
 * `(produced as? TypedStepOutput)?.outcome ?: StepOutcome.Success`. It is extracted here,
 * beside the carrier it projects, because a second frontend now needs the identical answer and
 * two inline copies of one semantic rule are two authorities that drift.
 *
 * ## Why it is not a classifier registry
 *
 * The Step's own typed ADT is the authority; this function only asks the carrier for what it
 * already decided. It never branches on a StepKey, never inspects a concrete output type, and
 * never re-derives an outcome. A Step that wants a different answer implements
 * [TypedStepOutput.outcome] differently, which is where that decision belongs.
 *
 * ## Why this makes fresh and reuse agree by construction
 *
 * ```text
 * FRESH   handler returns O            -> outcomeOf(O)
 * REUSE   persisted bytes -> decode -> O -> outcomeOf(O)
 * ```
 *
 * Reuse recovers `O` through the Step's own declared `outputCodec`, so the carrier is
 * reconstructed rather than remembered. The durable status cannot be the source: a Step that
 * completed as `Unstable` is journalled `SUCCEEDED`, and `Success` is too, so the two are
 * indistinguishable there. Reading the outcome out of the typed payload is the only place the
 * distinction still exists — and it is a place the durable format already stores.
 *
 * @param value any value a Step handler produced, including `Unit`.
 * @return the carrier's outcome, or [StepOutcome.Success] when the output is not a
 *   [TypedStepOutput]. The `Success` default is the legacy convention `core.echo` relies on.
 */
fun outcomeOf(value: Any): StepOutcome = (value as? TypedStepOutput)?.outcome ?: StepOutcome.Success
