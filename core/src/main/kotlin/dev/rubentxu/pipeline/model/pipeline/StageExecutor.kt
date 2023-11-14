package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.dsl.PipelineDsl
import dev.rubentxu.pipeline.dsl.StageBlock
import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.logger.PipelineLogger

/**
 * This class represents a stage in a pipeline.
 *
 * @property name The name of the stage.
 * @property block A block of code to run in the stage.
 */
@PipelineDsl
class StageExecutor(val name: String, val block: suspend StageBlock.() -> Any) {

    val logger = PipelineLogger.getLogger()
    /**
     * This function runs the stage.
     *
     * @param pipeline The pipeline to run the stage in.
     */
    suspend fun run(pipeline: Pipeline) : Any {

        val dsl = StageBlock(name, pipeline)
        val steps = StepsBlock(pipeline)
        var result: Any = ""
        try {
            result =  dsl.block()
        } catch (e: Exception) {
            logger.error("Error running stage $name, ${e.message}")
            dsl.stagePostExecutionBlock.failureFunc.invoke(steps)
            throw e
        }
        dsl.stagePostExecutionBlock.successFunc.invoke(steps)
        dsl.stagePostExecutionBlock.alwaysFunc.invoke(steps)
        return result
    }
}