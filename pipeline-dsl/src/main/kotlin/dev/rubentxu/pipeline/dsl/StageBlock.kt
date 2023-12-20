package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.pipeline.PostExecution

/**
 * This class defines the DSL for creating a stage.
 *
 * @property context The pipeline to create the stage in.
 */
@PipelineDsl
class StageBlock(val name: String, val context: IPipelineContext) {
    var postExecution: PostExecution = PostExecution()
    var stepsBlock: (StepsBlock.() -> Unit)? = null

    /**
     * This function defines the steps for the stage.
     *
     * @param block A block of code to define the steps.
     */
    fun steps(block: StepsBlock.() -> Unit) {
        stepsBlock = block
    }

    /**
     * Defines a block of code to execute after all stages have been executed.
     *
     * @param block The block of code to execute.
     */
    fun post(block: PostExecutionBlock.() -> Unit) {
        postExecution = PostExecutionBlock().apply(block).build()
    }


}