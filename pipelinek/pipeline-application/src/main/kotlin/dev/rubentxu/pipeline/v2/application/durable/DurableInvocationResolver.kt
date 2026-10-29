package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * Durable invocation reconciliation extracted from the
 * coordinator. Owns exactly one frozen decision: divergence gate,
 * external-subprocess recovery hook, effect-aware replay resolution.
 * Deps are the minimal collaborators of that decision; no event sink, no
 * cursor store, no dispatcher.
 *
 * ## It is also the READER of the journaled row
 *
 * The reconciliation compares a fingerprint against the row this invocation had. That row is a
 * FACT, and reading it is part of deciding — it is not a lookup the caller does on the
 * side. This class therefore reads the row itself, through [DurableInvocationRecord], and
 * returns the row TOGETHER with the decision in [ReconciledInvocation], so the caller never
 * asks the journal a second, differently-worded question about the same invocation.
 *
 * Before this class owned the read, the two callers of the reconciliation each read the row
 * their own way and each rebuilt the same `PENDING` envelope: the engine asked for the exact
 * attempt and the scripted invoker asked for the highest attempt. One of those two answers was
 * a row of a DIFFERENT attempt than the fingerprint it was compared against.
*/
internal class DurableInvocationResolver(
    private val divergenceDetector: dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector,
    private val effectReplayPolicy: EffectReplayPolicy,
    private val records: DurableInvocationRecord,
    // The external-subprocess compatibility hook is a PORT. The resolver
    // decides when recovery applies; the adapter observes the process. The resolver no longer
    // constructs StepReconcilerL1 / DurableShellExecutor, so this class stays free of process
    // and control-directory knowledge and remains testable with no filesystem.
    private val runningSubprocessRecovery: RunningSubprocessRecovery,
) {

    /**
     * Records a FAILED journal row and returns the terminal `SCHEMA` [StepOutcome]; the effective
     * Executor is never invoked. Fingerprint uses [ReplayPolicy.RERUN] as on the rejection path.
     *
     * The row is written through [DurableInvocationRecord], which owns the row's shape; the
     * fingerprint stays this method's because a schema rejection never reaches the dispatch
     * preparation that owns the canonical one.
    */
    internal fun rejectSchema(operationId: String, input: OperationInput, message: String): StepOutcome {
        val fingerprint = Fingerprint.compute(
            input,
            input.stepId,
            ReplayPolicy.RERUN,
            DurableInvocationRecord.ATTEMPT,
        )
        records.settle(operationId, fingerprint, input, OperationStatus.FAILED, null)
        return StepOutcome.Failure(
            PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA, message),
        )
    }

    /**
     * Resolves the durable replay/reconcile decision for an invocation, and returns the row the
     * decision was made from.
     *
     * The decision has two purity domains, kept separate:
     *  - [deterministicGate] is pure: fingerprint divergence and the effect-aware replay policy decide
     *    from their inputs alone.
     *  - running-process detection is the sole effectful part: it inspects and reattaches to a real
     *    external process. It is an explicit compatibility hook, NOT generic durable-protocol
     *    semantics.
     * ## Where the WHEN lives
     * This function decides **whether** a subprocess observation is owed
     * ([recoveryRequirement]) and only then asks **what** was found
     * ([RunningSubprocessRecovery.observe]). The two were conflated until the spike, where
     * the observer decided for itself and reported "I could not look" as "there is nothing to
     * recover" — a distinction the decision core then had no way to recover, because the port's only
     * non-answer was a single sentinel.
     * The durable decision consumes only the typed [StepMetadata] properties resolved by step key
     * it never selects behaviour by a concrete Step name.
     * Precedence reproduces the frozen flow exactly: divergence first, then recovery, then replay.
     * No cursor, event or executor is touched here; terminal resolutions carry only the data
     * their own handling needs.
     *
     * ## What it returns
     * [ReconciledInvocation] carries the decision AND the row the decision was made from. The
     * caller that goes on to reuse a completed result, or to admit an execution, needs that
     * row; giving it back here means the caller does not read the journal again.
    */
    internal fun reconcileInvocation(
        metadata: StepMetadata,
        fingerprint: Fingerprint,
        input: OperationInput,
        operationId: String,
    ): ReconciledInvocation {
        val journaled = records.read(operationId)
        val currentOperation = records.pending(operationId, fingerprint, input)
        return ReconciledInvocation(
            resolution = decide(metadata, currentOperation, journaled, operationId),
            journaled = journaled,
        )
    }

    /**
     * The frozen decision, over a row the caller of this class already read.
     *
     * [journaled] is `null` when the invocation was never journalled, which is a fact and not
     * the absence of one: it is what lets the replay kernel see a fresh invocation.
    */
    private fun decide(
        metadata: StepMetadata,
        currentOperation: RerunOperation,
        journaled: DurableOperation?,
        operationId: String,
    ): InvocationReconciliation {
        deterministicGate(currentOperation, journaled, operationId, metadata.effects, metadata.replayPolicy)?.let { return it }
        if (recoveryRequirement(metadata.recoveryPolicy, journaled) == RecoveryRequirement.Required) {
            when (val observation = runningSubprocessRecovery.observe(operationId)) {
                is RunningSubprocessObservation.Observed ->
                    return InvocationReconciliation.RecoverRunning(observation.terminal)
                // Required, and we could not look. This settles here rather than falling through to
                // the replay kernel: the kernel's RERUN would execute an external effect whose prior
                // state is unknown, which is the at-least-once window this arm closes.
                is RunningSubprocessObservation.Unavailable ->
                    return InvocationReconciliation.RecoveryUnobservable(operationId)
                // The substrate said the process was still reattachable and our window closed
                // without a terminal. The OBSERVER reports that fact; giving it meaning is this
                // Class's job, and fixes the meaning as a compatibility policy
                //     ReattachWindowExpired  ->  Recover(RecoveredTerminal.Lost)
                // which preserves the certified observable behaviour byte for byte. The
                // non-terminal alternative — leave the row RUNNING, invent nothing — is DEFERRED
                // Decision, and it is a decision rather than a defect. Until it is taken, this
                // line is the only place in the codebase allowed to collapse the two facts, and it
                // collapses them with that reason written next to it.
                // The message does NOT quantify the window. How long the observer waited is a fact
                // it owns, and a pure authority that repeated the number would be carrying a second
                // copy of it that could drift.
                RunningSubprocessObservation.ReattachWindowExpired ->
                    return InvocationReconciliation.RecoverRunning(
                        DurableTaskTerminal.Lost(
                            FailureRecord(
                                code = "REATTACH_WINDOW_EXPIRED",
                                kind = FailureKind.INFRASTRUCTURE,
                                message = "Canonical shell '$operationId' was still reattachable " +
                                    "when the observation window closed, so its outcome is unknown " +
                                    "rather than observed absent",
                                origin = FailureOrigin.RECONCILIATION,
                                retryable = false,
                                operationId = operationId,
                            ),
                        ),
                    )
            }
        }
        return replayResolution(effectReplayPolicy.decide(metadata.replayPolicy, metadata.effects, journaled != null, journaled?.status), operationId)
    }

    /**
     * Whether a subprocess observation is owed —, enforced from the DECLARED
     * descriptor properties and never from a literal or a concrete Step key.
     * Pure, and the only place in the recovery path allowed to answer this question. [RecoveryPolicy.None]
     * and a row that is not `RUNNING` are both ordinary "not applicable" and both fall through to
     * the replay policy unchanged; a row that IS `RUNNING` under a subprocess policy is the third,
     * separate case, and it is the one that used to be lost.
    */
    internal fun recoveryRequirement(
        recoveryPolicy: RecoveryPolicy,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
    ): RecoveryRequirement =
        if (recoveryPolicy == RecoveryPolicy.ExternalSubprocess && journaled?.status == dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.RUNNING) {
            RecoveryRequirement.Required
        } else {
            RecoveryRequirement.NotApplicable
        }

    /**
     * Pure, deterministic part of the reconciliation: the fingerprint-divergence gate
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
     * Pure mapping of the effect-aware replay policy onto the reconciliation resolutions
    */
    internal fun replayResolution(decision: ReplayDecision, operationId: String): InvocationReconciliation =
        when (decision) {
            ReplayDecision.SKIP -> InvocationReconciliation.ReuseCompleted
            ReplayDecision.ABORT -> InvocationReconciliation.RejectedAbort(operationId)
            ReplayDecision.RERUN -> InvocationReconciliation.Execute
        }
}
