package dev.rubentxu.pipeline.dsl

/**
 * This class defines the DSL for creating a stage.
 *
 * @property pipeline The pipeline to create the stage in.
 */
class StageDsl(val pipeline: PipelineDsl) {

    /**
     * This function defines the steps for the stage.
     *
     * @param block A block of code to define the steps.
     */
    suspend fun steps(block: suspend StepBlock.() -> Any) {
        val steps = StepBlock(pipeline)
        steps.block()
    }
}