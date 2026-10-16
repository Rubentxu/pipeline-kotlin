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
     * Terminal state for a successful cycle. The cycle was either
     * promoted and then closed, or it observed nothing actionable
     * and closed cleanly. The close reason is preserved for the
     * receipt (cycleId, sourceSHA, evidence, closeReason) per
     * `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`. A `Closed` cycle is
     * NOT a `Refused` cycle: refusing means evidence was
     * contradictory; closing means evidence was sufficient
     * (or unnecessary) and the temporary files were released.
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
    val latestReplacementEvidence: String = "",
) {
    init {
        require(probeOutcomes.all { it.evidenceRef.isNotBlank() }) {
            "probeOutcomes MUST only contain non-blank evidence refs; " +
                "the decider should have refused the bad ProbeFinished event"
        }
    }

    fun withProbe(probe: ProbeFinished): CycleContext =
        copy(probeOutcomes = probeOutcomes + probe)

    fun withReplacementEvidence(evidence: String): CycleContext =
        copy(latestReplacementEvidence = evidence)
}

/**
 * The reified shape of an accepted probe. Carries the fields the
 * decider inspects (exit code + non-blank evidence ref) as a value,
 * not a free-floating pair.
 */
data class ProbeFinished(val exitCode: Int, val evidenceRef: String) {
    init { require(evidenceRef.isNotBlank()) { "evidenceRef must not be blank" } }

    /** A non-zero exit code is a failed probe. The decider treats it as Refused. */
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Events that drive a [CycleState] forward. The runner produces them; the
 * decider consumes them. There is no `Cancel` event here: a cancel is a
 * specific `Refused(reason)` that the caller can emit, not a state of
 * its own.
 */
sealed interface CycleEvent {
    data object PrepareStarted : CycleEvent
    /** Emit ONLY when a real probe has run. Blank evidence is refused by the decider. */
    data class ProbeFinished(val exitCode: Int, val evidenceRef: String) : CycleEvent
    data class AllProbesObserved(val observedCommit: String) : CycleEvent
    /** Blank replacement evidence is refused on the promote path. */
    data class PromoteRequested(val replacementEvidence: String) : CycleEvent
    data class CloseRequested(val closeReason: String) : CycleEvent
    data class ExternalBlockerRaised(val blocker: String) : CycleEvent
}
