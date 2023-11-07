package dev.rubentxu.pipeline.dsl

/**
 * This class defines the DSL for creating a stage.
 *
 * @property pipeline The pipeline to create the stage in.
 */
@PipelineDsl
class StageDsl(val name: String, val pipeline: Pipeline) {

    var stagePost: Post = Post(pipeline)

    /**
     * This function defines the steps for the stage.
     *
     * @param block A block of code to define the steps.
     */
    suspend fun steps(block: suspend StepBlock.() -> Any) {
        val steps = StepBlock(pipeline)
        steps.block()
    }

    /**
     * Defines a block of code to execute after all stages have been executed.
     *
     * @param block The block of code to execute.
     */
    fun post(block: Post.() -> Unit) {
        stagePost = Post(pipeline).apply(block)
    }
}