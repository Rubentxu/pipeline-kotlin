package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.PluginStepType
import dev.rubentxu.pipeline.v2.application.CanonicalStructuralPreparation
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StructuralPreparation
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
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
 * metadata and fingerprint. No journal, no events, no executor and no clock, so the whole
 * thing is decidable — and therefore testable — without a run.
 *
 * It is the OWNER of the preparation decision: the dispatch engine asks it and interprets the
 * answer, it never re-derives any of these facts.
 *
 * Order is contract. The durable identity is derived FIRST because every rejection path
 * needs it to journal the rejection. The structural gate precedes typed decode because a
 * structurally-invalid node must be rejected before any typed semantics are read
 * . Metadata is resolved by structural step key BEFORE the fingerprint, so
 * reconciliation never depends on a decoded command.
 *
 * ## What it does NOT decide
 * It used to project a CONTROL OVERLAY here — the frame a `catchError` scope pushed when
 * it was entered and popped when it was triggered — and the stage continuation walked that
 * frame to decide what a failure meant. That is GONE: a scope's contract travels in its
 * block payload and its outcome is decided by the scope itself, inside the scope. Two
 * authorities on what a control scope means would have been a second event authority, and
 * this class no longer has one to offer.
 */
internal class CanonicalDispatchPreparation(
    private val metadataResolver: StepMetadataResolver,
) {
    /**
     * Decides everything a dispatch needs before any effect happens.
     *
     * There is no instant here any more. The decision used to carry one because a
     * `catchError` scope pushed a frame stamped with the moment it was entered, and
     * the stamp travelled inside the durable identity. The scope's contract now
     * travels in the block payload and the scope's outcome is decided by the scope
     * itself, so no frame is pushed and nothing here reads a clock.
     */
    fun prepare(
        step: StepNode,
        coordinates: DispatchCoordinates,
        stageShOptions: ShOptions,
        hasBodyContinuation: Boolean,
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

        // The structural gate: a node that is not structurally a canonical Step is
        // rejected here, before any typed semantics are read.
        when (val structural = CanonicalStructuralPreparation.prepare(step)) {
            is StructuralPreparation.Rejected ->
                return DispatchPreparation.Rejected(operationId, input, structural.reason)
            is StructuralPreparation.Ready -> Unit
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
            metadata = metadata,
            fingerprint = Fingerprint.compute(input, step.pluginStepId.value, metadata.replayPolicy, 1),
            lifecycleContext = StepLifecycleContext(
                runId = coordinates.runId.value,
                stageIndex = coordinates.stageIndex,
                stepIndex = coordinates.stepIndex,
                stepName = step.id.value,
                stepType = PluginStepType.shortType(step.pluginStepId.value),
            ),
        )
    }
}
