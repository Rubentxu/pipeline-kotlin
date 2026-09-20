package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.AdvanceAfterFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ResumeAttempt
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt

/**
 * WU-LPR-022 — Pure engine directive derived from a
 * [RetryReconciliationDecision].
 *
 * The [RetryReconciler] returns a decision that says WHAT the durable state
 * implies; the [DurableControlEnginePolicy] translates that decision into a
 * directive that says WHAT the runner must DO.
 *
 * Splitting these two layers keeps the durable spine deterministic: the
 * reconciler owns "what is the truth", the engine policy owns "what should
 * happen now", and the runner (coordinator) owns "perform the effects".
 *
 * ## What this is NOT
 *
 *  - NOT an effect. The directive is a typed value; the runner is the
 *    effectful component that materializes it (launches children, writes
 *    journal rows, emits events).
 *  - NOT a replacement for [RetryReconciler]. The reconciler is authoritative
 *    for "is the durable state ambiguous"; the policy is authoritative for
 *    "given a sound decision, what's the runner's next step".
 *  - NOT a StepKey branch. The mapping is closed over the
 *    [RetryReconciliationDecision] ADT and the [maxAttempts] budget. Adding a
 *    new decision case forces the engine policy to be revisited (compile-time
 *    exhaustiveness check).
 *
 * ## Migration note (post WU-LPR-022)
 *
 * The policy defined here is the seam the canonical coordinator will consume
 * once it migrates its inline retry loop behind an engine (this is part of
 * WU-LPR-024, InvocationEngine seam, which extracts the metadata/fingerprint/
 * journal/replay/capability boundary). Until then, the canonical
 * `CanonicalDurableRunCoordinator.dispatchBody` continues to inline the
 * retry loop, and this policy is a pure contract test target.
 */
fun interface DurableControlEnginePolicy {
    /**
     * `(decision, maxAttempts) -> EngineDirective`. Pure, total, no effects.
     *
     * @param decision The reconciler's verdict on durable state.
     * @param maxAttempts The decoded attempt budget (≥ 1). Any directive that
     *   would exceed this budget is reported as [EngineDirective.FailClosed]
     *   (the engine is fail-closed against decision/budget incoherence).
     */
    fun directiveFor(decision: RetryReconciliationDecision, maxAttempts: Int): EngineDirective
}

/**
 * Pure directive for the runner. Closed ADT over the legal next actions a
 * durable control engine can take in response to a reconciliation decision.
 *
 * Mapping:
 *
 *  - [LaunchChild] — execute the body's children at [attempt]. [fresh] = true
 *    means a fresh attempt (no journal evidence to resume); false means the
 *    journal evidence at [attempt] is mid-run and must be resumed without
 *    launching a new child.
 *  - [CloseTerminal] — the aggregate has reached a terminal state. [success]
 *    distinguishes the SUCCEEDED closure from the FAILED closure. The runner
 *    emits `RetrySucceeded` / `RetryFailed` (or `WaitUntilCompleted`) and the
 *    canonical loop terminates.
 *  - [FailClosed] — the decision/budget pair is incoherent (e.g.
 *    [AdvanceAfterFailure] with `to > maxAttempts`, or [ScheduleAttempt] with
 *    `attempt > maxAttempts`). The runner surfaces this as a typed
 *    `PipelineFailure(FailureKind.ENGINE, ...)`.
 */
sealed interface EngineDirective {

    /**
     * Launch body children at [attempt].
     *
     * @property attempt The deterministic attempt ordinal (1-indexed).
     * @property fresh `true` = new child execution; `false` = resume mid-run
     *   journal evidence (no new child launched).
     */
    data class LaunchChild(val attempt: Int, val fresh: Boolean) : EngineDirective

    /** Aggregate reached terminal state. [success] distinguishes SUCCEEDED vs FAILED. */
    data class CloseTerminal(val success: Boolean, val attempt: Int) : EngineDirective

    /**
     * Decision/budget pair is incoherent. The runner MUST NOT launch any
     * child. Surface as typed ENGINE failure with [reason].
     */
    data class FailClosed(val reason: String) : EngineDirective
}

/**
 * WU-LPR-022 — Reference implementation of [DurableControlEnginePolicy].
 *
 * Translation table (decision → directive, against [maxAttempts]):
 *
 * | Decision                          | Precondition           | Directive                                   |
 * |-----------------------------------|------------------------|---------------------------------------------|
 * | `ScheduleAttempt(attempt)`        | `attempt ∈ [1..max]`   | `LaunchChild(attempt, fresh = true)`        |
 * | `ScheduleAttempt(attempt)`        | `attempt > max`        | `FailClosed("schedule beyond maxAttempts")` |
 * | `ResumeAttempt(attempt)`          | `attempt ∈ [1..max]`   | `LaunchChild(attempt, fresh = false)`       |
 * | `ResumeAttempt(attempt)`          | `attempt > max`        | `FailClosed("resume beyond maxAttempts")`   |
 * | `CloseSuccessFromChild(attempt)`  | always                 | `CloseTerminal(success = true, attempt)`    |
 * | `AdvanceAfterFailure(from, to)`   | `to ∈ [1..max]`        | `LaunchChild(to, fresh = true)`             |
 * | `AdvanceAfterFailure(from, to)`   | `to > max`             | `FailClosed("advance beyond maxAttempts")`  |
 * | `ReuseSuccess(attempt)`           | always                 | `CloseTerminal(success = true, attempt)`    |
 * | `ReuseFailure(attempt)`           | always                 | `CloseTerminal(success = false, attempt)`   |
 * | `RejectDivergence(_, reason)`     | always                 | `FailClosed(reason)`                        |
 *
 * Total over the decision ADT — adding a decision case forces this `when`
 * to be revisited (compile-time exhaustiveness check).
 */
object DefaultDurableControlEnginePolicy : DurableControlEnginePolicy {
    override fun directiveFor(
        decision: RetryReconciliationDecision,
        maxAttempts: Int,
    ): EngineDirective = when (decision) {
        is ScheduleAttempt -> launchOrFail(decision.attempt, fresh = true, maxAttempts, "schedule")
        is ResumeAttempt -> launchOrFail(decision.attempt, fresh = false, maxAttempts, "resume")
        is CloseSuccessFromChild -> EngineDirective.CloseTerminal(success = true, attempt = decision.attempt)
        is AdvanceAfterFailure -> {
            if (decision.to in 1..maxAttempts) {
                EngineDirective.LaunchChild(attempt = decision.to, fresh = true)
            } else {
                EngineDirective.FailClosed(
                    reason = "AdvanceAfterFailure target ${decision.to} exceeds maxAttempts=$maxAttempts",
                )
            }
        }
        is ReuseSuccess -> EngineDirective.CloseTerminal(success = true, attempt = decision.attempt)
        is ReuseFailure -> EngineDirective.CloseTerminal(success = false, attempt = decision.attempt)
        is RejectDivergence -> EngineDirective.FailClosed(reason = decision.reason)
    }

    private fun launchOrFail(
        attempt: Int,
        fresh: Boolean,
        maxAttempts: Int,
        op: String,
    ): EngineDirective = if (attempt in 1..maxAttempts) {
        EngineDirective.LaunchChild(attempt = attempt, fresh = fresh)
    } else {
        EngineDirective.FailClosed(
            reason = "cannot $op attempt=$attempt beyond maxAttempts=$maxAttempts",
        )
    }
}
