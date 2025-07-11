package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import java.nio.file.Path

interface AgentManager<T> {
    fun canHandle(context: ExecutionContext): Boolean
    fun execute(definition: T, config: PipelineConfig, files: List<Path>): Result<PipelineResult>
}