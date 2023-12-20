package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.jobs.StageResult
import dev.rubentxu.pipeline.model.jobs.Status
import dev.rubentxu.pipeline.model.logger.PipelineLogger


class PostExecution(
    val alwaysFunc: (suspend StepsBlock.() -> Any)? = null,
    val successFunc: (suspend StepsBlock.() -> Any)? = null,
    val failureFunc: (suspend StepsBlock.() -> Any)? = null,
) {
    val logger = PipelineLogger.getLogger()
    suspend fun run(context: IPipelineContext, results: List<StageResult>) {
        val steps = StepsBlock(context)
        logger.system("Pipeline finished with status: ${results.map { it.status }}")
        if (results.any { it.status == Status.Failure }) {
            failureFunc?.invoke(steps)
        } else {
            successFunc?.invoke(steps)
        }

        alwaysFunc?.invoke(steps)
    }

}