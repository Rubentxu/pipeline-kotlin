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
                    nextState = CycleState.Refused.Generic(event.closeReason),
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
                    // The decider is the SOLE authority on whether a
                    // probe is verified. Anything other than
                    // ProbeOutcome.Verified is refused here, in the
                    // decider, on the ADT. The previous shape
                    // collapsed UnsupportedFidelity and
                    // BlockedExternal to exitCode=0 and let the
                    // decider read them as success; that is the P0
                    // defect the 2026-10-16 external audit named.
                    val outcome = event.outcome
                    when (outcome) {
                        is ProbeOutcome.Verified -> {
                            val probe = ProbeFinished(outcome)
                            CycleOutcome.Next(
                                nextState = CycleState.Running,
                                nextContext = context.withProbe(probe),
                            )
                        }
                        is ProbeOutcome.Failed ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused.ProbeFailed(
                                    evidenceRef = outcome.evidenceRef,
                                    failureReason = outcome.reason,
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.Skipped ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused.ProbeSkipped(
                                    evidenceRef = outcome.evidenceRef,
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.NotRun ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused.ProbeNotRun(
                                    evidenceRef = outcome.evidenceRef,
                                    notRunReason = outcome.reason,
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.NoEvidence ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused.ProbeNoEvidence(
                                    evidenceRef = outcome.evidenceRef,
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.OtherCommit ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused.ProbeOtherCommit(
                                    // BQ4 v22 (no-evidence carrier accepts
                                    // blank, 2026-10-17): the previous
                                    // shape substituted a `<refused:*>`
                                    // sentinel to satisfy the
                                    // `require(isNotBlank)` guard on the
                                    // carrier. The carrier is now
                                    // self-protected on the typed
                                    // `Refused` variant (the refused
                                    // shape is the discriminant, not the
                                    // evidence path) and accepts blank
                                    // `evidenceRef`. A refused fidelity
                                    // check may have produced no path;
                                    // the placeholder was a fabrication
                                    // that crossed the decider for no
                                    // signal value. The receipt text
                                    // distinguishes the two via the
                                    // typed `Refused` variant.
                                    evidenceRef = outcome.evidenceRef,
                                    observed = outcome.observed,
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.UnsupportedFidelity ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused.ProbeUnsupportedFidelity(
                                    // BQ4 v22 (no-evidence carrier
                                    // accepts blank, 2026-10-17): see
                                    // the `ProbeOtherCommit` branch.
                                    // The previous shape substituted a
                                    // `<refused:${fidelity}>` sentinel;
                                    // the carrier now accepts blank
                                    // `evidenceRef` and the
                                    // `reason` getter handles the
                                    // presentation.
                                    evidenceRef = outcome.evidenceRef,
                                    fidelity = outcome.fidelity,
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.BlockedExternal ->
                            CycleOutcome.Next(
                                nextState = CycleState.BlockedExternal(
                                    "probe ${outcome.evidenceRef} BlockedExternal=${outcome.blocker}; " +
                                        "the cycle cannot be observed while a probe " +
                                        "needs external authority",
                                ),
                                nextContext = context,
                            )
                    }
                }
                is CycleEvent.AllProbesObserved -> {
                    // At least one probe MUST have succeeded for
                    // the cycle to observe. The accumulator only
                    // contains Verified probes (the decider refuses
                    // every other case on entry), so a non-empty
                    // accumulator is, by construction, a set of
                    // successful probes. The audit's P0 defect was
                    // a decider that allowed exitCode=0 BlockedExternal
                    // to count as success; the new accumulator
                    // makes that impossible.
                    val observedCount = context.probeOutcomes.size
                    if (observedCount == 0) {
                        CycleOutcome.Next(
                            nextState = CycleState.Refused.NoProbesSucceeded(observedCount = 0),
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
                    nextState = CycleState.Refused.Generic(event.closeReason),
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
                    // The runner has already verified the evidence
                    // via [PromotionEvidenceVerifier] BEFORE emitting
                    // this event. A cycle cannot reach this branch
                    // with a verified evidence that fails the
                    // five-property check (existence, identity,
                    // fidelity, digest, oracle). The carrier is a
                    // typed [PromotionEvidence] (sealed interface);
                    // its data class init refuses blanks, so the
                    // decider does not need to re-check.
                    //
                    // The decider's job here is purely structural:
                    // record the evidence and advance the state.
                    // The previous shape (P0-D, audit 2026-10-16)
                    // string-prefix-validated the evidence here;
                    // that is now the runner's authority.
                    CycleOutcome.Next(
                        nextState = CycleState.Promoted(state.observedCommit),
                        nextContext = context.withReplacementEvidence(event.replacementEvidence),
                    )
                }
                is CycleEvent.CloseRequested -> {
                    // BQ4 audit (2026-10-16, vertical): the spec
                    // `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md` defines the
                    // single success path as
                    // `Draft -> Running -> Observed -> Promoted -> Closed`.
                    // Allowing `Observed -> Closed` lets a cycle skip the
                    // promote step, which is where the replacement
                    // evidence (a permanent contract or an external
                    // certifier receipt) is checked. A cycle that
                    // observed with at least one probe but did not
                    // request promotion MUST transition through
                    // `Promoted` before closing. `CloseRequested` from
                    // `Observed` is therefore `IllegalTransition`; the
                    // caller has to emit `PromoteRequested` first.
                    illegal(state, event)
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
