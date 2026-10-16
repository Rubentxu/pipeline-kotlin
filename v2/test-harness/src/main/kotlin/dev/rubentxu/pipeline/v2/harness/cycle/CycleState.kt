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
 * The state machine is the *test* cycle, not the pipeline replay cycle.
 * The two are deliberately separate: the pipeline replay is owned by
 * `:pipeline-domain:durable` and gated by ADR-0103. The test cycle
 * exists to drive `retirement_guard.py` and the close-out receipt.
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
    data class Observed(val observedCommit: String) : CycleState

    /** All evidence is valid; the manifest is ready to close. */
    data class Promoted(val promotedAtCommit: String) : CycleState

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
 * Events that drive a [CycleState] forward. The runner produces them; the
 * decider consumes them. There is no `Cancel` event here: a cancel is a
 * specific `Refused(reason)` that the caller can emit, not a state of
 * its own.
 */
sealed interface CycleEvent {
    data object PrepareStarted : CycleEvent
    data class ProbeFinished(val exitCode: Int, val evidenceRef: String) : CycleEvent
    data class AllProbesObserved(val observedCommit: String) : CycleEvent
    data class PromoteRequested(val replacementEvidence: String) : CycleEvent
    data class CloseRequested(val closeReason: String) : CycleEvent
    data class ExternalBlockerRaised(val blocker: String) : CycleEvent
}
