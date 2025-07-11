package dev.rubentxu.pipeline.backend.execution

import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineResult

/**
 * Interface for executing pipelines.
 */
interface PipelineExecutor {
    /**
     * Executes a pipeline with the given configuration.
     *
     * @param pipeline The pipeline to execute
     * @param config The pipeline configuration
     * @return Result containing the pipeline result or an error
     */
    fun execute(pipeline: Pipeline, config: PipelineConfig): Result<PipelineResult>
}