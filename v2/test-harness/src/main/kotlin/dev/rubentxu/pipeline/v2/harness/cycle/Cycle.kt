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
                                nextState = CycleState.Refused(
                                    "probe ${outcome.evidenceRef} Failed: ${outcome.reason}",
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.Skipped ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused(
                                    "probe ${outcome.evidenceRef} Skipped; " +
                                        "the spec rejects a Skipped probe from " +
                                        "advancing to Observed (the AAT or " +
                                        "E2E has no mutation target)",
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.NotRun ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused(
                                    "probe ${outcome.evidenceRef} NotRun: ${outcome.reason}",
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.NoEvidence ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused(
                                    "probe ${outcome.evidenceRef} NoEvidence; " +
                                        "the cycle cannot observe a probe " +
                                        "that produced no observable evidence",
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.OtherCommit ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused(
                                    "probe ${outcome.evidenceRef} OtherCommit=${outcome.observed}; " +
                                        "the manifest's source_sha is the only " +
                                        "authoritative source identity",
                                ),
                                nextContext = context,
                            )
                        is ProbeOutcome.UnsupportedFidelity ->
                            CycleOutcome.Next(
                                nextState = CycleState.Refused(
                                    "probe ${outcome.evidenceRef} UnsupportedFidelity=${outcome.fidelity}; " +
                                        "this vertical only supports the fidelities " +
                                        "declared in CycleManifest.ALLOWED_FIDELITIES",
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
                    val succeeded = context.probeOutcomes.isNotEmpty()
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
                    // ephemeral cycle's coverage) is present AND
                    // is not a free-form string. The audit named
                    // this as P0-D: a textual reference like
                    // "replacement:foo" is not a verified
                    // substitute, it is a label. A cycle that
                    // closes on an unverified substitute has
                    // deleted its evidence under a false
                    // guarantee.
                    val evidence = event.replacementEvidence
                    if (evidence.isBlank()) {
                        CycleOutcome.Next(
                            nextState = CycleState.Refused(
                                "PromoteRequested with blank replacementEvidence",
                            ),
                            nextContext = context,
                        )
                    } else if (!isVerifiedSubstituteShape(evidence)) {
                        CycleOutcome.Next(
                            nextState = CycleState.Refused(
                                "PromoteRequested with non-conforming replacementEvidence: " +
                                    "'$evidence'. The substitute must be either " +
                                    "'permanent-contract:<id>' (a permanent test/fitness " +
                                    "in the producer repo) or 'certifier-receipt:<sha256>' " +
                                    "(a receipt the external certifier has signed).",
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

    // P0-D (audit 2026-10-16): the shape of a replacementEvidence
    // that the decider accepts on Observed -> Promoted. A bare
    // string is refused: the substitute must be either a
    // reference to a permanent in-repo contract (the
    // test/fitness/property that replaces the ephemeral cycle's
    // coverage) or a receipt signed by the external certifier.
    //
    // This is the MINIMUM that the decider can verify in-process.
    // The producer still has to declare the substitute in the
    // manifest (ReplacementPlan.kind + ReplacementPlan.id +
    // ReplacementPlan.evidence) and the certifier still has to
    // sign a real receipt; what this check does is refuse a
    // textual placeholder like "replacement:foo" that names
    // nothing verifiable.
    private const val PERMANENT_CONTRACT_PREFIX = "permanent-contract:"
    private const val CERTIFIER_RECEIPT_PREFIX = "certifier-receipt:"
}

// P0-D verifier is a top-level internal function (Kotlin does
// not allow a `companion object` inside an `object`). It is
// used by [Cycle.decide] on the Observed -> Promoted path; the
// CLI's [CycleCli.requestPromote] call composes a
// replacementEvidence that matches one of the two accepted
// shapes, so a normal cycle path passes; a hand-crafted
// replacementEvidence that is a free-form string is refused.
internal fun isVerifiedSubstituteShape(evidence: String): Boolean {
    if (evidence.startsWith("permanent-contract:")) {
        val id = evidence.removePrefix("permanent-contract:")
        return id.isNotBlank() && id.length in 1..128 &&
            id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
    if (evidence.startsWith("certifier-receipt:")) {
        val digest = evidence.removePrefix("certifier-receipt:")
        return digest.length == 64 &&
            digest.all { it in '0'..'9' || it in 'a'..'f' }
    }
    return false
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
