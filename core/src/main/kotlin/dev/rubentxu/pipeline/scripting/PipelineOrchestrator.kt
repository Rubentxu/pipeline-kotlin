package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import java.nio.file.Path

/**
 * Orchestrates the evaluation, configuration loading, and execution of scripts using the generic scripting framework.
 * This class provides a unified interface for handling different DSL types.
 * 
 * @param T The type of definition that will be produced by script evaluation
 */
class PipelineOrchestrator<T>(
    private val evaluatorRegistry: DslEvaluatorRegistry,
    private val executorResolver: ExecutorResolver<T>
) {
    
    /**
     * Executes a script with the provided configuration using the appropriate DSL evaluator.
     * 
     * @param scriptPath Path to the script file
     * @param configPath Path to the configuration file
     * @param dslType The DSL type to use for evaluation
     * @param logger Logger for outputting execution information
     * @return The result of the script execution
     */
    fun executeScript(
        scriptPath: String, 
        configPath: String, 
        dslType: String, 
        logger: PipelineLogger
    ): PipelineResult {
        return try {
            logger.info("Starting script execution with DSL type: $dslType")
            
            // Get the appropriate evaluator for the DSL type
            val evaluator = evaluatorRegistry.getEvaluator<T>(dslType)
            logger.info("Using evaluator: ${evaluator::class.simpleName}")
            
            // Evaluate the script
            val definition = evaluator.evaluate(scriptPath)
            logger.system("Script evaluation completed: $definition")
            
            // Load configuration
            val configLoader = executorResolver.resolveConfigurationLoader(dslType)
            val configuration = configLoader.loadConfiguration(configPath)
            logger.info("Configuration loaded")
            
            // Create execution context
            val context = ExecutionContext(System.getenv(), dslType)
            
            // For pipeline DSL, we need to check agent requirements after building the pipeline
            // This preserves the original logic from PipelineScriptRunner
            if (dslType == "pipeline") {
                val agentManager = executorResolver.resolveAgentManager(dslType)
                if (agentManager.requiresAgent(definition)) {
                    logger.info("Agent execution may be required - delegating to agent manager")
                    val paths = listOf(Path.of(scriptPath), Path.of(configPath))
                    return agentManager.executeWithAgent(definition, configuration, paths)
                }
            }
            
            // Execute directly
            val executor = executorResolver.resolvePipelineExecutor(dslType)
            executor.execute(definition, configuration, context)
            
        } catch (e: Exception) {
            logger.error("Error executing script: ${e.message}")
            e.printStackTrace()
            PipelineResult(
                status = dev.rubentxu.pipeline.model.pipeline.Status.FAILURE, 
                stageResults = emptyList(), 
                env = dev.rubentxu.pipeline.steps.EnvVars(mapOf()), 
                logs = mutableListOf()
            )
        }
    }
}