package dev.rubentxu.pipeline.dsl

/**
 * This class represents a stage in a pipeline.
 *
 * @property name The name of the stage.
 * @property block A block of code to run in the stage.
 */
@PipelineDsl
class Stage(val name: String, val block: suspend StageDsl.() -> Any) {

    /**
     * This function runs the stage.
     *
     * @param pipeline The pipeline to run the stage in.
     */
    suspend fun run(pipeline: Pipeline) : Any {

        val dsl = StageDsl(name, pipeline)
        val steps = StepBlock(pipeline)
        var result: Any = ""
        try {
            result =  dsl.block()
        } catch (e: Exception) {
            pipeline.logger.error("Error running stage $name, ${e.message}")
            dsl.stagePost.failureFunc.invoke(steps)
            throw e
        }
        dsl.stagePost.successFunc.invoke(steps)
        dsl.stagePost.alwaysFunc.invoke(steps)
        return result
    }
}