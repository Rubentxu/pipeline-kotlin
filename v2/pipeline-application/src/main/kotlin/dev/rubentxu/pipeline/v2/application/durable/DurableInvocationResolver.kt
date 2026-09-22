package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import java.nio.file.Path

/**
 * WU-RP-031 E2 — durable invocation reconciliation extracted from the
 * coordinator. Owns exactly the frozen a1/a2.3 decision: divergence gate,
 * external-subprocess recovery hook, effect-aware replay resolution.
 * Deps are the minimal collaborators of that decision; no event sink, no
 * cursor store, no dispatcher.
 */
internal class DurableInvocationResolver(
    private val divergenceDetector: dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector,
    private val effectReplayPolicy: EffectReplayPolicy,
    private val clock: dev.rubentxu.pipeline.v2.domain.durable.Clock,
    private val journal: OperationJournal,
    private val controlDirRoot: Path?,
) {

    companion object {
        // Preserved from CanonicalDurableRunCoordinator companion (behavior-equivalence law).
        private val REATTACH_TIMEOUT_MS = 60_000L
    }

    /**
     * Records a FAILED journal row and returns the terminal `SCHEMA` [StepOutcome]; the effective
     * executor is never invoked (C3/C5). Fingerprint uses [ReplayPolicy.RERUN] as on the rejection path.
     */
    internal fun rejectSchema(operationId: String, input: OperationInput, message: String): StepOutcome {
        val fingerprint = Fingerprint.compute(input, input.stepId, ReplayPolicy.RERUN, 1)
        journal.append(
            RerunOperation(
                id = operationId,
                fingerprint = fingerprint,
                input = input,
                output = null,
                status = OperationStatus.FAILED,
                attempt = 1,
            ),
        )
        return StepOutcome.Failure(
            PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA, message),
        )
    }

    /**
     * Resolves the durable replay/reconcile decision for an invocation (B1.2c2-a2.3, CDE.2-b4).
     *
     * The decision has two purity domains, kept separate:
     *  - [deterministicGate] is pure: fingerprint divergence and the effect-aware replay policy decide
     *    from their inputs alone.
     *  - running-process detection ([recoverRunningShell]) is the sole effectful part: it inspects and
     *    reattaches to a real external process. It is an explicit a2 compatibility hook, NOT generic
     *    durable-protocol semantics; it triggers only when the operation declares
     *    [RecoveryPolicy.ExternalSubprocess] AND the journal is RUNNING AND a control dir exists.
     *
     * The durable decision consumes only the typed [StepMetadata] properties resolved by step key
     * (CDE.2-b2/b4); it never selects behaviour by a concrete Step name.
     *
     * Precedence reproduces the frozen a1 flow exactly: divergence first, then recovery, then replay.
     * No journal, cursor, event or executor is touched here; terminal resolutions carry only the data
     * their own handling needs.
     */
    internal fun reconcileInvocation(
        metadata: StepMetadata,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        currentOperation: RerunOperation,
        operationId: String,
    ): InvocationReconciliation {
        deterministicGate(currentOperation, journaled, operationId, metadata.effects, metadata.replayPolicy)?.let { return it }
        when (val recovery = recoverRunningShell(metadata.recoveryPolicy, journaled, operationId)) {
            RunningCanonicalShellRecovery.NotRunningShell -> Unit
            is RunningCanonicalShellRecovery.Recovered ->
                return InvocationReconciliation.RecoverRunning(recovery.outcome, recovery.status)
        }
        return replayResolution(effectReplayPolicy.decide(metadata.replayPolicy, metadata.effects, journaled != null, journaled?.status), operationId)
    }

    /**
     * Pure, deterministic part of the reconciliation: the fingerprint-divergence gate (B1.2c2-a2.3).
     * Returns a terminal divergence resolution when the fingerprints diverge, otherwise `null` so the
     * recovery hook and replay kernel can run in the frozen order. Never touches a journal, cursor,
     * process, event or executor.
     */
    internal fun deterministicGate(
        currentOperation: RerunOperation,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        operationId: String,
        effects: Set<Effect>,
        replayPolicy: ReplayPolicy,
    ): InvocationReconciliation? {
        if (divergenceDetector.check(currentOperation, journaled).isFailure) {
            return InvocationReconciliation.Diverged(operationId)
        }
        return null
    }

    /**
     * Pure mapping of the effect-aware replay policy onto the reconciliation resolutions (B1.2c2-a2.3).
     */
    internal fun replayResolution(decision: ReplayDecision, operationId: String): InvocationReconciliation =
        when (decision) {
            ReplayDecision.SKIP -> InvocationReconciliation.ReuseCompleted
            ReplayDecision.ABORT -> InvocationReconciliation.RejectedAbort(operationId)
            ReplayDecision.RERUN -> InvocationReconciliation.Execute
        }

    internal fun recoverRunningShell(
        recoveryPolicy: RecoveryPolicy,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
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
                    PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT, "Canonical shell '$operationId' timed out"),
                ),
                OperationStatus.FAILED_TIMEOUT,
            )
            StepReconcilerL1.Classification.Lost -> lostShellOutcome(operationId)
        }
    }

    internal fun completedShellOutcome(exitCode: Int): RunningCanonicalShellRecovery.Recovered =
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

    internal fun lostShellOutcome(operationId: String): RunningCanonicalShellRecovery.Recovered =
        RunningCanonicalShellRecovery.Recovered(
            StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, "Canonical shell '$operationId' could not be reconciled"),
            ),
            OperationStatus.LOST,
        )
}
