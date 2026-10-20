package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import java.nio.file.Files
import java.nio.file.Path

/**
 * TRAIN H3 / PR-019, corrected by ADR-0103 R1-E — the observer behind the "a2" external-subprocess
 * compatibility hook.
 *
 * [DurableInvocationResolver] used to reach for [StepReconcilerL1] and [DurableShellExecutor]
 * itself. That put process-reconciliation knowledge — control directories, reattach polling,
 * the 60 s reattach timeout — inside the class that owns the durable replay DECISION, which is
 * otherwise pure and tested at HF0 with no filesystem. The port below is the seam that fixed that.
 *
 * ## What the S4-R-REC spike measured, and what changed because of it
 *
 * The first shape of this port took the declared policy and the journaled row, and returned a
 * single `NotRunningShell` sentinel for all of:
 *
 * ```text
 * (1) recovery does NOT apply          — declared policy is not ExternalSubprocess
 * (2) the row is not RUNNING           — there is nothing to reattach
 * (3) recovery DOES apply and the
 *     substrate CANNOT be observed     — no control root is configured
 * ```
 *
 * (1) and (2) legitimately fall through to the replay policy. **(3) does not.** It is not "there
 * is nothing to recover", it is *"I am required to recover and I cannot see the substrate"*, and
 * folding it into the same value made it unrepresentable. The spike measured the consequence end
 * to end: for a Step declaring `RERUN`, case (3) reached `Execute`, re-ran a subprocess whose
 * prior external effect was unknown, and then terminalised the row as `SUCCEEDED` — so the unknown
 * effect became indistinguishable from a fresh success.
 *
 * ## The correction
 *
 * The WHEN now lives entirely in the decision core, which is the only component that can see the
 * declared policy and the journaled status. This port is consulted ONLY when recovery is already
 * required, and it answers one question: **what did you find?**
 *
 * ```text
 * [DurableInvocationResolver]  decides WHEN recovery is required
 *        └─ [RunningSubprocessRecovery]  observes WHAT the substrate holds
 * ```
 *
 * [RunningSubprocessObservation] therefore has no case meaning "not applicable" — that fact is not
 * this port's to express, and expressing it is precisely what let the collapse happen. It has no
 * policy parameter and no journaled row for the same reason: an observer that can check a policy
 * is an observer that can be asked to skip recovery, and a caller must not be able to declare its
 * own way out of it.
 *
 * @see RunningSubprocessObservation for why the vocabulary lives next to the port rather than
 *   beside the decision: conflating "what I decided" with "what I saw" is the same category error
 *   that let the three facts share one sentinel.
 */
internal fun interface RunningSubprocessRecovery {

    /**
     * Observes the real external process for [operationId].
     *
     * The caller has ALREADY decided that recovery is required. This method never re-checks that
     * and never returns a "nothing to do" sentinel, because a sentinel would again merge a policy
     * decision with an observation.
     *
     * Returns [RunningSubprocessObservation.Unavailable] when the substrate cannot be inspected
     * at all — which is a fact about observability, and is emphatically **not** the same as
     * [StepReconcilerL1.Classification.Lost], which is an observation of a substrate that was
     * successfully inspected and held nothing recoverable.
     */
    fun observe(operationId: String): RunningSubprocessObservation
}

/**
 * What the observer found. Three cases, because an inspection can conclude, fail to happen, or be
 * cut short by OUR window — and those are three different claims.
 *
 * There is deliberately no case meaning "recovery was not required". That judgement belongs to
 * [DurableInvocationResolver] before this type is ever reached.
 *
 * ADR-S4-R1 §2: none of these cases names a `StepOutcome` and none names an `OperationStatus`. The
 * observer reports facts; a pipeline outcome and a durable status are both projections of those
 * facts, and a fact layer that carries them is a fact layer that has already decided something.
 * Both are written as code spans rather than KDoc links on purpose — this layer not naming those
 * types is the property, so a resolvable link would work against it.
 *
 * ## S4-F1-C1 — the terminal case carries a FACT, not a semantic terminal
 *
 * This used to carry `RecoveredTerminal` (retired with the type itself), whose `Failed(failure)`
 * case the observer populated with
 * `FailureKind.SCRIPT` the moment an exit code was non-zero. That was the second manifestation of
 * ADR-S4-R1's `implementation conformance: PARTIAL`, and F1-C0 measured it: for
 * `sh(returnStatus = true)` with exit 42, the spine journalled FAILED while the contract says
 * `Status(42) · Success`. The exit code was OBSERVED — it is in `result.txt`, written by the process
 * itself. Failing closed there was not fail-closed; it was losing `returnMode` before the authority
 * that can read it.
 *
 * The payload is now [DurableTaskTerminal], which is the substrate's own vocabulary and which the
 * Step-owned projection already consumes through [classifyShellTerminal]. No parallel ADT is
 * introduced: the four cases this observer can produce map one-to-one onto the four that type
 * already has, and `classifyShellTerminal` was already exhaustive over it.
 *
 * What the observer may therefore report, and nothing more:
 *
 * ```text
 * exitCode              yes   ← result.txt
 * capturedStdout        yes   ← output.txt, and null when that file is ABSENT
 * timeout               yes   ← timeout.flag was written before the kill, so it is KNOWN
 * lost                  yes   ← the root was readable and held nothing recoverable
 * unavailable           yes   ← there was no root to read
 * window expired        yes   ← a fact about OUR window, and terminal-free by construction
 * ```
 *
 * What it may not report, at any return mode:
 *
 * ```text
 * StepOutcome · OperationStatus · FailureKind.SCRIPT · Success/Failure of any Step
 * ```
 *
 * The discriminator that decides which of those is right is the Step's own `returnMode`, recovered
 * from the durable input by the Step's own codec. It is not the observer's to know.
 */
internal sealed interface RunningSubprocessObservation {

    /**
     * The substrate was inspected and yielded a conclusive durable terminal.
     *
     * [DurableTaskTerminal], not a `(StepOutcome, OperationStatus)` pair and not a semantic
     * terminal: both the durable status and the pipeline outcome are projections, and the observer
     * gets to choose neither.
     */
    data class Observed(val terminal: DurableTaskTerminal) : RunningSubprocessObservation

    /**
     * The substrate could not be inspected, so no conclusion is possible.
     *
     * `Unavailable` is never converted into `LOST`. `LOST` says "I looked and there is nothing
     * recoverable"; `Unavailable` says "I was required to look and could not". Turning the second
     * into the first would terminalise a row that a later, correctly configured run could still
     * reconcile — destroying the only evidence that the operation is still in flight.
     */
    data class Unavailable(val cause: UnobservableCause) : RunningSubprocessObservation

    /**
     * The substrate said the process was still reattachable, and no terminal appeared before our
     * observation window closed.
     *
     * ADR-S4-R1 §2.3. This is a statement about OUR WINDOW, not about the substrate: we are not
     * claiming the process is gone, only that we stopped looking. Reporting it as `LOST` was the
     * collapse the S4-R1 §3b characterisation measured — a live process reported as lost — and it
     * happened because the expiry branch shared the genuine no-evidence terminal with the real one.
     *
     * The observer may not invent a terminal it does not have, which is why this carries none.
     * Whether an expired window is terminal at all is a RECONCILIATION decision, and it belongs to
     * [DurableInvocationResolver], not here.
     */
    data object ReattachWindowExpired : RunningSubprocessObservation
}

/**
 * Why the substrate could not be inspected.
 *
 * A closed ADT rather than a free-text reason, so that the exhaustive `when` in
 * [RecoveryInterpretationEngine] fails to compile the day a second cause appears and the failure
 * message the operator reads is forced to be revisited. The case exists so the loss of the reason
 * is visible in the type rather than in a string nobody can switch on.
 */
internal sealed interface UnobservableCause {

    /**
     * The runtime was assembled with no control root, so there is no directory in which a control
     * record for any operation could exist. This is a configuration gap, not an observation, and
     * it is recoverable by the operator — which is why it must not burn the journal row.
     */
    data object NoControlRootConfigured : UnobservableCause
}

/**
 * The real implementation: reconciles a RUNNING external subprocess from its control directory.
 *
 * This class is the compatibility seam PR-019 refers to. It is deliberately the ONLY place in the
 * recovery path that knows a process exists, and it is constructible from the composition root
 * with an explicit control root, so "recover a running shell" is a decision made when the runtime
 * is built rather than a branch buried in the resolver.
 */
internal class ExternalSubprocessRecovery(
    private val clock: Clock,
    private val controlDirRoot: Path?,
    /**
     * S4-R1 §3b — the reattach wait, as an explicit dependency.
     *
     * It used to be `DurableShellExecutor().pollResult(...)` constructed INLINE, which made the
     * reattach-expiry branch (`poll` returns null) unreachable to any test that did not want to
     * burn [REATTACH_TIMEOUT_MS] of wall clock per row. That branch is precisely the fact
     * `ReattachWindowExpired` exists to name, so it is exactly the branch that must be
     * observable.
     *
     * `null` — the production default, and what every composition root passes today — resolves
     * below to the REAL executor, so production behaviour and timing are unchanged.
     *
     * The caller passes `null` rather than an executor because a composition root that had to
     * build a `DurableShellExecutor` to configure the observer would be a second place that knows
     * a process exists. The observer keeps that knowledge: it is the component that owns the
     * process and its clock under ADR-S4-R1 §1, and it is also where the fallback lives.
     */
    pollResult: ((Path, Long) -> Int?)? = null,
) : RunningSubprocessRecovery {

    /**
     * The real wait, unless a caller substituted one. Owning the fallback HERE is what lets the
     * composition root forward a nullable dependency without branching on it.
     */
    private val reattachPoll: (Path, Long) -> Int? =
        pollResult ?: { controlDir, timeoutMs ->
            DurableShellExecutor().pollResult(controlDir, timeoutMs)
        }

    /**
     * No policy check and no status check. Both belong to the decision core, and their absence
     * here is the fix: an observer that can decline because of what it was told is an observer
     * whose decline is indistinguishable from the caller's own reasoning going wrong.
     */
    override fun observe(operationId: String): RunningSubprocessObservation {
        val root = controlDirRoot
            ?: return RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured)

        val reconciler = StepReconcilerL1(clock, root)
        val classification = reconciler.classify(operationId)
        return when (classification) {
            is StepReconcilerL1.Classification.Complete ->
                observedTerminal(root.resolve(operationId), classification.exitCode)
            is StepReconcilerL1.Classification.Reattach -> {
                val exitCode = reattachPoll(classification.controlDir, REATTACH_TIMEOUT_MS)
                // The window closed with nothing. That is a fact about the WINDOW, and it is
                // reported as such: the substrate said `Reattach`, which means the process may
                // still be alive, and claiming otherwise from here would be the observer deciding
                // a reconciliation question it was not asked.
                if (exitCode == null) {
                    RunningSubprocessObservation.ReattachWindowExpired
                } else {
                    observedTerminal(classification.controlDir, exitCode)
                }
            }
            is StepReconcilerL1.Classification.TimedOut -> RunningSubprocessObservation.Observed(
                DurableTaskTerminal.Cancelled(
                    InterruptionRecord(
                        kind = InterruptionKind.TIMEOUT,
                        message = "Canonical shell '$operationId' timed out",
                        operationId = operationId,
                    ),
                ),
            )
            // A real observation, not a gap: the control root WAS readable, and the operation
            // directory held nothing recoverable. LOST is the honest terminal for this case and
            // must remain distinct from [RunningSubprocessObservation.Unavailable] — and distinct
            // from [RunningSubprocessObservation.ReattachWindowExpired], which is the case where we
            // never reached this check.
            StepReconcilerL1.Classification.Lost -> RunningSubprocessObservation.Observed(
                DurableTaskTerminal.Lost(
                    FailureRecord(
                        code = "DURABLE_TASK_LOST",
                        kind = FailureKind.INFRASTRUCTURE,
                        message = "Canonical shell '$operationId' could not be reconciled",
                        origin = FailureOrigin.RECONCILIATION,
                        retryable = false,
                        operationId = operationId,
                    ),
                ),
            )
        }
    }

    /**
     * S4-F1-C1 — the facts a terminated process left behind, and the whole of what this observer
     * knows about its VALUE.
     *
     * Two reads, no interpretation:
     *
     * ```text
     * result.txt   → exitCode      the number the process itself wrote
     * output.txt   → capturedStdout   ONLY when that file exists
     * ```
     *
     * The `output.txt` read is the subtle one, and `null` is load-bearing. `DurableTaskOutput`
     * carries `capturedStdout: String?` precisely so this can be said without inventing a value:
     *
     * ```text
     * file present, holds "abc"   →  "abc"    the program printed this
     * file present, holds ""      →  ""       the program printed NOTHING — a fact
     * file ABSENT                 →  null     nobody recorded anything — an ABSENCE
     * ```
     *
     * The console log is deliberately NOT read as a substitute. `consoleTranscript` and
     * `capturedStdout` are different channels by construction (see [DurableTaskOutput]), and
     * substituting one for the other would make a `returnStdout` value out of text the contract
     * never promised as the value. So this observer leaves it `null`: it has not observed it, and
     * saying otherwise is the fabrication this whole slice exists to prevent.
     */
    private fun observedTerminal(controlDir: Path, exitCode: Int): RunningSubprocessObservation {
        val captured = if (Files.exists(OUTPUT_FILE(controlDir))) {
            Files.readString(OUTPUT_FILE(controlDir))
        } else {
            null
        }
        return RunningSubprocessObservation.Observed(
            DurableTaskTerminal.Exited(
                exitCode = exitCode,
                output = DurableTaskOutput(
                    controlDir = controlDir.toString(),
                    capturedStdout = captured,
                ),
            ),
        )
    }

    private companion object {
        // Preserved from CanonicalDurableRunCoordinator companion (behaviour-equivalence law).
        private const val REATTACH_TIMEOUT_MS = 60_000L

        /** The captured-stdout channel. Read only to learn whether it EXISTS, and what it holds. */
        private fun OUTPUT_FILE(controlDir: Path): Path = controlDir.resolve("output.txt")
    }
}
