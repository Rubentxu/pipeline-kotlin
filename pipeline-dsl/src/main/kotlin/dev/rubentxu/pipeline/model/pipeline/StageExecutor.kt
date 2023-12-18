package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.dsl.PipelineDsl
import dev.rubentxu.pipeline.dsl.StageBlock
import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.model.jobs.StageResult
import dev.rubentxu.pipeline.model.jobs.Status
import dev.rubentxu.pipeline.model.logger.PipelineLogger

/**
 * This class represents a stage in a pipeline.
 *
 * @property name The name of the stage.
 * @property block A block of code to run in the stage.
 */
@PipelineDsl
class StageExecutor(val name: String, val block: suspend StageBlock.() -> Any) {

    val logger = PipelineLogger.getLogger()
    var postExecution: PostExecution? = null

    /**
     * This function runs the stage.
     *
     * @param pipeline The pipeline to run the stage in.
     */
    suspend fun run(pipeline: Pipeline): Any {
        var status: Status = Status.Success
        var errorMessage = ""
        val dsl = StageBlock(name, pipeline)
//        val steps = StepsBlock(pipeline)
        var result: Any = ""
        try {
            dsl.block()
            postExecution = dsl.postExecution
            val stepsBlock: (StepsBlock.() -> Unit)? = dsl.stepsBlock
            if (stepsBlock != null) {
                result = executeSteps(stepsBlock, pipeline)
            }

        } catch (e: Exception) {
            status = Status.Failure
            errorMessage = e.message ?: ""
            logger.error("Error running stage $name, ${e.message}")
            throw e
        } finally {
            postExecution?.run(pipeline, listOf(StageResult(name, status, "", errorMessage)))
        }
        return result
    }

    fun executeSteps(block: StepsBlock.() -> Unit, pipeline: Pipeline) {
        val steps = StepsBlock()
        steps.block()
    }
}