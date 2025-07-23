package dev.rubentxu.pipeline.runner

import dev.rubentxu.pipeline.context.KoinServiceLocator
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.context.managers.interfaces.IWorkspaceManager
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import kotlinx.coroutines.runBlocking

/**
 * Simplified PipelineRunner for Phase 2 - focuses on service integration
 * 
 * This implementation demonstrates how PipelineRunner integrates with the 
 * service initialization system using the powerful KoinServiceLocator.
 */
class PipelineRunner(
    val koinServiceLocator: KoinServiceLocator
) {
    val workspaceManager: IWorkspaceManager = koinServiceLocator.get(IWorkspaceManager::class.java)
    val loggerManager: ILoggerManager = koinServiceLocator.get(ILoggerManager::class.java)
    val environmentManager: IEnvironmentManager = koinServiceLocator.get(IEnvironmentManager::class.java)
    val parameterManager: IParameterManager = koinServiceLocator.get(IParameterManager::class.java)
    
    fun run(): PipelineResult = runBlocking {
        val logger = loggerManager.getLogger("PipelineRunner")
        
        try {
            logger.info("Starting pipeline execution")
            
            // Simulate successful pipeline execution with service access
            val workspace = workspaceManager.current
            logger.info("Using workspace: ${workspace.path}")
            
            // Set some parameters to demonstrate service integration
            parameterManager.set("pipeline_started", "true")
            
            // Demonstrate environment access
            environmentManager.set("PIPELINE_STATUS", "RUNNING")
            
            logger.info("Pipeline completed successfully")
            
            PipelineResult(
                status = Status.SUCCESS,
                stageResults = listOf(
                    StageResult("mock-stage", Status.SUCCESS)
                ),
                logs = listOf("Pipeline executed successfully"),
                errors = emptyList()
            )
            
        } catch (e: Exception) {
            logger.error("Pipeline execution failed: ${e.message}")
            
            PipelineResult(
                status = Status.FAILURE,
                stageResults = listOf(
                    StageResult("mock-stage", Status.FAILURE)
                ),
                logs = listOf("Pipeline execution failed"),
                errors = listOf(e.message ?: "Unknown error")
            )
        }
    }
    
    suspend fun execute(scriptPath: String, workingDirectory: String? = null): PipelineResult {
        val logger = loggerManager.getLogger("PipelineRunner")
        
        return try {
            logger.info("Executing pipeline script: $scriptPath")
            
            // Set working directory if provided
            workingDirectory?.let { 
                // In a real implementation, this would change the workspace
                logger.info("Using working directory: $it")
            }
            
            // For now, simulate successful execution
            // In a real implementation, this would parse and execute the script
            logger.info("Script execution simulated (Phase 2 implementation)")
            
            PipelineResult(
                status = Status.SUCCESS,
                stageResults = listOf(
                    StageResult("script-execution", Status.SUCCESS)
                ),
                logs = listOf("Script executed successfully"),
                errors = emptyList()
            )
            
        } catch (e: Exception) {
            logger.error("Script execution failed: ${e.message}")
            
            PipelineResult(
                status = Status.FAILURE,
                stageResults = listOf(
                    StageResult("script-execution", Status.FAILURE)
                ),
                logs = listOf("Script execution failed"),
                errors = listOf(e.message ?: "Unknown error")
            )
        }
    }
}

/**
 * Simplified status enum for Phase 2
 */
enum class Status {
    SUCCESS, FAILURE, IN_PROGRESS
}

/**
 * Simplified pipeline result for Phase 2
 */
data class PipelineResult(
    val status: Status,
    val stageResults: List<StageResult>,
    val logs: List<String>,
    val errors: List<String>
)

/**
 * Simplified stage result for Phase 2
 */
data class StageResult(
    val stageName: String,
    val status: Status
)