package dev.rubentxu.pipeline.v2.runtime.recover

/**
 * Public idempotent recover verb that brings a run back to a known state
 * WITHOUT re-executing external effects.
 * ## Why this port exists
 * The audit's B.3 / B.6 gap is that every existing `Recover` path is internal to
 * `EffectReplayPolicy.decide`, `RunningSubprocessRecovery.observe`,
 * `RecoveredExecutionMaterializer.materialize`, `RecoveryInterpretationEngine
 * .interpret`. None is reachable from outside the JVM that owns the run.
 * The contract surface that comes closest is
 * [dev.rubentxu.pipeline.v2.output.OutputRefusal.RecoveryNotCompleted] — the
 * SIGNAL of unreadiness, not a way to act on it.
 * This port publishes the recover verb. The implementation composes the existing
 * journal / cursor / output-recovery path through the pure
 * [RuntimeRecoverDecision] decider (Step 2).
 * ## Idempotency + no-rerun invariant (audit D.4)
 * A second call to `recover(runId)` on the same run is a no-op: the journal
 * already says the run is reconciled, and the recover port returns
 * [RecoverOutcome.AlreadyRecovered] without re-running the substrate observer
 * or invoking any step's `execute` again.
 * ## What recover does and does NOT do
 * DOES:
 *  - Read the journal (`OperationJournal.listForRun`) to determine what was
 *    already done.
 *  - Read the cursor (`ReplayCursorStore.load`) to determine where the run
 *    left off.
 *  - Consult [RuntimeRecoverDecision.decideRecovery] (the pure decider the
 *    audit's B.6 asks for).
 *  - For `ReuseTerminal`: materialise via the existing
 *    [dev.rubentxu.pipeline.v2.application.durable.RecoveredExecutionMaterializer.materialize],
 *    append the terminal journal row via [dev.rubentxu.pipeline.v2.events.durable.OperationJournal.append],
 *    advance the cursor via [dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore.advance],
 *    and reconcile the output store via the existing
 *    [dev.rubentxu.pipeline.v2.output.store.OutputRecoveryPort.recover] plus the
 *    existing [dev.rubentxu.pipeline.v2.output.OutputFrameIndex.recoverUnframedBytes]
 *    in that order.
 * DOES NOT:
 *  - Re-execute external side effects. The recovered terminal is the journal's
 *    own observation of the RUN; no step's `execute` is called.
 *  - Introduce a new scheduler. The recover path is pull-by-call.
 *  - Modify output bytes already persisted.
 *
 * ## The lease, and why the recover takes it
 *
 * The recover used to CONSULT the lease record and decide with it. The record
 * is a statement about the PAST: a writer that was killed leaves its record
 * behind, and the record still says its owner is alive. So a recover of a run
 * whose writer died concluded the run was busy and refused — which is the one
 * run a recover exists for.
 *
 * The recover therefore TAKES the run's lease — the lock, not the record — and
 * gives it back when it finishes. Taking it is what makes "nobody is
 * publishing this run" a fact instead of a guess, and giving it back is what
 * leaves the run closable for whoever comes next. The lease is the SAME lease
 * the run's writer held; there is no second lease and no second authority over
 * who owns a run.
*/
interface RuntimeRecoverPort {

    /**
     * Recover `runId`. Idempotent: a second call returns
     * [RecoverOutcome.AlreadyRecovered]. Does NOT re-execute external effects.
     * @param runId The run to recover. `String`, NOT typed.
     * @param options The recovery options; default is [RecoverOptions.Default].
     *   Use `dryRun = true` to compute the decision without writing.
     * @return A [RecoverOutcome] that is one of [RecoverOutcome.RecoveredTerminal],
     *   [RecoverOutcome.ReattachPending], [RecoverOutcome.FailClosed], or
     *   [RecoverOutcome.AlreadyRecovered].
     *   NEVER throws. A substrate that cannot be observed is returned as a typed
     *   refusal, not an exception.
*/
    fun recover(
        runId: String,
        options: RecoverOptions,
    ): RecoverOutcome

    companion object {
        /**
         * Upper bound on a single `recover` call's wall time.
         * Implementation default, NOT a contract surface. The recover port must
         * return [RecoverOutcome.ReattachPending] before this bound expires.
*/
        const val DEFAULT_RECOVER_DEADLINE_MS: Long = 2_000L
    }
}
