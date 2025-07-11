package dev.rubentxu.pipeline.backend.execution.impl

import dev.rubentxu.pipeline.backend.execution.PipelineExecutor
import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.job.JobExecutor
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineResult

/**
 * Default implementation of PipelineExecutor using JobExecutor.
 */
class DefaultPipelineExecutor : PipelineExecutor {
    
    private val jobExecutor = JobExecutor()
    
    override fun execute(pipeline: Pipeline, config: PipelineConfig): Result<PipelineResult> {
        return try {
            val result = jobExecutor.execute(pipeline)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}