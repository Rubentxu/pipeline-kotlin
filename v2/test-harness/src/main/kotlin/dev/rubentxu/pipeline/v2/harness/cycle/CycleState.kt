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

    /**
     * A probe or the runner rejected; the cycle is closed. The interface
     * is sealed so the [ExitVerdict] projection and the [Cycle] decider
     * see the same closed set of cases; adding a new refusal shape
     * (e.g. a new `ProbeOutcome` variant) fails the build at both
     * sites at compile time, not at a runtime string match.
     *
     * BQ4 audit (2026-10-16, RC3 close-out, vertical): the previous
     * shape was a `data class Refused(val reason: String)` and the
     * decider emitted well-known reason strings (`"probe ... Failed"`,
     * `"other commit: ..."`, `"UnsupportedFidelity: ..."`, ...). The
     * runner's [ExitVerdict.classifyRefusal] then re-parsed those
     * strings with `startsWith` to recover the typed case — a
     * dual-authority defect (emitter + re-parser) the AGENTS.md §3
     * stringly-typed rule forbids. The new shape makes each case a
     * distinct data class with typed fields; the carrier [reason] is
     * preserved verbatim so the operator-facing receipt text survives
     * the refactor.
     */
    sealed interface Refused : CycleState {
        /**
         * Operator-facing text for the receipt and the CI log. Preserved
         * verbatim across subtypes: each subtype computes [reason] from
         * its typed payload, so the string is the typed view, not a
         * parallel string that could drift.
         */
        val reason: String

        /** A single probe reported [ProbeOutcome.Failed]. */
        data class ProbeFailed(
            val evidenceRef: String,
            val failureReason: String,
        ) : Refused {
            init {
                require(evidenceRef.isNotBlank()) { "ProbeFailed.evidenceRef must not be blank" }
                require(failureReason.isNotBlank()) { "ProbeFailed.failureReason must not be blank" }
            }
            override val reason: String get() = "probe $evidenceRef Failed: $failureReason"
        }

        /** A single probe reported [ProbeOutcome.Skipped]. */
        data class ProbeSkipped(val evidenceRef: String) : Refused {
            init { require(evidenceRef.isNotBlank()) { "ProbeSkipped.evidenceRef must not be blank" } }
            override val reason: String get() =
                "probe $evidenceRef Skipped; the spec rejects a Skipped probe from " +
                    "advancing to Observed (the AAT or E2E has no mutation target)"
        }

        /** A single probe reported [ProbeOutcome.NotRun]. */
        data class ProbeNotRun(val evidenceRef: String, val notRunReason: String) : Refused {
            init {
                require(evidenceRef.isNotBlank()) { "ProbeNotRun.evidenceRef must not be blank" }
                require(notRunReason.isNotBlank()) { "ProbeNotRun.notRunReason must not be blank" }
            }
            override val reason: String get() = "probe $evidenceRef NotRun: $notRunReason"
        }

        /** A single probe reported [ProbeOutcome.NoEvidence]. */
        data class ProbeNoEvidence(val evidenceRef: String) : Refused {
            init { require(evidenceRef.isNotBlank()) { "ProbeNoEvidence.evidenceRef must not be blank" } }
            override val reason: String get() =
                "probe $evidenceRef NoEvidence; the cycle cannot observe a probe " +
                    "that produced no observable evidence"
        }

        /** A single probe reported [ProbeOutcome.OtherCommit] — runner and probe disagree on the source. */
        data class ProbeOtherCommit(val evidenceRef: String, val observed: String) : Refused {
            init {
                require(evidenceRef.isNotBlank()) { "ProbeOtherCommit.evidenceRef must not be blank" }
                require(observed.isNotBlank()) { "ProbeOtherCommit.observed must not be blank" }
            }
            override val reason: String get() =
                "probe $evidenceRef OtherCommit=$observed; the manifest's source_sha is the only " +
                    "authoritative source identity"
        }

        /**
         * A single probe reported [ProbeOutcome.UnsupportedFidelity] — the manifest
         * requested a fidelity the executor cannot honour. Distinct exit code (64)
         * downstream so a CI matrix can route this refusal separately from a
         * generic `1`.
         */
        data class ProbeUnsupportedFidelity(val evidenceRef: String, val fidelity: String) : Refused {
            init {
                require(evidenceRef.isNotBlank()) { "ProbeUnsupportedFidelity.evidenceRef must not be blank" }
                require(fidelity.isNotBlank()) { "ProbeUnsupportedFidelity.fidelity must not be blank" }
            }
            override val reason: String get() =
                "probe $evidenceRef UnsupportedFidelity=$fidelity; this vertical only " +
                    "supports the fidelities declared in CycleManifest.ALLOWED_FIDELITIES"
        }

        /**
         * The decider observed zero Verified probes. A cycle that ran
         * but never saw a probe succeed: the spec's "no evidence"
         * refusal. Distinct from [Generic] because the [ExitVerdict]
         * projection routes it to [ExitVerdict.Refusal.NoEvidence]
         * (exit code 1, typed carrier) and the operator diagnostic
         * names the missing observation count.
         */
        data class NoProbesSucceeded(val observedCount: Int) : Refused {
            init { require(observedCount == 0) { "NoProbesSucceeded.observedCount must be 0" } }
            override val reason: String get() =
                "AllProbesObserved but no probe succeeded (observed=$observedCount); " +
                    "the cycle cannot advance to Observed without at least one Verified probe"
        }

        /**
         * Generic refusal: a close reason from [CycleEvent.CloseRequested] or a
         * prep failure from [CycleRunner.fail]. The [reason] is preserved
         * verbatim for the receipt; the [ExitVerdict] projection routes it
         * to [ExitVerdict.Refusal.Generic] (exit 1). The previous shape's
         * "other commit:" / "UnsupportedFidelity:" / "no probe succeeded"
         * string-prefix routing is now impossible: those cases have their
         * own typed carriers above.
         */
        data class Generic(override val reason: String) : Refused {
            init { require(reason.isNotBlank()) { "Generic.reason must not be blank" } }
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
    // BQ4 v2 (typed accumulator, 2026-10-17): the
    // previous init check on the list is gone because
    // [ProbeFinished] now wraps [ProbeOutcome.Verified]
    // directly, which the type system enforces. The list
    // can only contain Verified probes; the runtime
    // check that every entry is `is ProbeOutcome.Verified`
    // is therefore uncompilable (it cannot be false),
    // and the `it.outcome.outcome.name` fallback the
    // previous message used is also unreachable. The
    // decider is still the only authority that decides
    // a probe is verified; the data class is just the
    // carrier of an already-decided outcome.
    fun withProbe(probe: ProbeFinished): CycleContext =
        copy(probeOutcomes = probeOutcomes + probe)

    fun withReplacementEvidence(evidence: PromotionEvidence): CycleContext =
        copy(latestReplacementEvidence = evidence)
}

/**
 * The reified shape of an accepted probe. Carries a [ProbeOutcome.Verified]
 * directly, NOT a `ProbeOutcome` plus a runtime `is` check. The type
 * system makes the verified-only invariant unrepresentable: a
 * `ProbeFinished` cannot be constructed with `Failed`, `Skipped`,
 * `NotRun`, `NoEvidence`, `OtherCommit`, `UnsupportedFidelity`, or
 * `BlockedExternal`, so the previous init check on the type itself
 * becomes uncompilable (the failure branch is no longer reachable).
 *
 * The previous shape collapsed `UnsupportedFidelity` and `BlockedExternal`
 * to `exitCode=0` and let the decider read them as success: that is the
 * P0 defect the 2026-10-16 external audit named. The ADT is the source
 * of truth here; the decider is the only place that decides whether a
 * probe is "verified", and a `ProbeFinished` is the carrier of an
 * already-decided "verified" outcome.
 */
data class ProbeFinished(val outcome: ProbeOutcome.Verified) {
    init {
        // The Verified-only invariant is enforced by the
        // type. The blank-evidenceRef invariant is still
        // a runtime check, because a `Verified("")` is
        // a Verified by type and the type cannot reject
        // it; the data class is where the cycle rejects
        // a Verified-without-evidence.
        require(outcome.evidenceRef.isNotBlank()) {
            "ProbeFinished.outcome.evidenceRef must not be blank; Verified without " +
                "a real evidence path is a fabrication"
        }
    }
}

/**
 * Events that drive a [CycleState] forward. The runner produces them; the
 * decider consumes them. There is no `Cancel` event here: a cancel is a
 * specific [CycleState.Refused.Generic] that the caller can emit, not a state of
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
    data class AllProbesObserved(val observedCommit: String) : CycleEvent {
        // BQ4 v17 (cycle event carrier self-protect,
        // 2026-10-17): a blank `observedCommit` is the
        // decider's record of "all probes observed
        // against commit X" for a commit that has no
        // SHA — a fabrication. The event refuses
        // blanks at construction. The cycle runner
        // always supplies a non-blank SHA via the
        // CLI's --source-sha (strict-validated in
        // CycleCli); a test or future consumer that
        // bypasses the runner would now fail fast.
        init { require(observedCommit.isNotBlank()) { "AllProbesObserved.observedCommit must not be blank" } }
    }
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
    data class CloseRequested(val closeReason: String) : CycleEvent {
        // BQ4 v17 (cycle event carrier self-protect,
        // 2026-10-17): a blank `closeReason` is the
        // decider's record of "cycle closed because X"
        // for a reason that has no content — a
        // fabrication. The event refuses blanks at
        // construction; the cycle runner always
        // supplies a non-blank reason via
        // [CycleRunner.requestClose] and the CLI's
        // "cycle closed by CycleCli" message.
        init { require(closeReason.isNotBlank()) { "CloseRequested.closeReason must not be blank" } }
    }
    data class ExternalBlockerRaised(val blocker: String) : CycleEvent {
        // BQ4 v17 (cycle event carrier self-protect,
        // 2026-10-17): a blank `blocker` is the
        // decider's record of "cycle blocked on
        // external authority X" for a blocker that
        // has no name — a fabrication. The event
        // refuses blanks at construction.
        init { require(blocker.isNotBlank()) { "ExternalBlockerRaised.blocker must not be blank" } }
    }
}
