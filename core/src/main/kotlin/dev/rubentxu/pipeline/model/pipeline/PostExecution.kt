package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.logger.PipelineLogger

/**
 * Represents post-execution actions for a pipeline stage.
 *
 * Allows defining actions to be executed always, on success, or on failure after stage execution.
 *
 * @property alwaysFunc Lambda to be executed always after the stage, regardless of the result.
 * @property successFunc Lambda to be executed only if the stage succeeds.
 * @property failureFunc Lambda to be executed only if the stage fails.
 */
class PostExecution(
    val alwaysFunc: (suspend StepsBlock.() -> Any)? = null,
    val successFunc: (suspend StepsBlock.() -> Any)? = null,
    val failureFunc: (suspend StepsBlock.() -> Any)? = null,
) {
    /**
     * Logger instance for post-execution actions.
     */
    val logger = PipelineLogger.getLogger()

    /**
     * Executes the appropriate post-execution actions based on the stage results.
     *
     * @param pipeline The pipeline instance.
     * @param results The list of stage results.
     */
    suspend fun run(pipeline: Pipeline, results: List<StageResult>) {
        val steps = StepsBlock(pipeline)
        logger.system("Pipeline finished with status: ${results.map { it.status }}")
        if (results.any { it.status == Status.FAILURE }) {
            failureFunc?.invoke(steps)
        } else {
            successFunc?.invoke(steps)
        }

        alwaysFunc?.invoke(steps)
    }

}