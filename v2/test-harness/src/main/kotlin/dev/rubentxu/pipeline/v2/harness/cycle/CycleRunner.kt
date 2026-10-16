package dev.rubentxu.pipeline.v2.harness.cycle

import java.nio.file.Path

/**
 * Runner for an ephemeral acceptance test cycle.
 *
 * Spec: `docs/v2/03-specifications/EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`.
 *
 * The runner is a thin orchestrator that:
 *   1. `prepare` — validates the manifest, reserves a cycle directory
 *      under `workspace/.pipelinek-cycles/<cycle-id>/` (outside the
 *      Git producer repo, never added back), refuses if the dir
 *      already exists (idempotency / cycle-id collision).
 *   2. `run` — executes the probes declared in the manifest with
 *      timeout and process isolation. The runner does NOT inspect
 *      probe output: it collects exit codes and evidence refs
 *      and emits `CycleEvent.ProbeFinished` for each. The decider
 *      is the only authority that classifies the cycle, and the
 *      decider now threads a [CycleContext] that records every
 *      accepted probe.
 *   3. `adjudicate` — combines the manifest, the probe outcomes,
 *      and the harness verdict (if any) into a single typed
 *      decision. This is `Cycle.decide`'s caller.
 *   4. `close` — if the decision is `Closed`, deletes the cycle
 *      directory and the temporary files declared in the
 *      manifest. If the decision is anything else, the cycle
 *      directory is preserved for inspection.
 *
 * The runner is intentionally minimal: it does not run Gradle,
 * does not fetch from the harness, and does not write to the
 * repo. The runner is an interpreter; the cycle spec is the
 * contract.
 *
 * **This is a typed skeleton**: the wire-up of the probe executor
 * and the harness-verdict fetcher is left to the operator. What
 * the runner guarantees is the *state-machine discipline*:
 * every transition goes through `Cycle.decide`, the close action
 * is gated on a `Closed` decision (which itself requires at least
 * one probe to have succeeded in the new audit-closed decider),
 * and the temporary directory is never deleted unless that gate
 * is satisfied.
 */
class CycleRunner(
    val cycleId: String,
    val manifestPath: Path,
    val cycleDir: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(cycleId.isNotBlank()) { "cycleId must not be blank" }
        require(manifestPath.toString().isNotBlank()) { "manifestPath must not be blank" }
        val asFile = cycleDir.toFile()
        require(!asFile.exists() || asFile.isDirectory) {
            "cycleDir must be a directory or not exist; got $cycleDir"
        }
    }

    /**
     * The current state of the cycle. The runner holds the
     * `CycleState` and only advances it via `Cycle.decide`.
     */
    var state: CycleState = CycleState.Draft
        private set

    /**
     * The accumulated context. The runner threads this through
     * every `Cycle.decide` call and exposes the latest value
     * so the operator can read the evidence trail.
     */
    var context: CycleContext = CycleContext()
        private set

    /**
     * `prepare`: validates the manifest exists and is readable,
     * and reserves the cycle directory. Returns the new state
     * (`Running` on success) and the outcome of the decision.
     */
    fun prepare(): CycleOutcome {
        val outcome = transition(CycleEvent.PrepareStarted)
        if (outcome is CycleOutcome.Next && outcome.nextState is CycleState.Running) {
            cycleDir.toFile().mkdirs()
        }
        return outcome
    }

    /**
     * `run`: feed a single probe's outcome into the state machine.
     * The probe executor is responsible for invoking the probe,
     * collecting its exit code and evidence, and calling this
     * method.
     *
     * The decider refuses:
     *   - `exitCode != 0` (refused to `Refused` so the cycle cannot
     *     observe on a failed probe);
     *   - blank `evidenceRef` (refused at the data-class init).
     */
    fun reportProbe(exitCode: Int, evidenceRef: String): CycleOutcome =
        transition(CycleEvent.ProbeFinished(exitCode, evidenceRef))

    /**
     * `adjudicate`: declare the cycle has observed all its
     * probes and is ready for promotion. The decider refuses
     * if no probe has succeeded in the accumulator.
     */
    fun observeAll(observedCommit: String): CycleOutcome =
        transition(CycleEvent.AllProbesObserved(observedCommit))

    /**
     * `adjudicate`: request promotion. The decider decides
     * whether the cycle is `Promoted` or stays in `Observed`
     * (the latter is a typed refusal of the promotion request
     * — the state machine does not move).
     *
     * The decider refuses a blank `replacementEvidence` so a
     * cycle cannot promote without naming a substitute.
     */
    fun requestPromote(replacementEvidence: String): CycleOutcome =
        transition(CycleEvent.PromoteRequested(replacementEvidence))

    /**
     * `close`: ask for the cycle to terminate.
     *
     * The decision is:
     *   - `Closed(reason)` if the cycle is in `Promoted` or
     *     `Observed` state with at least one probe in the
     *     accumulator (success path);
     *   - `Refused(reason)` if the cycle is in `Draft` or
     *     `Running` (closed without evidence; the manifest was
     *     invalid or the probes never finished);
     *   - `IllegalTransition` if the cycle is already `Closed`,
     *     `Refused`, or `BlockedExternal`.
     *
     * The runner emits a `Closed` or `Refused` state and the
     * caller decides whether to delete the cycle directory.
     * The runner NEVER deletes files unless the decision is
     * `Closed` AND the caller invokes [finalise] explicitly.
     */
    fun requestClose(closeReason: String): CycleOutcome =
        transition(CycleEvent.CloseRequested(closeReason))

    /**
     * Mark a cycle as blocked on external authority (typically
     * a missing harness verdict).
     */
    fun blockExternal(blocker: String): CycleOutcome =
        transition(CycleEvent.ExternalBlockerRaised(blocker))

    /**
     * `close` action: if the current state is `Closed`, the
     * caller MAY invoke this to delete the cycle directory and
     * the temporary files declared in the manifest. The runner
     * does NOT touch the filesystem itself; the caller passes
     * the actual deletion callback. This keeps the runner
     * side-effect free outside the cycle directory.
     *
     * The method returns the closed state when the deletion
     * was authorised, and throws an [IllegalStateException]
     * when the cycle is not in `Closed` state. It is
     * deliberately not possible to bypass the gate: a
     * `Refused` cycle keeps its files for inspection; a
     * `BlockedExternal` cycle does the same.
     */
    fun finalise(deleteDirectory: () -> Unit): CycleState {
        if (state !is CycleState.Closed) {
            error("finalise requires Closed state; got $state")
        }
        deleteDirectory()
        return state
    }

    private fun transition(event: CycleEvent): CycleOutcome {
        val outcome = Cycle.decide(state, event, context)
        if (outcome is CycleOutcome.Next) {
            state = outcome.nextState
            context = outcome.nextContext
        }
        return outcome
    }
}
