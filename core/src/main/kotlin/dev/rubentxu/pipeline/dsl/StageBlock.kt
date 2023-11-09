package dev.rubentxu.pipeline.dsl

/**
 * This class defines the DSL for creating a stage.
 *
 * @property pipeline The pipeline to create the stage in.
 */
@PipelineDsl
class StageBlock(val name: String, val pipeline: Pipeline) {

    var stagePostExecutionBlock: PostExecutionBlock = PostExecutionBlock(pipeline)

    /**
     * This function defines the steps for the stage.
     *
     * @param block A block of code to define the steps.
     */
    fun steps(block: StepsBlock.() -> Any) {
        val steps = StepsBlock(pipeline)
        steps.block()
    }

    /**
     * Defines a block of code to execute after all stages have been executed.
     *
     * @param block The block of code to execute.
     */
    fun post(block: PostExecutionBlock.() -> Unit) {
        stagePostExecutionBlock = PostExecutionBlock(pipeline).apply(block)
    }
}