package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.model.job.JobExecutor
import kotlinx.coroutines.runBlocking
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
                // This will need to be delegated to the executeWithAgent function
                // For now, let's keep it simple and just execute locally
                JobExecutor().execute(pipeline)
            } else {
                JobExecutor().execute(pipeline)
            }
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Builds the pipeline using coroutines.
    private fun buildPipeline(pipelineDef: PipelineDefinition, configuration: PipelineConfig): Pipeline = runBlocking {
        pipelineDef.build(configuration)
    }
}