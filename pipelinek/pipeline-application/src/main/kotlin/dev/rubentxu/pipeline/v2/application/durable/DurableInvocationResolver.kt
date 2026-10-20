package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.domain.BlockSegment
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
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.StepOutcome

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
    private val journal: OperationJournal,
    // TRAIN H3 / PR-019: the a2 external-subprocess compatibility hook is a PORT. The resolver
    // decides when recovery applies; the adapter observes the process. The resolver no longer
    // constructs StepReconcilerL1 / DurableShellExecutor, so this class stays free of process
    // and control-directory knowledge and remains testable with no filesystem.
    private val runningSubprocessRecovery: RunningSubprocessRecovery,
) {

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
     *  - running-process detection is the sole effectful part: it inspects and reattaches to a real
     *    external process. It is an explicit a2 compatibility hook, NOT generic durable-protocol
     *    semantics.
     *
     * ## Where the WHEN lives, after ADR-0103 R1-E
     *
     * This function decides **whether** a subprocess observation is owed
     * ([recoveryRequirement]) and only then asks **what** was found
     * ([RunningSubprocessRecovery.observe]). The two were conflated until the S4-R-REC spike, where
     * the observer decided for itself and reported "I could not look" as "there is nothing to
     * recover" — a distinction the decision core then had no way to recover, because the port's only
     * non-answer was a single sentinel.
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
                // class's job, and ADR-S4-R1 §2.3 fixes the meaning as a compatibility policy:
                //
                //     ReattachWindowExpired  ->  Recover(RecoveredTerminal.Lost(...))
                //
                // which preserves the certified observable behaviour byte for byte. The
                // non-terminal alternative — leave the row RUNNING, invent nothing — is DEFERRED
                // decision D-1, and it is a decision rather than a defect. Until it is taken, this
                // line is the only place in the codebase allowed to collapse the two facts, and it
                // collapses them with that reason written next to it.
                //
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
     * Whether a subprocess observation is owed — ADR-0103 RPL-2, enforced from the DECLARED
     * descriptor properties and never from a literal or a concrete Step key.
     *
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
}
