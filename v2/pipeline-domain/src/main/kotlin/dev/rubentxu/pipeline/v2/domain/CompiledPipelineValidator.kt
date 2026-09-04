package dev.rubentxu.pipeline.v2.domain

/** Structural validation for the canonical compiled pipeline IR. */
object CompiledPipelineValidator {
    fun validate(pipeline: CompiledPipeline) {
        require(pipeline.stages.isNotEmpty()) { "CompiledPipeline must contain at least one stage" }
        val stageIds = mutableSetOf<StageId>()
        val stepIds = mutableSetOf<StepId>()

        fun visit(stage: StageNode) {
            require(stageIds.add(stage.id)) { "Duplicate stage id '${stage.id.value}'" }
            when (val body = stage.body) {
                is StageBody.Steps -> body.steps.forEach { step ->
                    require(stepIds.add(step.id)) { "Duplicate step id '${step.id.value}'" }
                    require(step.payload.encoded.isNotBlank()) {
                        "Step '${step.id.value}' payload must not be blank"
                    }
                }
                is StageBody.NestedStages -> body.stages.forEach(::visit)
                is StageBody.Parallel -> {
                    require(body.branches.size >= 2) { "Parallel stage '${stage.name}' requires at least two branches" }
                    body.branches.forEach(::visit)
                }
                is StageBody.Matrix -> require(body.matrix.axes.isNotEmpty()) {
                    "Matrix stage '${stage.name}' must define at least one axis"
                }
            }
            stage.post?.conditions?.values?.flatten()?.forEach { step ->
                require(stepIds.add(step.id)) { "Duplicate post step id '${step.id.value}'" }
            }
        }

        pipeline.stages.forEach(::visit)
    }
}
