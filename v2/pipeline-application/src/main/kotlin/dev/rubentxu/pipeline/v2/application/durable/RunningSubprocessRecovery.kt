package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
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
 * What the observer found. A closed pair, because there are exactly two things that can be true of
 * an inspection: it yielded a conclusive observation, or it could not happen.
 *
 * There is deliberately no third case meaning "recovery was not required". That judgement belongs
 * to [DurableInvocationResolver] before this type is ever reached.
 */
internal sealed interface RunningSubprocessObservation {

    /** The substrate was inspected and yielded a conclusive outcome. */
    data class Recovered(
        val outcome: StepOutcome,
        val status: OperationStatus,
    ) : RunningSubprocessObservation

    /**
     * The substrate could not be inspected, so no conclusion is possible.
     *
     * `Unavailable` is never converted into [OperationStatus.LOST]. `LOST` says "I looked and there
     * is nothing recoverable"; `Unavailable` says "I was required to look and could not". Turning
     * the second into the first would terminalise a row that a later, correctly configured run
     * could still reconcile — destroying the only evidence that the operation is still in flight.
     */
    data class Unavailable(val cause: UnobservableCause) : RunningSubprocessObservation
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
     * It was `DurableShellExecutor().pollResult(...)` constructed INLINE, which made the
     * reattach-expiry branch (`poll` returns null) unreachable to any test that did not want to
     * burn [REATTACH_TIMEOUT_MS] of wall clock per row. That branch is precisely the fact
     * `ReattachWindowExpired` exists to name, so it is exactly the branch that must be
     * observable.
     *
     * The default is the REAL executor, so production behaviour and timing are unchanged. This
     * grants the observer no new power — owning a live process and its clock is already its job
     * under ADR-S4-R1 — it only removes a construction that was hidden inside a method body.
     */
    private val pollResult: (Path, Long) -> Int? = { controlDir, timeoutMs ->
        DurableShellExecutor().pollResult(controlDir, timeoutMs)
    },
) : RunningSubprocessRecovery {

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
            is StepReconcilerL1.Classification.Complete -> completedShellOutcome(classification.exitCode)
            is StepReconcilerL1.Classification.Reattach -> {
                val exitCode = pollResult(classification.controlDir, REATTACH_TIMEOUT_MS)
                if (exitCode == null) lostShellOutcome(operationId) else completedShellOutcome(exitCode)
            }
            is StepReconcilerL1.Classification.TimedOut -> RunningSubprocessObservation.Recovered(
                StepOutcome.Failure(
                    PipelineFailure(FailureKind.TIMEOUT, "Canonical shell '$operationId' timed out"),
                ),
                OperationStatus.FAILED_TIMEOUT,
            )
            // A real observation, not a gap: the control root WAS readable, and the operation
            // directory held nothing recoverable. LOST stays the honest terminal for this case and
            // must remain distinguishable from [RunningSubprocessObservation.Unavailable].
            StepReconcilerL1.Classification.Lost -> lostShellOutcome(operationId)
        }
    }

    private fun completedShellOutcome(exitCode: Int): RunningSubprocessObservation.Recovered =
        if (exitCode == 0) {
            RunningSubprocessObservation.Recovered(StepOutcome.Success, OperationStatus.SUCCEEDED)
        } else {
            RunningSubprocessObservation.Recovered(
                StepOutcome.Failure(
                    PipelineFailure(FailureKind.SCRIPT, "Canonical shell exited with code $exitCode"),
                ),
                OperationStatus.FAILED,
            )
        }

    private fun lostShellOutcome(operationId: String): RunningSubprocessObservation.Recovered =
        RunningSubprocessObservation.Recovered(
            StepOutcome.Failure(
                PipelineFailure(FailureKind.INFRASTRUCTURE, "Canonical shell '$operationId' could not be reconciled"),
            ),
            OperationStatus.LOST,
        )

    private companion object {
        // Preserved from CanonicalDurableRunCoordinator companion (behaviour-equivalence law).
        private const val REATTACH_TIMEOUT_MS = 60_000L
    }
}
