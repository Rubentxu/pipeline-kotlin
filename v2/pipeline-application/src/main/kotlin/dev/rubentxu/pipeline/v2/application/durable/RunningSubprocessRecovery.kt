package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import java.nio.file.Path

/**
 * TRAIN H3 / PR-019 — the port behind the "a2" external-subprocess compatibility hook.
 *
 * [DurableInvocationResolver] used to reach for [StepReconcilerL1] and [DurableShellExecutor]
 * itself. That put process-reconciliation knowledge — control directories, reattach polling,
 * the 60 s reattach timeout — inside the class that owns the durable replay DECISION, which is
 * otherwise pure and tested at HF0 with no filesystem.
 *
 * The hook is not generic durable-protocol semantics. It is the running-subprocess behaviour of
 * the legacy core world, triggered only when a Step declares [RecoveryPolicy.ExternalSubprocess].
 * That is a composition concern, so it lives behind this port and the concrete implementation is
 * wired where the runtime is assembled.
 *
 * The decision keeps full ownership of WHEN recovery applies; this port only answers WHAT the
 * reconciler found. Keeping the policy check in the decision and the observation in the adapter
 * is the point: a caller must not be able to declare its own way to skip recovery.
 */
internal fun interface RunningSubprocessRecovery {

    /**
     * Observes the real external process for [operationId].
     *
     * Returns [RunningCanonicalShellRecovery.NotRunningShell] when there is nothing to recover —
     * including when the declared policy is not [RecoveryPolicy.ExternalSubprocess] or no control
     * root is configured. The decision core must not have to know which of those applied.
     */
    fun recover(
        recoveryPolicy: RecoveryPolicy,
        journaled: DurableOperation?,
        operationId: String,
    ): RunningCanonicalShellRecovery
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
) : RunningSubprocessRecovery {

    override fun recover(
        recoveryPolicy: RecoveryPolicy,
        journaled: DurableOperation?,
        operationId: String,
    ): RunningCanonicalShellRecovery {
        if (recoveryPolicy != RecoveryPolicy.ExternalSubprocess || journaled?.status != OperationStatus.RUNNING || controlDirRoot == null) {
            return RunningCanonicalShellRecovery.NotRunningShell
        }

        val reconciler = StepReconcilerL1(clock, controlDirRoot)
        val classification = reconciler.classify(operationId)
        return when (classification) {
            is StepReconcilerL1.Classification.Complete -> completedShellOutcome(classification.exitCode)
            is StepReconcilerL1.Classification.Reattach -> {
                val exitCode = DurableShellExecutor().pollResult(classification.controlDir, REATTACH_TIMEOUT_MS)
                if (exitCode == null) lostShellOutcome(operationId) else completedShellOutcome(exitCode)
            }
            is StepReconcilerL1.Classification.TimedOut -> RunningCanonicalShellRecovery.Recovered(
                StepOutcome.Failure(
                    PipelineFailure(FailureKind.TIMEOUT, "Canonical shell '$operationId' timed out"),
                ),
                OperationStatus.FAILED_TIMEOUT,
            )
            StepReconcilerL1.Classification.Lost -> lostShellOutcome(operationId)
        }
    }

    private fun completedShellOutcome(exitCode: Int): RunningCanonicalShellRecovery.Recovered =
        if (exitCode == 0) {
            RunningCanonicalShellRecovery.Recovered(StepOutcome.Success, OperationStatus.SUCCEEDED)
        } else {
            RunningCanonicalShellRecovery.Recovered(
                StepOutcome.Failure(
                    PipelineFailure(FailureKind.SCRIPT, "Canonical shell exited with code $exitCode"),
                ),
                OperationStatus.FAILED,
            )
        }

    private fun lostShellOutcome(operationId: String): RunningCanonicalShellRecovery.Recovered =
        RunningCanonicalShellRecovery.Recovered(
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
