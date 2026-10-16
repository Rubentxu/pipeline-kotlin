package dev.rubentxu.pipeline.v2.harness.cycle

/**
 * Pure decider for the cycle state machine.
 *
 * `decide(state, event)` returns the next state. The result is a sealed
 * ADT — either a state, or a [Cycle.IllegalTransition] refusal. The
 * decider is total: every (state, event) pair has a defined outcome.
 *
 * The decider does not consult the filesystem, the Git history, or the
 * Gradle build. The runner produces events; the decider classifies.
 */
object Cycle {
    fun decide(state: CycleState, event: CycleEvent): CycleOutcome {
        return when (state) {
            is CycleState.Draft -> when (event) {
                CycleEvent.PrepareStarted -> CycleOutcome.Next(CycleState.Running)
                is CycleEvent.CloseRequested -> CycleOutcome.Next(
                    CycleState.Refused(event.closeReason),
                )
                is CycleEvent.ExternalBlockerRaised -> CycleOutcome.Next(
                    CycleState.BlockedExternal(event.blocker),
                )
                is CycleEvent.ProbeFinished -> illegal(state, event)
                is CycleEvent.AllProbesObserved -> illegal(state, event)
                is CycleEvent.PromoteRequested -> illegal(state, event)
            }
            is CycleState.Running -> when (event) {
                is CycleEvent.ProbeFinished -> CycleOutcome.Stay(CycleState.Running)
                is CycleEvent.AllProbesObserved -> CycleOutcome.Next(
                    CycleState.Observed(event.observedCommit),
                )
                is CycleEvent.CloseRequested -> CycleOutcome.Next(
                    CycleState.Refused(event.closeReason),
                )
                is CycleEvent.ExternalBlockerRaised -> CycleOutcome.Next(
                    CycleState.BlockedExternal(event.blocker),
                )
                CycleEvent.PrepareStarted -> illegal(state, event)
                is CycleEvent.PromoteRequested -> illegal(state, event)
            }
            is CycleState.Observed -> when (event) {
                is CycleEvent.PromoteRequested -> CycleOutcome.Next(
                    CycleState.Promoted(state.observedCommit),
                )
                is CycleEvent.CloseRequested -> CycleOutcome.Next(
                    CycleState.Refused(event.closeReason),
                )
                is CycleEvent.ExternalBlockerRaised -> CycleOutcome.Next(
                    CycleState.BlockedExternal(event.blocker),
                )
                CycleEvent.PrepareStarted -> illegal(state, event)
                is CycleEvent.ProbeFinished -> illegal(state, event)
                is CycleEvent.AllProbesObserved -> illegal(state, event)
            }
            is CycleState.Promoted -> when (event) {
                is CycleEvent.CloseRequested -> {
                    // The closure carries the original "replacement evidence" that
                    // justified the promotion; the state does not need to retain it.
                    CycleOutcome.Next(CycleState.Refused(event.closeReason))
                }
                is CycleEvent.ExternalBlockerRaised -> CycleOutcome.Next(
                    CycleState.BlockedExternal(event.blocker),
                )
                CycleEvent.PrepareStarted -> illegal(state, event)
                is CycleEvent.ProbeFinished -> illegal(state, event)
                is CycleEvent.AllProbesObserved -> illegal(state, event)
                is CycleEvent.PromoteRequested -> illegal(state, event)
            }
            is CycleState.Refused, is CycleState.BlockedExternal -> {
                // Terminal: any further event is rejected. The cycle is closed.
                illegal(state, event)
            }
        }
    }

    private fun illegal(state: CycleState, event: CycleEvent): CycleOutcome.IllegalTransition =
        CycleOutcome.IllegalTransition(state, event)
}

/** Result of [Cycle.decide]. Closed. */
sealed interface CycleOutcome {
    /** Transition accepted; the state is the new value. */
    data class Next(val state: CycleState) : CycleOutcome

    /** The event is a no-op (e.g. a probe finished while we were already Running). */
    data class Stay(val state: CycleState) : CycleOutcome

    /** The (state, event) pair is illegal; the cycle does not move. */
    data class IllegalTransition(val state: CycleState, val event: CycleEvent) : CycleOutcome
}
