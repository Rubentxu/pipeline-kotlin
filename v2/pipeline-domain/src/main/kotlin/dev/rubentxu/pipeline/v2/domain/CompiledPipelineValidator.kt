package dev.rubentxu.pipeline.v2.domain

/**
 * Thrown when block nesting depth exceeds the maximum allowed depth.
 */
class BlockNestingExceededException(
    val depth: Int,
    val maxDepth: Int = BlockNestingConstants.MAX_BLOCK_DEPTH,
) : RuntimeException("Block nesting depth $depth exceeds maximum $maxDepth at block")

/**
 * Structural validation for the canonical compiled pipeline IR.
 *
 * Enforces:
 * - Stage uniqueness
 * - Step uniqueness within same parent block or stage
 * - Block nesting depth <= 3
 * - BlockStepNode.pluginStepId must have takesBody=true in registry
 */
object CompiledPipelineValidator {
    private val descriptorRegistry = StepDescriptorRegistry.standard()

    fun validate(pipeline: CompiledPipeline) {
        require(pipeline.stages.isNotEmpty()) { "CompiledPipeline must contain at least one stage" }
        val stageIds = mutableSetOf<StageId>()
        val rootStepIds = mutableSetOf<StepId>()

        fun visitStage(stage: StageNode) {
            require(stageIds.add(stage.id)) { "Duplicate stage id '${stage.id.value}'" }
            when (val body = stage.body) {
                is StageBody.Steps -> body.steps.forEach { step ->
                    visitStep(step, "stage '${stage.name}'", rootStepIds, depth = 0)
                }
                is StageBody.NestedStages -> body.stages.forEach(::visitStage)
                is StageBody.Parallel -> {
                    require(body.branches.size >= 2) { "Parallel stage '${stage.name}' requires at least two branches" }
                    body.branches.forEach { branch ->
                        visitStage(branch)
                    }
                }
                is StageBody.Matrix -> require(body.matrix.axes.isNotEmpty()) {
                    "Matrix stage '${stage.name}' must define at least one axis"
                }
            }
            stage.post?.conditions?.values?.flatten()?.forEach { step ->
                visitStep(step, "post-condition of stage '${stage.name}'", rootStepIds, depth = 0)
            }
        }

        pipeline.stages.forEach(::visitStage)
    }

    /**
     * Validates a step recursively with depth tracking and intra-block scope.
     *
     * @param step The step to validate
     * @param parentPath Human-readable path for error messages
     * @param intraBlockIds StepId set for uniqueness within current block scope (reset at block boundaries)
     * @param depth Current block nesting depth (incremented on BlockStepNode)
     */
    private fun visitStep(
        step: StepNode,
        parentPath: String,
        intraBlockIds: MutableSet<StepId>,
        depth: Int,
    ) {
        when (step) {
            is OpaqueStepNode -> {
                require(step.payload.encoded.isNotBlank()) {
                    "Step '${step.id.value}' payload must not be blank"
                }
            }
            is BlockStepNode -> {
                val descriptor = descriptorRegistry.get(step.pluginStepId)
                require(descriptor?.takesBody == true) {
                    "BlockStepNode '${step.id.value}' (pluginStepId=${step.pluginStepId.value}) has takesBody=false but body is non-empty. " +
                        "Block steps must have takesBody=true in StepDescriptorRegistry."
                }
                require(step.body.isNotEmpty() || descriptor.bodyInvocations == BodyInvocationPolicy.ZERO_OR_MORE) {
                    "BlockStepNode '${step.id.value}' must have non-empty body unless bodyInvocations is ZERO_OR_MORE"
                }

                // Depth check (fail-closed)
                require(depth < BlockNestingConstants.MAX_BLOCK_DEPTH) {
                    throw BlockNestingExceededException(depth + 1, BlockNestingConstants.MAX_BLOCK_DEPTH)
                }

                // Intra-block StepId uniqueness (reset at block boundary)
                val childBlockIds = mutableSetOf<StepId>()
                step.body.forEach { child ->
                    require(childBlockIds.add(child.id)) {
                        "Duplicate step id '${child.id.value}' within block '${step.id.value}': ${parentPath}"
                    }
                    visitStep(child, "${parentPath}/${step.id.value}", childBlockIds, depth = depth + 1)
                }
            }
        }
    }
}
