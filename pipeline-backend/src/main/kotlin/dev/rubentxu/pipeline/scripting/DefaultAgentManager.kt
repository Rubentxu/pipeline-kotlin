package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.model.job.JobExecutor
import dev.rubentxu.pipeline.backend.executeWithAgent
import dev.rubentxu.pipeline.backend.buildPipeline
import java.nio.file.Path

class DefaultAgentManager : AgentManager<PipelineDefinition> {
    
    override fun canHandle(context: ExecutionContext): Boolean {
        return context.dslType == "pipeline"
    }
    
    override fun execute(definition: PipelineDefinition, config: PipelineConfig, files: List<Path>): Result<PipelineResult> {
        return try {
            val pipeline = buildPipeline(definition, config)
            
            val isAgentEnv = System.getenv("IS_AGENT")
            val result = if (!(pipeline.agent is AnyAgent) && isAgentEnv == null) {
                executeWithAgent(pipeline, config, files)
            } else {
                JobExecutor().execute(pipeline)
            }
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}