package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.utils.buildPipeline
import dev.rubentxu.pipeline.backend.executeWithAgent
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.steps.EnvVars
import java.nio.file.Path

/**
 * Agent manager for Pipeline DSL.
 */
class PipelineAgentManager : AgentManager<PipelineDefinition> {
    
    override fun requiresAgent(definition: PipelineDefinition): Boolean {
        // Check environment variable as in the original code
        val isAgentEnv = System.getenv("IS_AGENT")
        return isAgentEnv == null
    }
    
    override fun executeWithAgent(definition: PipelineDefinition, configuration: Any, paths: List<Path>): PipelineResult {
        val pipelineConfig = configuration as IPipelineConfig
        val pipeline = buildPipeline(definition, pipelineConfig)
        
        // Check if pipeline actually needs an agent (not AnyAgent)
        if (pipeline.agent is AnyAgent) {
            // No agent needed, execute directly
            return dev.rubentxu.pipeline.model.job.JobExecutor().execute(pipeline)
        }
        
        return executeWithAgent(pipeline, pipelineConfig, paths)
    }
}

/**
 * Agent manager for Task DSL - simple implementation that doesn't support agents.
 */
class TaskAgentManager : AgentManager<TaskDefinition> {
    
    override fun requiresAgent(definition: TaskDefinition): Boolean {
        // Task DSL doesn't support agents in this simple implementation
        return false
    }
    
    override fun executeWithAgent(definition: TaskDefinition, configuration: Any, paths: List<Path>): PipelineResult {
        // Task DSL doesn't support agents, so return failure
        return PipelineResult(
            status = Status.FAILURE,
            stageResults = emptyList(),
            env = EnvVars(mapOf()),
            logs = mutableListOf()
        )
    }
}