package dev.rubentxu.pipeline.v2.domain

/** A schedulable unit produced directly from [CompiledPipeline]. */
sealed interface CompiledExecutionUnit {
    val steps: List<StepNode>

    /**
     * One step on its own.
     *
     * The unit holds exactly one step and states no ordering constraint
     * against anything else on the plan, so a dispatcher may run it as soon
     * as the plan reaches it.
     */
    data class Single(val step: StepNode) : CompiledExecutionUnit {
        override val steps: List<StepNode> = listOf(step)
    }

    /**
     * A wave of steps that may run at the same time.
     *
     * The constructor refuses fewer than two steps: a wave of one is not a
     * wave, and admitting it here would let a caller claim a concurrency
     * that does not exist. No ordering constraint holds between the steps,
     * and their declaration order is the order their outcomes must keep, so
     * that a fold over the wave stays deterministic however the wave
     * completes.
     */
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

/**
 * The schedulable units of one compiled pipeline, in the order a dispatcher
 * walks them.
 *
 * The plan is what the planner produces and what a run consumes: it is a
 * value, holds no reference to the pipeline it came from, and may be walked
 * more than once.
 *
 * [linearSteps] flattens the units into the step sequence they contain. It
 * reports the steps of a [CompiledExecutionUnit.Block] as the block itself,
 * because the block's body is scheduled by the plan that the block carries,
 * not by this one.
 */
data class CompiledExecutionPlan(val units: List<CompiledExecutionUnit>) {
    /** Every step of the plan, in unit order and, inside a unit, in its own order. */
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

                // A scripted body contributes NO static unit, and that is a fact rather
                // than a gap. The planner enumerates structure known at COMPILE time, and a
                // scripted body has none by construction: its steps are produced at RUN time by
                // the artifact. Emitting a placeholder unit here would be the declarative
                // "fake runtime return" the contract forbids, and emitting nothing
                // leaves the run with nothing to dispatch statically — which is the truth.
                is StageBody.Scripted -> Unit
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
