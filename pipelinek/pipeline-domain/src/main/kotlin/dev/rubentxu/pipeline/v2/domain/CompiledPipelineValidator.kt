package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver

/**
 * Thrown when block nesting depth exceeds the maximum allowed depth.
*/
class BlockNestingExceededException(
    val depth: Int,
    val maxDepth: Int = BlockNestingConstants.MAX_BLOCK_DEPTH,
) : RuntimeException("Block nesting depth $depth exceeds maximum $maxDepth at block")

/**
 * Structural validation for the canonical compiled pipeline IR.
 * Enforces:
 * - Stage uniqueness
 * - Step uniqueness within same parent block or stage
 * - Block nesting depth <= 3
 * - BlockStepNode.pluginStepId must resolve to an executable body policy
*/
object CompiledPipelineValidator {
    private val standardBodyPolicyResolver = StepDescriptorRegistry.standard()
        .bodyPolicyResolver(BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING_FOLDING)

    fun validate(pipeline: CompiledPipeline) = validate(pipeline, standardBodyPolicyResolver)

    /** Validates against the same body declarations the execution route resolves. */
    fun validate(pipeline: CompiledPipeline, bodyPolicyResolver: BodyPolicyResolver) {
        require(pipeline.stages.isNotEmpty()) { "CompiledPipeline must contain at least one stage" }
        val stageIds = mutableSetOf<StageId>()

        fun visitSteps(steps: List<StepNode>, parentPath: String, depth: Int) {
            val stepIds = mutableSetOf<StepId>()
            steps.forEach { step ->
                require(stepIds.add(step.id)) {
                    "Duplicate step id '${step.id.value}' within $parentPath"
                }
                visitStep(step, parentPath, depth, bodyPolicyResolver)
            }
        }

        fun visitStage(stage: StageNode) {
            require(stageIds.add(stage.id)) { "Duplicate stage id '${stage.id.value}'" }
            when (val body = stage.body) {
                is StageBody.Steps -> visitSteps(body.steps, "stage '${stage.name}'", depth = 0)
                is StageBody.NestedStages -> body.stages.forEach(::visitStage)
                is StageBody.Parallel -> {
                    require(body.branches.size >= 2) { "Parallel stage '${stage.name}' requires at least two branches" }
                    body.branches.forEach { branch ->
                        val branchSteps = branch.body as? StageBody.Steps
                            ?: throw IllegalArgumentException("Parallel branch '${branch.name}' must contain steps")
                        require(branchSteps.steps.size == 1) {
                            "Parallel branch '${branch.name}' must contain exactly one step"
                        }
                        visitStage(branch)
                    }
                }
                is StageBody.Matrix -> require(body.matrix.axes.isNotEmpty()) {
                    "Matrix stage '${stage.name}' must define at least one axis"
                }
                // A scripted body is validated BY CONSTRUCTION. `ScriptedStageRef`'s init
                // rejects a blank artifact key or entry point, so there is nothing left to assert
                // here and inventing an assertion would only be a restatement of the type. What
                // cannot be checked statically is whether the artifact RESOLVES, and that is an
                // edge concern answered fail-closed at dispatch, not a compile-time property.
                is StageBody.Scripted -> Unit
            }
            stage.post?.conditions?.forEach { (condition, steps) ->
                visitSteps(steps, "${condition} post-condition of stage '${stage.name}'", depth = 0)
            }
        }

        pipeline.stages.forEach(::visitStage)
    }

    /**
     * Validates a step recursively with depth tracking and intra-block scope.
     * @param step The step to validate
     * @param parentPath Human-readable path for error messages
     * @param depth Current block nesting depth (incremented on BlockStepNode)
*/
    private fun visitStep(
        step: StepNode,
        parentPath: String,
        depth: Int,
        bodyPolicyResolver: BodyPolicyResolver,
    ) {
        when (step) {
            is OpaqueStepNode -> {
                require(step.payload.encoded.isNotBlank()) {
                    "Step '${step.id.value}' payload must not be blank"
                }
            }
            is BlockStepNode -> {
                //: the declaration is one value. A node that carries a body must resolve to a
                // declared body row; a terminal row (StepBody.None) has no cardinality to consult.
                val resolution = bodyPolicyResolver.resolve(step.pluginStepId)
                val bodyPolicy = requireNotNull((resolution as? BodyPolicyResolution.Resolved)?.policy) {
                    "BlockStepNode '${step.id.value}' (pluginStepId=${step.pluginStepId.value}) carries a body but the resolver " +
                        "does not resolve an executable body policy: ${(resolution as? BodyPolicyResolution.Rejected)?.reason}"
                }
                require(step.body.isNotEmpty() || bodyPolicy is BodyExecutionPolicy.Retrying) {
                    "BlockStepNode '${step.id.value}' must have non-empty body unless its declared invocation is ZERO_OR_MORE"
                }

                // Depth check (fail-closed)
                require(depth < BlockNestingConstants.MAX_BLOCK_DEPTH) {
                    throw BlockNestingExceededException(depth + 1, BlockNestingConstants.MAX_BLOCK_DEPTH)
                }

                // Intra-block StepId uniqueness (reset at block boundary)
                val childBlockIds = mutableSetOf<StepId>()
                step.body.forEach { child ->
                    require(childBlockIds.add(child.id)) {
                        "Duplicate step id '${child.id.value}' within block '${step.id.value}': $parentPath"
                    }
                    visitStep(
                        child,
                        "${parentPath}/${step.id.value}",
                        depth = depth + 1,
                        bodyPolicyResolver = bodyPolicyResolver,
                    )
                }
            }
        }
    }
}
