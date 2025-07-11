package dev.rubentxu.pipeline.backend.execution

import dev.rubentxu.pipeline.backend.execution.impl.DefaultConfigurationLoader
import dev.rubentxu.pipeline.backend.execution.impl.DefaultPipelineExecutor
import dev.rubentxu.pipeline.backend.execution.impl.DefaultScriptEvaluator
import dev.rubentxu.pipeline.backend.execution.impl.DockerAgentManager
import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Orchestrates the execution of pipelines by coordinating different components.
 */
class PipelineOrchestrator(
    private val scriptEvaluator: ScriptEvaluator = DefaultScriptEvaluator(),
    private val configLoader: ConfigurationLoader = DefaultConfigurationLoader(),
    private val pipelineExecutor: PipelineExecutor = DefaultPipelineExecutor(),
    private val agentManagers: List<AgentManager> = listOf(DockerAgentManager()),
    private val logger: IPipelineLogger = PipelineLogger.getLogger()
) {
    
    /**
     * Executes a pipeline from script and configuration files.
     */
    fun execute(
        scriptPath: Path,
        configPath: Path,
        context: ExecutionContext
    ): Result<PipelineResult> {
        
        // 1. Evaluate script
        val pipelineDefResult = scriptEvaluator.evaluate(scriptPath)
        if (pipelineDefResult.isFailure) {
            val error = pipelineDefResult.exceptionOrNull()!!
            logger.error("Failed to evaluate script: ${error.message}")
            return Result.failure(PipelineExecutionException("Script evaluation failed", error))
        }
        val pipelineDef = pipelineDefResult.getOrThrow()
        logger.system("Pipeline definition: $pipelineDef")
        
        // 2. Load configuration
        val configResult = configLoader.load(configPath)
        if (configResult.isFailure) {
            val error = configResult.exceptionOrNull()!!
            logger.error("Failed to load configuration: ${error.message}")
            return Result.failure(PipelineExecutionException("Configuration loading failed", error))
        }
        val configuration = configResult.getOrThrow()
        
        // 3. Build pipeline
        val pipelineResult = buildPipeline(pipelineDef, configuration)
        if (pipelineResult.isFailure) {
            val error = pipelineResult.exceptionOrNull()!!
            logger.error("Failed to build pipeline: ${error.message}")
            return Result.failure(PipelineExecutionException("Pipeline building failed", error))
        }
        val pipeline = pipelineResult.getOrThrow()
        logger.system("Built Pipeline: $pipeline")
        
        // 4. Execute pipeline based on agent type and context
        return if (!(pipeline.agent is AnyAgent) && !context.isAgentEnvironment) {
            executeWithAgent(pipeline, configuration, listOf(scriptPath, configPath))
        } else {
            pipelineExecutor.execute(pipeline, configuration)
        }
    }
    
    private fun buildPipeline(
        pipelineDef: PipelineDefinition, 
        configuration: dev.rubentxu.pipeline.model.config.IPipelineConfig
    ): Result<Pipeline> {
        return try {
            val pipeline = runBlocking {
                pipelineDef.build(configuration)
            }
            Result.success(pipeline)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun executeWithAgent(
        pipeline: Pipeline,
        config: dev.rubentxu.pipeline.model.PipelineConfig,
        files: List<Path>
    ): Result<PipelineResult> {
        val agent = pipeline.agent
        logger.info("Executing pipeline with agent: ${agent::class.simpleName}")
        
        val manager = agentManagers.find { it.canHandle(agent) }
            ?: return Result.failure(
                AgentManagerNotFoundException("No agent manager found for agent type: ${agent::class.simpleName}")
            )
        
        return manager.execute(pipeline, config, files)
    }
}

/**
 * Exception thrown when pipeline execution fails.
 */
class PipelineExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when no agent manager is found for a specific agent type.
 */
class AgentManagerNotFoundException(message: String) : Exception(message)