package dev.rubentxu.pipeline.core.pipeline

import dev.rubentxu.pipeline.core.dsl.StepsBlock
import dev.rubentxu.pipeline.core.interfaces.IPipelineContext
import dev.rubentxu.pipeline.core.jobs.StageResult
import dev.rubentxu.pipeline.core.jobs.Status


class PostExecution(
    val alwaysFunc: (suspend StepsBlock.() -> Any)? = null,
    val successFunc: (suspend StepsBlock.() -> Any)? = null,
    val failureFunc: (suspend StepsBlock.() -> Any)? = null,
) {

    suspend fun run(context: IPipelineContext, results: List<StageResult>) {
        val steps = StepsBlock(context)
        if (results.any { it.status == Status.Failure }) {
            failureFunc?.invoke(steps)
        } else {
            successFunc?.invoke(steps)
        }

        alwaysFunc?.invoke(steps)
    }

}