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
                is StageBody.Steps -> body.steps.forEach { units += CompiledExecutionUnit.Single(it) }
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
}
