package dev.rubentxu.pipeline.core.dsl

import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.core.interfaces.IPipelineContext
import dev.rubentxu.pipeline.core.jobs.StageResult
import dev.rubentxu.pipeline.core.jobs.Status
import dev.rubentxu.pipeline.core.pipeline.PostExecution


/**
 * This class represents a stage in a pipeline.
 *
 * @property name The name of the stage.
 * @property block A block of code to run in the stage.
 */
@PipelineDsl
class StageExecutor(val name: String, val block: suspend StageBlock.() -> Any) {


    var postExecution: PostExecution? = null

    /**
     * This function runs the stage.
     *
     * @param context The pipeline to run the stage in.
     */
    suspend fun run(context: IPipelineContext): Any {
        val logger: ILogger = context.getComponent(ILogger::class)
        var status: Status = Status.Success
        var errorMessage = ""
        val dsl = StageBlock(name, context)
//        val steps = StepsBlock(pipeline)
        var result: Any = ""
        try {
            dsl.block()
            postExecution = dsl.postExecution
            val stepsBlock: (StepsBlock.() -> Unit)? = dsl.stepsBlock
            if (stepsBlock != null) {
                result = executeSteps(stepsBlock, context)
            }

        } catch (e: Exception) {
            status = Status.Failure
            errorMessage = e.message ?: ""
            logger.error("StageExecutor", "Error running stage $name, ${e.message}")
            throw e
        } finally {
            postExecution?.run(context, listOf(StageResult(name, status, "", errorMessage)))
        }
        return result
    }

    fun executeSteps(block: StepsBlock.() -> Unit, context: IPipelineContext) {
        val steps = StepsBlock(context)
        steps.block()
    }
}