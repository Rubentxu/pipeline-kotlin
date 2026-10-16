package dev.rubentxu.pipeline.v2.harness.cycle

/**
 * Lifecycle of an ephemeral acceptance test cycle.
 *
 * Spec: docs/v2/03-specifications/EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md.
 *
 * The state machine is closed: a [CycleState] is one of seven cases, and
 * the transition function [Cycle.decide] is total over (state, event).
 * The decider is the only authority that moves a cycle forward or
 * sideways; adapters and runners observe states, they do not invent
 * them.
 *
 * BQ4 audit (2026-10-16, RC3 close-out): the previous decider accepted
 * transitions on the basis of state + event alone, with no access to
 * the cycle's accumulated evidence. That allowed a sequence
 * `Draft → Running → Observed(observedCommit) → Closed(closeReason)`
 * with **zero** probes run, which contradicted the spec's
 * "toda observación Skipped, NotRun, NoEvidence o OtherCommit
 * niega Verified". The fix here introduces [CycleContext], a
 * running tally of probes observed and the most recent replacement
 * evidence, and makes [Cycle.decide] refuse any transition that
 * would otherwise let a cycle close without at least one probe
 * having produced a non-blank evidence ref, a zero exit code, and
 * a non-blank replacement evidence on the promote path.
 *
 * This module is self-contained: it does NOT import from
 * `dev.rubentxu.pipeline.v2.harness.catalog` or `…harness.plan` because
 * those packages are owned by the canonical H0 test-harness.
 */
sealed interface CycleState {
    /** A manifest exists; no probes have been run yet. */
    data object Draft : CycleState

    /** At least one probe started; no evidence collected yet. */
    data object Running : CycleState

    /** At least one probe finished; evidence recorded; verdict pending. */
    data class Observed(val observedCommit: String) : CycleState {
        init { require(observedCommit.isNotBlank()) { "Observed.observedCommit must not be blank" } }
    }

    /** All evidence is valid; the manifest is ready to close. */
    data class Promoted(val promotedAtCommit: String) : CycleState {
        init { require(promotedAtCommit.isNotBlank()) { "Promoted.promotedAtCommit must not be blank" } }
    }

    /**
     * Terminal state for a successful cycle. A `Closed` cycle is
     * reached ONLY via the success path
     * `Draft -> Running -> Observed -> Promoted -> Closed` per
     * `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`. The close reason is
     * preserved for the receipt (cycleId, sourceSHA, evidence,
     * closeReason). A `Closed` cycle is NOT a `Refused` cycle:
     * refusing means evidence was contradictory; closing means
     * evidence was sufficient and the temporary files were
     * released. The BQ4 audit (2026-10-16, vertical) closed the
     * previous `Observed -> Closed` shortcut: a cycle that
     * observed with probes but did not request promotion is
     * `IllegalTransition`, not `Closed`.
     */
    data class Closed(val closeReason: String) : CycleState {
        init {
            require(closeReason.isNotBlank()) { "Closed.closeReason must not be blank" }
        }
    }

    /** A probe or the runner rejected; the cycle is closed. */
    data class Refused(val reason: String) : CycleState {
        init {
            require(reason.isNotBlank()) { "Refused.reason must not be blank" }
        }
    }

    /** The cycle cannot finish without external authority (harness verdict, etc.). */
    data class BlockedExternal(val blocker: String) : CycleState {
        init {
            require(blocker.isNotBlank()) { "BlockedExternal.blocker must not be blank" }
        }
    }
}

/**
 * The accumulated context a [Cycle.decide] consults.
 *
 * The decider is pure: the context is the only mutable thing a
 * caller threads through, and the decider returns a new context
 * alongside the new state on every [CycleOutcome.Next]. That keeps
 * the state machine total and lets the runner reconstruct the
 * accumulated state at any point by replaying the event log.
 *
 * `probeOutcomes` is the running list of [ProbeFinished] events
 * the decider has accepted in this cycle. The decider refuses
 * `Running → Observed` if the list is empty or if any entry has
 * `exitCode != 0` or a blank `evidenceRef`. The decider refuses
 * `Observed → Promoted` if `latestReplacementEvidence` is blank.
 */
data class CycleContext(
    val probeOutcomes: List<ProbeFinished> = emptyList(),
    val latestReplacementEvidence: PromotionEvidence? = null,
) {
    init {
        require(probeOutcomes.all { it.outcome is ProbeOutcome.Verified }) {
            "probeOutcomes MUST only contain Verified probes; the decider refuses " +
                "every other ProbeOutcome case (Failed, Skipped, NotRun, NoEvidence, " +
                "OtherCommit, UnsupportedFidelity, BlockedExternal). Found: " +
                probeOutcomes.filter { it.outcome !is ProbeOutcome.Verified }
                    .map { it.outcome.outcome.name }
        }
    }

    fun withProbe(probe: ProbeFinished): CycleContext =
        copy(probeOutcomes = probeOutcomes + probe)

    fun withReplacementEvidence(evidence: PromotionEvidence): CycleContext =
        copy(latestReplacementEvidence = evidence)
}

/**
 * The reified shape of an accepted probe. Carries the typed [ProbeOutcome]
 * from the executor, NOT a free-floating `(Int, String)` pair. The
 * previous shape collapsed `UnsupportedFidelity` and `BlockedExternal` to
 * `exitCode=0` and let the decider read them as success: that is the P0
 * defect the 2026-10-16 external audit named. The ADT is the source of
 * truth here; the decider is the only place that decides whether a
 * probe is "verified".
 */
data class ProbeFinished(val outcome: ProbeOutcome) {
    init {
        require(outcome is ProbeOutcome.Verified) {
            "ProbeFinished MUST only wrap ProbeOutcome.Verified; the runner cannot " +
                "promote a ${outcome::class.simpleName} into the accumulator. " +
                "Got: ${outcome.outcome.name}"
        }
        require(outcome.evidenceRef.isNotBlank()) {
            "ProbeFinished.outcome.evidenceRef must not be blank; Verified without " +
                "a real evidence path is a fabrication"
        }
    }
}

/**
 * Events that drive a [CycleState] forward. The runner produces them; the
 * decider consumes them. There is no `Cancel` event here: a cancel is a
 * specific `Refused(reason)` that the caller can emit, not a state of
 * its own.
 *
 * `ProbeFinished` carries the typed [ProbeOutcome] (not a free-floating
 * `exitCode` and `evidenceRef`). The decider is the only authority that
 * classifies a probe as accepted; collapsing the ADT to a boolean at the
 * runner boundary is the P0 defect the 2026-10-16 external audit named.
 */
sealed interface CycleEvent {
    data object PrepareStarted : CycleEvent
    /** Emit ONLY when the probe returned [ProbeOutcome.Verified]. Other cases refuse. */
    data class ProbeFinished(val outcome: ProbeOutcome) : CycleEvent
    data class AllProbesObserved(val observedCommit: String) : CycleEvent
    /**
     * The substitute guarantee the cycle retires with. The carrier
     * is a typed [PromotionEvidence] (a sealed interface with
     * `PermanentContract` and `CertifierReceipt` variants). The
     * runner has already verified the carrier via
     * [PromotionEvidenceVerifier] before emitting this event; the
     * decider's only job is to record the evidence and advance the
     * state. A blank carrier is impossible by construction
     * (the carrier's data class init refuses blanks).
     */
    data class PromoteRequested(val replacementEvidence: PromotionEvidence) : CycleEvent
    data class CloseRequested(val closeReason: String) : CycleEvent
    data class ExternalBlockerRaised(val blocker: String) : CycleEvent
}
