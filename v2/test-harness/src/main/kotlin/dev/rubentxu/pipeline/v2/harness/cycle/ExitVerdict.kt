package dev.rubentxu.pipeline.v2.harness.cycle

/**
 * The single projection from a [CycleState] to a process exit code.
 *
 * BQ4 v2 (2026-10-17, typed refactor): the previous shape read the
 * terminal state by string-matching on the refusal reason
 * (`reason.startsWith(...)`) and inferred "Verified" from a raw
 * `exitCode == 0` of an isolated probe. Both shapes were wrong: a
 * probe exit code is the probe's authority on the probe, not on
 * the cycle; and a refusal reason is data, not control flow. A
 * future "NoEvidence" refusal or "OtherCommit" failure would
 * silently map to `1` and look the same as a generic
 * [CycleState.Refused].
 *
 * [ExitVerdict] makes the projection typed. Each variant carries its own
 * exit code so a caller cannot accidentally mix two refusal cases, and
 * the [from] function is the only entry point — it is total over
 * [CycleState]'s cases by construction (the `when` is exhaustive;
 * the compiler enforces it). [classifyRefusal] consumes a typed
 * [CycleState.Refused] (a sealed interface over the closed set of
 * refusal shapes), NOT a free String.
 *
 * The exit-code mapping follows the project convention:
 *  - `Verified` → 0
 *  - `Refusal.Generic` / `Refusal.NoEvidence` / `Refusal.OtherCommit` → 1
 *  - `Refusal.UnsupportedFidelity` → 64
 *  - `BlockedExternal` → 75
 *  - `Incomplete` (the cycle never reached a terminal state — a runner
 *    defect) → 1, with a typed tag that names the cause
 *
 * Callers (Gradle `JavaExec`, the CLI, a future test harness entry point)
 * read [exitCode] only at the process boundary. Internal code uses the
 * variant to decide what to log, what to write to the receipt, what to
 * retry, etc.
 *
 * The shape is closed; adding a new case requires updating [from] AND
 * the exit-code map AND at least one test. The audit's "two silent
 * authorities" failure mode is no longer reachable, and the
 * [CycleRefusalProjectionFitnessTest] pins the absence of
 * string-prefix classification in the cycle package.
 */
sealed interface ExitVerdict {

    /** The cycle closed legitimately: at least one Verified probe, a real commit, and a manifest. */
    data object Verified : ExitVerdict

    /**
     * The cycle was refused. The reason is preserved verbatim so an
     * operator-facing diagnostic can be assembled without a second
     * pass; the kind tag is for the dispatch (e.g. a CI policy may
     * retry on a NoEvidence but not on a generic Refused).
     */
    sealed interface Refusal : ExitVerdict {
        val reason: String
        override val exitCode: Int

        /** The cycle ran but no probe produced verifiable evidence. Not retriable blindly. */
        data class NoEvidence(override val reason: String) : Refusal {
            override val exitCode: Int = 1
        }

        /** The cycle is not authorised against the source commit the runner saw. */
        data class OtherCommit(override val reason: String) : Refusal {
            override val exitCode: Int = 1
        }

        /**
         * The cycle ran but the harness refused the requested fidelity.
         * The reason carries the HF label so the operator can decide
         * whether to fall back to a supported one. Distinct exit code
         * (64) so a CI matrix can route UnsupportedFidelity refusals
         * separately from a generic `1`.
         */
        data class UnsupportedFidelity(override val reason: String) : Refusal {
            override val exitCode: Int = 64
        }

        /** The cycle failed for any other reason named by [reason]. */
        data class Generic(override val reason: String) : Refusal {
            override val exitCode: Int = 1
        }
    }

    /**
     * The cycle cannot finish without an external authority (a missing
     * harness verdict, a credential handshake, etc.). Distinct exit code
     * (75) so a CI matrix can hold the run pending instead of marking
     * it red.
     */
    data class BlockedExternal(val reason: String) : ExitVerdict {
        override val exitCode: Int = 75
    }

    /**
     * The runner reached no terminal state. The decider refused to
     * close; an external cause (kill signal, JVM crash, harness bug)
     * is implied. Kept as a typed variant so callers do not have to
     * re-discover the missing case from logs.
     */
    data class Incomplete(val reason: String) : ExitVerdict {
        override val exitCode: Int = 1
    }

    /**
     * The integer that exits the JVM. Convenience accessor; equals
     * the per-variant field by construction.
     */
    val exitCode: Int
        get() = when (this) {
            Verified -> 0
            is Refusal -> this.exitCode
            is BlockedExternal -> this.exitCode
            is Incomplete -> this.exitCode
        }

    companion object {
        /**
         * Total projection from any [CycleState] to an [ExitVerdict].
         * The `when` is exhaustive; adding a `CycleState` case fails
         * the build at the projection site, not at a runtime
         * string-match in a CI log.
         */
        fun from(state: CycleState): ExitVerdict = when (state) {
            is CycleState.Draft -> Incomplete("cycle never started; state=Draft")
            is CycleState.Running -> Incomplete("cycle still running; state=Running")
            is CycleState.Observed -> Incomplete("cycle observed but did not advance; state=Observed")
            is CycleState.Promoted -> Incomplete("cycle promoted but did not close; state=Promoted")
            is CycleState.Closed -> Verified
            is CycleState.Refused -> classifyRefusal(state)
            is CycleState.BlockedExternal -> BlockedExternal(state.blocker)
        }

        /**
         * Typed disambiguation of a [CycleState.Refused] into the
         * right [Refusal] variant. The decider now emits a typed
         * case for every well-known refusal shape (probe-level
         * Failed/Skipped/NotRun/NoEvidence/OtherCommit/UnsupportedFidelity
         * and the cycle-level NoProbesSucceeded); the projection
         * here is a flat `when` over the closed set, not a string
         * prefix match. Adding a new [CycleState.Refused] case
         * forces this `when` to grow at compile time — the dual
         * emitter/parser authority the audit named is gone.
         *
         * The [Refusal.Generic] carrier preserves the
         * operator-facing [reason] text verbatim; the typed
         * case-to-typed-case mapping is the audit's answer to the
         * previous "stringly-typed reason" defect.
         */
        private fun classifyRefusal(refused: CycleState.Refused): Refusal = when (refused) {
            // NoProbesSucceeded routes to NoEvidence because the
            // cycle ran but observed zero verified probes — the
            // spec's "no evidence" condition.
            is CycleState.Refused.NoProbesSucceeded -> Refusal.NoEvidence(refused.reason)
            // ProbeNoEvidence is the per-probe no-evidence signal:
            // a single probe reported no observable evidence. The
            // cycle refused at the probe boundary, not at the
            // AllProbesObserved boundary, so the projection
            // distinguishes the two via the carrier type.
            is CycleState.Refused.ProbeNoEvidence -> Refusal.NoEvidence(refused.reason)
            // ProbeUnsupportedFidelity keeps the distinct exit code
            // (64) so a CI matrix can route UnsupportedFidelity
            // refusals separately from a generic `1`.
            is CycleState.Refused.ProbeUnsupportedFidelity -> Refusal.UnsupportedFidelity(refused.reason)
            // ProbeOtherCommit is the per-probe other-commit signal;
            // a single probe saw a different source identity than
            // the manifest declares. The runner inherits the
            // manifest's sourceSha, so this refusal shape is the
            // exact thing the PromotionEvidenceVerifier checks at
            // promotion time, but per-probe.
            is CycleState.Refused.ProbeOtherCommit -> Refusal.OtherCommit(refused.reason)
            // All other typed probe-level refusals (ProbeFailed,
            // ProbeSkipped, ProbeNotRun) collapse to Generic with
            // the typed reason preserved. They are operator-facing
            // diagnostic cases; the exit code is 1, the same as
            // any other runtime failure.
            is CycleState.Refused.ProbeFailed -> Refusal.Generic(refused.reason)
            is CycleState.Refused.ProbeSkipped -> Refusal.Generic(refused.reason)
            is CycleState.Refused.ProbeNotRun -> Refusal.Generic(refused.reason)
            // Generic captures close reasons from
            // [CycleEvent.CloseRequested] and prep failures from
            // [CycleRunner.fail]. The runner used to write
            // stringly-typed reasons here too (e.g. "other commit:"
            // from [PromotionEvidenceVerifier]); those now flow
            // through the runner's `fail` path and land in
            // [Refusal.Generic] with the original text preserved.
            is CycleState.Refused.Generic -> Refusal.Generic(refused.reason)
        }
    }
}
