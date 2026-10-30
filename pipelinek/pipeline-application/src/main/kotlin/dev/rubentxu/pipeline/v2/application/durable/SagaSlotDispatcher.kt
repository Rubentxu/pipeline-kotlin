package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodySlots
import dev.rubentxu.pipeline.v2.domain.step.SlotResolution
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions

/**
 * The coordinates of the slot invocation: WHERE the body that owns the slot is, and WHAT the
 * slot's child will be launched with.
 *
 * They travel together because they are read together: the dispatcher needs the parent's path
 * to derive the child's, the parent's options and context to project the patch onto, and the
 * run's coordinates to re-enter the spine with the same identity every other child carries.
 */
internal data class SagaSlotRequest(
    val runId: RunId,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val bodyPath: List<BlockSegment>,
    val stageShOptions: ShOptions,
    val executionContext: ExecutionContext,
)

/**
 * The Saga slot seam, with ONE owner.
 *
 * ## What it owns
 *
 * A Saga body has alternatives: the handler asks the engine to run one of the children the body
 * owns, by address. This class decides which child that address names, what identity the child
 * travels with, and what the answer is when the address names nothing or names too much.
 *
 * ## Why it is a separate unit
 *
 * It knows ONE way back into the spine: the [BodyChildDispatcher] seam the engine hands it.
 * It does not know how a child is dispatched, so it cannot open a second path into the
 * dispatch. That is the whole point of the extraction: the slot used to reach the dispatcher
 * directly, which made the seam it was given a decoration rather than the mechanism, and left
 * the engine with two call sites into the same spine.
 *
 * ## What it decides
 *
 * - a slot the body does not have, or a body that holds two children under the same address,
 *   is a CONTAINED failure in the handler's own outcome, not an exception: the contract was
 *   the engine's to keep and the handler named something the body does not have;
 * - a slot the body has is dispatched through the seam, carrying the path the canonical body
 *   loop WOULD have given it — the parent's path, the attempt, and the child's own segment —
 *   so the slot's journal rows are the rows that child would have had, and a body dispatched
 *   by the engine and a body dispatched by the handler are the same operation.
 */
internal class SagaSlotDispatcher(
    private val ownedBodies: List<StepNode>,
    private val ownerName: String,
    private val reentry: BodyChildDispatcher,
) {

    /**
     * The port the handler reaches the slot through.
     *
     * The invoker is the whole seam: it resolves the address, projects the patch and answers.
     */
    fun invoker(request: SagaSlotRequest): BodySlotInvoker = BodySlotInvoker { slot, slotContext ->
        when (
            val resolution = BodySlots.resolve(
                // The ADDRESS of each child: the name the plugin gave it, or its kind when
                // nobody named it. So a body that names its children can hold two of the same
                // kind, and a body that names none behaves exactly as it did before names
                // existed.
                ownedBodies.map { it.address },
                slot,
            )
        ) {
            is SlotResolution.Missing -> slotFailure(
                "no body slot '${slot.value}' in the body of '$ownerName'",
            )

            is SlotResolution.Ambiguous -> slotFailure(
                "body slot '${slot.value}' is ambiguous in the body of '$ownerName': " +
                    "${resolution.matches} children share that address",
            )

            is SlotResolution.Found -> {
                val childIndex = resolution.childIndex
                val child = ownedBodies[childIndex]
                val attemptSegment = slotContext.attempt?.let { segment ->
                    listOf(BlockSegment(segment.index, segment.key))
                }.orEmpty()
                val childBodyPath = request.bodyPath + attemptSegment +
                    BlockSegment(childIndex, child.pluginStepId)
                // The patch is projected onto BOTH surfaces: the context AND the options the
                // child is launched with. A body that has to recognise its own operation key
                // can only do it if the key reaches the process, and a process is launched
                // with the options.
                when (
                    val projection = projectInvocationPatch(
                        request.executionContext,
                        request.stageShOptions,
                        slotContext.patch,
                    )
                ) {
                    is InvocationPatchProjection.Refused -> slotFailure(projection.reason)

                    is InvocationPatchProjection.Applied -> BodyOutcome.Completed(
                        reentry.dispatchChild(
                            child = child,
                            runId = request.runId,
                            stageName = request.stageName,
                            stageIndex = request.stageIndex,
                            stepIndex = request.stepIndex,
                            childShOptions = projection.shOptions,
                            bodyPath = childBodyPath,
                            executionContext = projection.context,
                        ),
                    )
                }
            }
        }
    }

    /**
     * A slot the engine cannot honour, answered inside the handler's own outcome.
     *
     * A CONTAINED failure, not an exception: the violation belongs to the handler's outcome,
     * and an exception would tear the run down instead of reporting it.
     */
    private fun slotFailure(message: String): BodyOutcome =
        BodyOutcome.Completed(
            StepOutcome.Failure(
                PipelineFailure(
                    kind = FailureKind.ENGINE,
                    message = message,
                ),
            ),
        )
}
