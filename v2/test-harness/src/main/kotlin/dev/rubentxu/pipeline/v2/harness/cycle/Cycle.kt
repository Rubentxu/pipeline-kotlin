package dev.rubentxu.pipeline.v2.harness.cycle

/**
 * Pure decider for the cycle state machine.
 *
 * `decide(state, event, context)` returns the next [CycleOutcome] plus
 * a [CycleContext] reflecting the accumulated evidence. The result
 * is a sealed ADT — either a state, a refusal, or an
 * [CycleOutcome.IllegalTransition]. The decider is total: every
 * (state, event, context) triple has a defined outcome.
 *
 * The decider does not consult the filesystem, the Git history, or
 * the Gradle build. The runner produces events; the decider
 * classifies. The accumulator ([CycleContext]) is the only thing
 * the decider threads across calls.
 *
 * BQ4 audit (2026-10-16, RC3 close-out): the previous decider
 * allowed a `Running → Observed → Closed` sequence with **zero**
 * probes run. The new decider enforces, on every transition, the
 * conditions the spec requires:
 *
 *   - `Running → Observed` requires at least one
 *     [ProbeFinished] in the context, with a non-zero exit code
 *     refused and a non-blank evidence ref.
 *   - `Running → Observed` requires the `observedCommit` of the
 *     [AllProbesObserved] event to be non-blank.
 *   - `Observed → Promoted` requires the [PromoteRequested] event
 *     to carry a non-blank `replacementEvidence`; the
 *     accumulator is updated and the transition is allowed.
 *   - `Observed → Closed` requires at least one probe in the
 *     accumulator (the spec's "no-op cycle without evidence
 *     refuses" rule; a cycle that finds nothing actionable but
 *     ran probes and observed them can close, but a cycle that
 *     never observed a single probe MUST be Refused, not
 *     Closed).
 *   - `Promoted → Closed` is allowed (the spec's success path);
 *     the close reason is preserved.
 *   - `Draft` and `Running` cannot close; they must transition
 *     to `Observed` first, which requires probes.
 *
 * The accumulator on the outcome is the **new** context. The
 * decider never mutates; the runner threads context through.
 */
object Cycle {
    fun decide(
        state: CycleState,
        event: CycleEvent,
        context: CycleContext = CycleContext(),
    ): CycleOutcome {
        return when (state) {
            is CycleState.Draft -> when (event) {
                CycleEvent.PrepareStarted -> CycleOutcome.Next(
                    nextState = CycleState.Running,
                    nextContext = context,
                )
                is CycleEvent.CloseRequested -> CycleOutcome.Next(
                    nextState = CycleState.Refused(event.closeReason),
                    nextContext = context,
                )
                is CycleEvent.ExternalBlockerRaised -> CycleOutcome.Next(
                    nextState = CycleState.BlockedExternal(event.blocker),
                    nextContext = context,
                )
                is CycleEvent.ProbeFinished -> illegal(state, event)
                is CycleEvent.AllProbesObserved -> illegal(state, event)
                is CycleEvent.PromoteRequested -> illegal(state, event)
            }
            is CycleState.Running -> when (event) {
                is CycleEvent.ProbeFinished -> {
                    // Reject a probe with a non-blank exit code OR
                    // a blank evidence ref before the accumulator
                    // sees it: the cycle is Refused, the runner
                    // can close it explicitly. This pins the
                    // spec's "Skipped, NotRun, NoEvidence niega
                    // Verified" rule at the probe boundary.
                    val probe = ProbeFinished(event.exitCode, event.evidenceRef)
                    if (!probe.succeeded) {
                        CycleOutcome.Next(
                            nextState = CycleState.Refused(
                                "probe ${event.evidenceRef} failed with exitCode=${event.exitCode}",
                            ),
                            nextContext = context,
                        )
                    } else {
                        CycleOutcome.Next(
                            nextState = CycleState.Running,
                            nextContext = context.withProbe(probe),
                        )
                    }
                }
                is CycleEvent.AllProbesObserved -> {
                    // At least one probe MUST have succeeded for
                    // the cycle to observe. Without this guard a
                    // cycle could observe the commit and then
                    // close without ever running a probe (the
                    // audit's P0 defect).
                    val succeeded = context.probeOutcomes.isNotEmpty() &&
                        context.probeOutcomes.all { it.succeeded }
                    if (!succeeded) {
                        CycleOutcome.Next(
                            nextState = CycleState.Refused(
                                "AllProbesObserved but no probe succeeded",
                            ),
                            nextContext = context,
                        )
                    } else {
                        CycleOutcome.Next(
                            nextState = CycleState.Observed(event.observedCommit),
                            nextContext = context,
                        )
                    }
                }
                is CycleEvent.CloseRequested -> CycleOutcome.Next(
                    nextState = CycleState.Refused(event.closeReason),
                    nextContext = context,
                )
                is CycleEvent.ExternalBlockerRaised -> CycleOutcome.Next(
                    nextState = CycleState.BlockedExternal(event.blocker),
                    nextContext = context,
                )
                CycleEvent.PrepareStarted -> illegal(state, event)
                is CycleEvent.PromoteRequested -> illegal(state, event)
            }
            is CycleState.Observed -> when (event) {
                is CycleEvent.PromoteRequested -> {
                    // The spec: a Promote is valid only when a
                    // replacement evidence (the substitute
                    // contract / property test that replaces the
                    // ephemeral cycle's coverage) is present.
                    val evidence = event.replacementEvidence
                    if (evidence.isBlank()) {
                        CycleOutcome.Next(
                            nextState = CycleState.Refused(
                                "PromoteRequested with blank replacementEvidence",
                            ),
                            nextContext = context,
                        )
                    } else {
                        CycleOutcome.Next(
                            nextState = CycleState.Promoted(state.observedCommit),
                            nextContext = context.withReplacementEvidence(evidence),
                        )
                    }
                }
                is CycleEvent.CloseRequested -> {
                    // The audit's other concern: an Observed
                    // cycle closing without promotion must
                    // STILL have at least one probe in the
                    // accumulator. A cycle that observed without
                    // any probe is Refused, not Closed.
                    if (context.probeOutcomes.isEmpty()) {
                        CycleOutcome.Next(
                            nextState = CycleState.Refused(
                                "CloseRequested from Observed without any ProbeFinished; " +
                                    "the cycle never collected evidence",
                            ),
                            nextContext = context,
                        )
                    } else {
                        CycleOutcome.Next(
                            nextState = CycleState.Closed(event.closeReason),
                            nextContext = context,
                        )
                    }
                }
                is CycleEvent.ExternalBlockerRaised -> CycleOutcome.Next(
                    nextState = CycleState.BlockedExternal(event.blocker),
                    nextContext = context,
                )
                CycleEvent.PrepareStarted -> illegal(state, event)
                is CycleEvent.ProbeFinished -> illegal(state, event)
                is CycleEvent.AllProbesObserved -> illegal(state, event)
            }
            is CycleState.Promoted -> when (event) {
                is CycleEvent.CloseRequested -> CycleOutcome.Next(
                    nextState = CycleState.Closed(event.closeReason),
                    nextContext = context,
                )
                is CycleEvent.ExternalBlockerRaised -> CycleOutcome.Next(
                    nextState = CycleState.BlockedExternal(event.blocker),
                    nextContext = context,
                )
                CycleEvent.PrepareStarted -> illegal(state, event)
                is CycleEvent.ProbeFinished -> illegal(state, event)
                is CycleEvent.AllProbesObserved -> illegal(state, event)
                is CycleEvent.PromoteRequested -> illegal(state, event)
            }
            is CycleState.Closed -> {
                // Closed is terminal.
                illegal(state, event)
            }
            is CycleState.Refused, is CycleState.BlockedExternal -> {
                illegal(state, event)
            }
        }
    }

    private fun illegal(state: CycleState, event: CycleEvent): CycleOutcome =
        CycleOutcome.IllegalTransition(state, event)
}

/** Result of [Cycle.decide]. */
sealed interface CycleOutcome {
    /** The new state and the new accumulator. */
    data class Next(
        val nextState: CycleState,
        val nextContext: CycleContext,
    ) : CycleOutcome

    /** The (state, event) pair is illegal; the cycle does not move. */
    data class IllegalTransition(val state: CycleState, val event: CycleEvent) : CycleOutcome
}
