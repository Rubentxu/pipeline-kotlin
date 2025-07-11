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
            logger.error("Failed to evaluate script: ${pipelineDefResult.exceptionOrNull()?.message}")
            return Result.failure(pipelineDefResult.exceptionOrNull()!!)
        }
        val pipelineDef = pipelineDefResult.getOrThrow()
        logger.system("Pipeline definition: $pipelineDef")
        
        // 2. Load configuration
        val configResult = configLoader.load(configPath)
        if (configResult.isFailure) {
            logger.error("Failed to load configuration: ${configResult.exceptionOrNull()?.message}")
            return Result.failure(configResult.exceptionOrNull()!!)
        }
        val configuration = configResult.getOrThrow()
        
        // 3. Build pipeline
        val pipeline = buildPipeline(pipelineDef, configuration)
        logger.system("Built Pipeline: $pipeline")
        
        // 4. Execute pipeline based on agent type and context
        return if (!(pipeline.agent is AnyAgent) && !context.isAgentEnvironment) {
            executeWithAgent(pipeline, configuration, listOf(scriptPath, configPath))
        } else {
            pipelineExecutor.execute(pipeline, configuration)
        }
    }
    
    private fun buildPipeline(pipelineDef: PipelineDefinition, configuration: dev.rubentxu.pipeline.model.config.IPipelineConfig): Pipeline = runBlocking {
        pipelineDef.build(configuration)
    }
    
    private fun executeWithAgent(
        pipeline: Pipeline,
        config: dev.rubentxu.pipeline.model.PipelineConfig,
        files: List<Path>
    ): Result<PipelineResult> {
        val agent = pipeline.agent
        logger.info("Executing pipeline with agent: ${agent::class.simpleName}")
        
        val manager = agentManagers.find { it.canHandle(agent) }
            ?: return Result.failure(IllegalStateException("No agent manager found for agent type: ${agent::class.simpleName}"))
        
        return manager.execute(pipeline, config, files)
    }
}