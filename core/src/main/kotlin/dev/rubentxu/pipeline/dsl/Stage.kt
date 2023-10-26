package dev.rubentxu.pipeline.dsl

/**
 * This class represents a stage in a pipeline.
 *
 * @property name The name of the stage.
 * @property block A block of code to run in the stage.
 */
class Stage(val name: String, val block: suspend StageDsl.() -> Any) {

    /**
     * This function runs the stage.
     *
     * @param pipeline The pipeline to run the stage in.
     */
    suspend fun run(pipeline: PipelineDsl) : Any {

        val dsl = StageDsl(pipeline)
        val result = dsl.block()

        return result
    }
}