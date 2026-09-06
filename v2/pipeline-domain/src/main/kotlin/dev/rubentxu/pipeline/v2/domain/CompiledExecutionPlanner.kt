package dev.rubentxu.pipeline.v2.domain

/** A schedulable unit produced directly from [CompiledPipeline]. */
sealed interface CompiledExecutionUnit {
    val steps: List<StepNode>

    data class Single(val step: StepNode) : CompiledExecutionUnit {
        override val steps: List<StepNode> = listOf(step)
    }

    data class Concurrent(override val steps: List<StepNode>) : CompiledExecutionUnit {
        init { require(steps.size >= 2) { "Concurrent unit requires at least two steps" } }
    }

    /**
     * A block step with its compiled body plan.
     * The block counts as ONE planning unit; its body is represented as a separate [CompiledExecutionPlan].
     */
    data class Block(
        val block: BlockStepNode,
        val bodyPlan: CompiledExecutionPlan,
    ) : CompiledExecutionUnit {
        override val steps: List<StepNode> = listOf(block)
    }
}

data class CompiledExecutionPlan(val units: List<CompiledExecutionUnit>) {
    val linearSteps: List<StepNode> get() = units.flatMap { it.steps }
}

/** Deterministic, pure planner for the canonical executable IR. */
object CompiledExecutionPlanner {
    fun plan(pipeline: CompiledPipeline): CompiledExecutionPlan {
        CompiledPipelineValidator.validate(pipeline)
        val units = mutableListOf<CompiledExecutionUnit>()

        fun append(stage: StageNode) {
            when (val body = stage.body) {
                is StageBody.Steps -> body.steps.forEach { step ->
                    units += planStep(step)
                }
                is StageBody.NestedStages -> body.stages.forEach(::append)
                is StageBody.Parallel -> {
                    val branchSteps = body.branches.map { branch ->
                        val branchBody = branch.body as? StageBody.Steps
                            ?: error("Parallel branch '${branch.name}' must contain steps")
                        require(branchBody.steps.size == 1) {
                            "Parallel branch '${branch.name}' must contain exactly one step"
                        }
                        branchBody.steps.single()
                    }
                    units += CompiledExecutionUnit.Concurrent(branchSteps)
                }
                is StageBody.Matrix -> error("Matrix planning is not supported yet for stage '${stage.name}'")
            }
        }

        pipeline.stages.forEach(::append)
        return CompiledExecutionPlan(units)
    }

    /**
     * Plans a single step, returning a [CompiledExecutionUnit].
     * For [BlockStepNode], wraps in a [CompiledExecutionUnit.Block] with recursive body plan.
     */
    private fun planStep(step: StepNode): CompiledExecutionUnit = when (step) {
        is OpaqueStepNode -> CompiledExecutionUnit.Single(step)
        is BlockStepNode -> {
            val bodyPlan = planBody(step.body)
            CompiledExecutionUnit.Block(step, bodyPlan)
        }
    }

    /**
     * Plans a list of steps into a [CompiledExecutionPlan].
     */
    private fun planBody(steps: List<StepNode>): CompiledExecutionPlan {
        val bodyUnits = steps.map { step -> planStep(step) }
        return CompiledExecutionPlan(bodyUnits)
    }
}
