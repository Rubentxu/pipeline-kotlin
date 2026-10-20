package dev.rubentxu.pipeline.v2.domain

/**
 * Pure reducer from a sequence of [StepOutcome] to the canonical
 * [RunOutcome] of the run.
 *
 * This is the **single** authority that produces a [RunOutcome]. No other
 * site in the codebase is allowed to fabricate a `RunOutcome` literal; any
 * such fabrication is a migration gap and a violation of
 * `CANONICAL_CONTRACTS_SPEC.md §Outcomes` ("`RunOutcome` se obtiene
 * mediante un único reducer").
 *
 * Reduction rules, in priority order:
 *
 * 1. The **first** [StepOutcome.Failure] wins; the resulting
 *    [RunOutcome.Failure] carries exactly that [PipelineFailure]. No later
 *    failure, no [StepOutcome.Unstable], no [StepOutcome.Success] overrides
 *    a failure.
 * 2. Otherwise, the **first** [StepOutcome.Unstable] wins; the result is
 *    [RunOutcome.Unstable]. Multiple unstable steps collapse to one.
 * 3. Otherwise, an empty list reduces to [RunOutcome.Success] (the run had
 *    nothing to do, and that is not a failure).
 * 4. Otherwise, all-success reduces to [RunOutcome.Success].
 *
 * [RunOutcome.Aborted] is **never** derived from steps: it is set explicitly
 * by the orchestrator when a run is cancelled or interrupted. This makes
 * "aborted" auditable as a separate terminal state and keeps the reducer
 * deterministic with respect to its inputs.
 *
 * The function is pure: no I/O, no wall-clock, no logging. Determinism is
 * what makes the reducer safely replayable across event-log reconstruction.
 */
object RunOutcomeReducer {
    fun reduce(steps: List<StepOutcome>): RunOutcome {
        steps.forEach { step ->
            if (step is StepOutcome.Failure) return RunOutcome.Failure(step.failure)
        }
        steps.forEach { step ->
            if (step is StepOutcome.Unstable) return RunOutcome.Unstable
        }
        return RunOutcome.Success
    }
}
