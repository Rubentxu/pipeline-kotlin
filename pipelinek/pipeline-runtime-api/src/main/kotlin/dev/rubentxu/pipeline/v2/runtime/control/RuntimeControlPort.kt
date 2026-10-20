package dev.rubentxu.pipeline.v2.runtime.control

/**
 * Public terminal control verb that closes a run, exactly once.
 * ## Why this port exists
 * A run can die in a way nobody planned — the agent is killed, the machine
 * reboots — and it then says two things at once: its journal says an
 * operation was still running, and its event lane says the run never
 * finished. The closest surfaces do not close that gap:
 *  - `DurableShellLaunching.kill` — kills ONE subprocess of the writer; no
 *    journal write, no terminality guarantee, no fencing. Calling it from
 *    outside the writer's JVM would corrupt the journal.
 *  - `OutputSealPort.seal` — terminal marker for a stream; refuses appends
 *    after seal; NOT a run cancel.
 *  - The internal `OperationJournal.append` path the coordinator uses to close
 *    a run today — reachable only through the internal `RunLifecycleEngine`,
 *    which has no external entry.
 * This port publishes the close verb. The implementation lives in
 * `:pipeline-runtime`, composing the existing lease store, the existing
 * `OperationJournal`, the existing event lane and the existing
 * `OutputSealPort.seal`.
 * ## What a close writes
 * The run's terminal bookend in the event lane — the same bookend the run's
 * own writer would have written — and the closure itself in the journal. The
 * bookend carries the product's existing `aborted` outcome, because the run
 * reached no outcome of its own and the run-outcome vocabulary has no second
 * word for it. The journal row says that a CANCELLATION happened, which is
 * what makes a second call a no-op rather than a second terminal state.
 * ## Idempotency invariant
 * A cancel call made N times produces one terminal state, not N. The second call
 * returns [CancelOutcome.AlreadyCancelled], NOT a refusal. A refusal under race
 * is a bug.
 * ## Lease boundary
 * A cancel never crosses a LIVE boundary. It takes the run's lease, and the
 * lease store's acquisition is what proves that no live process is publishing
 * the run: the OS lock behind the lease is granted only when nobody else
 * holds it. A live owner is therefore a refusal
 * ([CancelRefusal.LeaseHeldByAnother]), never a takeover.
 * A dead owner IS taken over, and that takeover is the point: a run whose
 * writer was killed still holds a lease record that claims its owner is alive,
 * and only the lock can tell the difference. The takeover is also what makes
 * the terminal bookend exclusive — two bookends for one run is the ambiguity
 * this port exists to remove.
*/
fun interface RuntimeControlPort {

    /**
     * Cancel `runId` for `reason`. Idempotent: a second call returns
     * [CancelOutcome.AlreadyCancelled].
     * @param runId The run to cancel. `String`, NOT typed.
     * @param reason The reason the cancel was issued; carried in the terminal
     *   `RunFinished` event's payload and surfaced in the journal row.
     * @return A [CancelOutcome] that is one of [CancelOutcome.Cancelled] (this
     *   call flipped the state), [CancelOutcome.AlreadyCancelled] (a prior call
     *   already flipped it), or [CancelOutcome.Refused] (a closed
     *   [CancelRefusal] reason).
     *   NEVER throws. A refusal is the explicit answer the contract expects for
     *   "may not cancel" scenarios.
*/
    fun cancel(runId: String, reason: CancelReason): CancelOutcome
}
