package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.pipeline.PipelineResult

interface PipelineExecutor<T> {
    fun execute(definition: T, config: PipelineConfig): Result<PipelineResult>
}