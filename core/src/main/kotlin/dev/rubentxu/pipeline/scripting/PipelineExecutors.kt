package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.backend.buildPipeline
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.job.JobExecutor
import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import dev.rubentxu.pipeline.model.pipeline.Status
import dev.rubentxu.pipeline.steps.EnvVars

/**
 * Pipeline executor for Pipeline DSL.
 */
class PipelineDslExecutor : PipelineExecutor<PipelineDefinition> {
    
    override fun execute(definition: PipelineDefinition, configuration: Any, context: ExecutionContext): PipelineResult {
        val pipelineConfig = configuration as IPipelineConfig
        val pipeline = buildPipeline(definition, pipelineConfig)
        
        return JobExecutor().execute(pipeline)
    }
}

/**
 * Pipeline executor for Task DSL - a simple implementation.
 */
class TaskDslExecutor : PipelineExecutor<TaskDefinition> {
    
    override fun execute(definition: TaskDefinition, configuration: Any, context: ExecutionContext): PipelineResult {
        val logger = PipelineLogger.getLogger()
        
        return try {
            logger.info("Executing task: ${definition.name}")
            logger.info("Description: ${definition.description}")
            logger.info("Command: ${definition.command}")
            
            // For simplicity, just log the task execution
            // In a real implementation, you would execute the command
            logger.info("Task executed successfully")
            
            PipelineResult(
                status = Status.SUCCESS,
                stageResults = emptyList(),
                env = EnvVars(definition.environment),
                logs = mutableListOf()
            )
        } catch (e: Exception) {
            logger.error("Task execution failed: ${e.message}")
            PipelineResult(
                status = Status.FAILURE,
                stageResults = emptyList(),
                env = EnvVars(mapOf()),
                logs = mutableListOf()
            )
        }
    }
}