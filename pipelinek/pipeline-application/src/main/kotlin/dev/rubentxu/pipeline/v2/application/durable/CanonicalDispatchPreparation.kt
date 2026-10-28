package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata
import dev.rubentxu.pipeline.v2.application.CanonicalStructuralPreparation
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StructuralOverlay
import dev.rubentxu.pipeline.v2.application.StructuralOverlayProjection
import dev.rubentxu.pipeline.v2.application.StructuralPreparation
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ContextTransition
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything a dispatch needs BEFORE any effect, decided as one value.
 *
 * Closed because "structurally rejected" and "ready to execute" are opposite outcomes, and
 * because the rejection still has to carry the durable identity the journal row needs: a
 * rejection that lost its [operationId] could not be journaled fail-closed, it would simply
 * vanish. A nullable seam would have let exactly that happen silently.
 *
 * It lives at the top level of the package and not inside the dispatch engine because the
 * engine does not decide it: [CanonicalDispatchPreparation] decides it, and the engine only
 * interprets the answer.
 */
internal sealed interface DispatchPreparation {
    /** Terminal SCHEMA rejection. The identity is carried so the caller can journal it. */
    data class Rejected(
        val operationId: String,
        val input: OperationInput,
        val reason: String,
    ) : DispatchPreparation

    /** Admitted. Every field is derived; nothing here observed anything. */
    data class Ready(
        val opId: OpId,
        val operationId: String,
        val input: OperationInput,
        val contextAfterOverlay: ExecutionContext,
        val metadata: StepMetadata,
        val fingerprint: Fingerprint,
        val lifecycleContext: StepLifecycleContext,
    ) : DispatchPreparation
}

/**
 * WHERE a dispatch happens, as one value.
 *
 * The four coordinates travel together because they are one fact: the run, the stage, the step
 * and the body path are the identity of the operation, and a signature that lists them one by
 * one lets a caller pair a body path of one call with a run of another.
 */
internal data class DispatchCoordinates(
    val runId: RunId,
    val stageIndex: Int,
    val stepIndex: Int,
    val bodyPath: List<BlockSegment>,
)

/**
 * The PURE pre-execution prologue of a dispatch: durable identity, structural verdict,
 * control-overlay projection, metadata and fingerprint. No journal, no events, no executor and
 * no clock, so the whole thing is decidable — and therefore testable — without a run.
 *
 * It is the OWNER of the preparation decision: the dispatch engine asks it and interprets the
 * answer, it never re-derives any of these facts.
 *
 * Order is contract. The durable identity is derived FIRST because every rejection path
 * needs it to journal the rejection. The structural gate precedes typed decode because a
 * structurally-invalid node must be rejected before any typed semantics are read
 * . Metadata is resolved by structural step key BEFORE the fingerprint, so
 * reconciliation never depends on a decoded command.
 */
internal class CanonicalDispatchPreparation(
    private val metadataResolver: StepMetadataResolver,
) {
    /**
     * Decides everything a dispatch needs before any effect happens.
     *
     * @param now the instant a catchError scope is entered, in epoch millis. It is an
     *   ARGUMENT, not a reading of the ambient clock: the core of this decision has no
     *   clock of its own, so the instant is a fact the caller observes and hands over.
     */
    fun prepare(
        step: StepNode,
        coordinates: DispatchCoordinates,
        stageShOptions: ShOptions,
        hasBodyContinuation: Boolean,
        executionContext: ExecutionContext,
        now: Long,
    ): DispatchPreparation {
        val opId = OpId(
            coordinates.runId.value,
            coordinates.stageIndex,
            coordinates.stepIndex,
            bodyPath = coordinates.bodyPath,
        )
        val operationId = opId.format()
        // the body of a HANDLER_CONTINUATION Step is part of the parent's durable
        // identity. Without it, the same parent payload with a different body would reuse a
        // memoized result, the handler would never run, and the changed child would not even
        // reach divergence. Engine-driven blocks are deliberately EXCLUDED so every journal
        // row written by the published 0.45.0 candidate stays valid.
        val bodyStructure: String? = if (hasBodyContinuation && step is BlockStepNode) {
            BodyStructureDigest.of(step.body)
        } else {
            null
        }
        val input = OperationInput(
            stepId = step.pluginStepId.value,
            // a non-NONE sandbox profile enters the operation fingerprint,
            // so a resume with a CHANGED confinement profile diverges fail-closed instead
            // of silently re-attaching under different semantics. NONE stays absent from
            // params so default-run journals keep their historical fingerprints.
            params = buildMap {
                put("payload", JsonPrimitive(step.payload.encoded))
                if (stageShOptions.sandbox.profile != SandboxProfile.NONE) {
                    put("sandboxProfile", JsonPrimitive(stageShOptions.sandbox.profile.name))
                }
                bodyStructure?.let { digest -> put("bodyStructure", JsonPrimitive(digest)) }
            },
            runId = coordinates.runId.value,
            attempt = 1,
        )

        val structuralReady = when (val structural = CanonicalStructuralPreparation.prepare(step)) {
            is StructuralPreparation.Rejected ->
                return DispatchPreparation.Rejected(operationId, input, structural.reason)
            is StructuralPreparation.Ready -> structural
        }
        // pure context derivation, no ambient mutation (C6 preserved: pushed pre-reconcile).
        val projectedOverlay = StructuralOverlayProjection.project(
            structuralReady.invocation.stepKey,
            structuralReady.envelope,
        )
        // Baseline behaviour preserved: Triggered with emitted=false performs NO scope exit.
        val overlayExitedSilently =
            projectedOverlay is StructuralOverlay.CatchErrorTriggered && !projectedOverlay.emitted
        val contextAfterOverlay = if (overlayExitedSilently) {
            executionContext
        } else {
            deriveOverlay(executionContext, projectedOverlay, now)
        }

        // durable metadata resolved by structural step key BEFORE typed semantics, so
        // fingerprint and reconcile never depend on the decoded command.
        val metadata = metadataResolver.resolve(step.pluginStepId)
            ?: throw EngineInvariantViolation(
                "No durable metadata for canonical step '${step.pluginStepId.value}'",
            )

        return DispatchPreparation.Ready(
            opId = opId,
            operationId = operationId,
            input = input,
            contextAfterOverlay = contextAfterOverlay,
            metadata = metadata,
            fingerprint = Fingerprint.compute(input, step.pluginStepId.value, metadata.replayPolicy, 1),
            lifecycleContext = StepLifecycleContext(
                runId = coordinates.runId.value,
                stageIndex = coordinates.stageIndex,
                stepIndex = coordinates.stepIndex,
                stepName = step.id.value,
                stepType = CanonicalCoreStepMetadata.shortType(step.pluginStepId.value),
            ),
        )
    }

    /**
     * pure derivation of the successor ExecutionContext from the structural overlay.
     * The old CatchErrorTriggered pop/underflow check disappears: the frame was pushed by the
     * matching CatchErrorEntered derivation, and the caller simply keeps its own parent value.
     * There is no shared authority to underflow.
     */
    private fun deriveOverlay(
        parent: ExecutionContext,
        overlay: StructuralOverlay,
        now: Long,
    ): ExecutionContext = when (overlay) {
        StructuralOverlay.None -> parent
        is StructuralOverlay.CatchErrorEntered -> parent.pushed(
            ContextOverlay.CatchErrorOverlay(
                overlay.buildResult,
                overlay.stageResult,
                overlay.message,
                overlay.enteredAt?.toLongOrNull() ?: now,
            ),
        )
        is StructuralOverlay.CatchErrorTriggered -> {
            val exit = parent.exitCatchError()
            check(exit is ContextTransition.Advanced) {
                "Context stack underflow: CatchErrorTriggered without matching CatchErrorEntered"
            }
            exit.context
        }
    }
}
