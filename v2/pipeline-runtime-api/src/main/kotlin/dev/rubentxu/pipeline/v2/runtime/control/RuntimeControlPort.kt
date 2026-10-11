package dev.rubentxu.pipeline.v2.runtime.control

/**
 * M2 — public terminal control verb that flips a run's durable state to
 * `CANCELLED`, exactly once.
 *
 * ## Why this port exists
 *
 * The audit's B.2 / B.7 gap is that no public Cancel port exists today. The
 * closest surfaces are:
 *
 *  - `DurableShellLaunching.kill` — kills ONE subprocess of the writer; no
 *    journal write, no terminality guarantee, no fencing. Calling it from
 *    outside the writer's JVM would corrupt the journal.
 *  - `OutputSealPort.seal` — terminal marker for a stream; refuses appends
 *    after seal; NOT a run cancel.
 *  - The internal `OperationJournal.append` path the coordinator uses to close
 *    a run today — reachable only through the internal `RunLifecycleEngine`,
 *    which has no external entry.
 *
 * This port publishes the cancel verb. The implementation lives in
 * `:pipeline-runtime` (Step 2 of the work), composing the existing
 * `RunExecutionLease.acquire` decider, the existing `OperationJournal.append`,
 * the existing `OutputSealPort.seal`, and the existing `ObservationWakeup`
 * vocabulary.
 *
 * ## Idempotency invariant (audit D.3)
 *
 * A cancel call made N times produces one terminal state, not N. The second call
 * returns [CancelOutcome.AlreadyCancelled], NOT a refusal. A refusal under race
 * is a bug.
 *
 * ## Lease boundary (audit B.2, B.8, G.3)
 *
 * Cancel MUST NOT cross lease boundaries. The implementation consults the existing
 * `RunExecutionLease.acquire` pure decider BEFORE it writes the terminal row, and
 * refuses with [CancelRefusal.LeaseHeldByAnother] on
 * `LeaseAcquisition.AlreadyOwned`.
 *
 * Cancel does NOT take a fresh lease; it consults the existing one.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §4.
 */
fun interface RuntimeControlPort {

    /**
     * Cancel `runId` for `reason`. Idempotent: a second call returns
     * [CancelOutcome.AlreadyCancelled].
     *
     * @param runId The run to cancel. `String`, NOT typed.
     * @param reason The reason the cancel was issued; carried in the terminal
     *   `RunFinished` event's payload and surfaced in the journal row.
     * @return A [CancelOutcome] that is one of [CancelOutcome.Cancelled] (this
     *   call flipped the state), [CancelOutcome.AlreadyCancelled] (a prior call
     *   already flipped it), or [CancelOutcome.Refused] (a closed
     *   [CancelRefusal] reason).
     *
     *   NEVER throws. A refusal is the explicit answer the contract expects for
     *   "may not cancel" scenarios.
     */
    fun cancel(runId: String, reason: CancelReason): CancelOutcome
}
