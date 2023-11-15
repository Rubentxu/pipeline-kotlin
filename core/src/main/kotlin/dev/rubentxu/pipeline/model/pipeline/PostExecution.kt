package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.logger.PipelineLogger


class PostExecution(
    val alwaysFunc: (suspend StepsBlock.() -> Any)? = null,
    val successFunc: (suspend StepsBlock.() -> Any)? = null,
    val failureFunc: (suspend StepsBlock.() -> Any)? = null,
    ) {
    val logger = PipelineLogger.getLogger()
    suspend fun run(pipeline: Pipeline, results: List<StageResult>) {
        val steps = StepsBlock(pipeline)
        logger.system("Pipeline finished with status: ${results.map { it.status }}")
        if (results.any { it.status == Status.Failure }) {
            failureFunc?.invoke(steps)
        } else {
            successFunc?.invoke(steps)
        }

        alwaysFunc?.invoke(steps)
    }

}