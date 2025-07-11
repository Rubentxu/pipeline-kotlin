package dev.rubentxu.pipeline.backend.execution.impl

import dev.rubentxu.pipeline.backend.agent.docker.ContainerLifecycleManager
import dev.rubentxu.pipeline.backend.agent.docker.DockerConfigManager
import dev.rubentxu.pipeline.backend.agent.docker.DockerImageBuilder
import dev.rubentxu.pipeline.backend.execution.AgentManager
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.steps.EnvVars
import java.nio.file.Path

/**
 * Agent manager for Docker-based pipeline execution.
 */
class DockerAgentManager : AgentManager {
    
    private val logger = PipelineLogger.getLogger()
    
    override fun canHandle(agent: Agent): Boolean {
        return agent is DockerAgent
    }
    
    override fun execute(pipeline: Pipeline, config: PipelineConfig, files: List<Path>): Result<PipelineResult> {
        return try {
            val agent = pipeline.agent as DockerAgent
            
            logger.info("Executing pipeline in Docker agent: ${agent.image}:${agent.tag}")
            
            val dockerClientProvider = DockerConfigManager(agent)
            val imageBuilder = DockerImageBuilder(dockerClientProvider)
            val containerManager = ContainerLifecycleManager(dockerClientProvider)
            
            val imageId = imageBuilder.buildCustomImage("${agent.image}:${agent.tag}", files)
            containerManager.createAndStartContainer(mapOf("IS_AGENT" to "true"))
            
            // For now, return a success result - actual execution would be more complex
            Result.success(PipelineResult(Status.SUCCESS, emptyList(), EnvVars(mapOf()), mutableListOf()))
        } catch (e: Exception) {
            logger.error("Error executing pipeline in Docker agent: ${e.message}")
            Result.failure(e)
        }
    }
}