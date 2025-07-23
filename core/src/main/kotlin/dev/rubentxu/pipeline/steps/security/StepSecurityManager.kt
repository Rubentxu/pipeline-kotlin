package dev.rubentxu.pipeline.steps.security

import dev.rubentxu.pipeline.context.PipelineContext
import dev.rubentxu.pipeline.context.ResourceLimits
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.security.SandboxManager
import dev.rubentxu.pipeline.annotations.StepMetadata
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Security manager for @Step function execution.
 * 
 * This class integrates the existing SandboxManager with the new @Step system,
 * providing security enforcement for step execution based on SecurityLevel
 * and ResourceLimits defined in the PipelineContext.
 * 
 * Similar to how @Composable functions have compile-time restrictions,
 * @Step functions have runtime security enforcement.
 */
class StepSecurityManager(
    private val logger: ILogger,
    private val sandboxManager: SandboxManager = SandboxManager(logger)
) {
    
    private val activeStepExecutions = ConcurrentHashMap<String, StepExecution>()
    private val executionCounter = AtomicLong(0)
    
    /**
     * Executes a @Step function with appropriate security constraints
     */
    suspend fun <T> executeStepSecurely(
        stepName: String,
        stepMetadata: StepMetadata,
        context: PipelineContext,
        stepFunction: suspend () -> T
    ): T {
        val executionId = "step-${executionCounter.incrementAndGet()}"
        val execution = StepExecution(
            id = executionId,
            stepName = stepName,
            securityLevel = stepMetadata.securityLevel,
            resourceLimits = context.resourceLimits,
            startTime = System.currentTimeMillis()
        )
        
        activeStepExecutions[executionId] = execution
        
        try {
            logger.info("Starting secure execution of step '$stepName' (${stepMetadata.securityLevel})")
            
            return when (stepMetadata.securityLevel) {
                dev.rubentxu.pipeline.annotations.SecurityLevel.TRUSTED -> {
                    // Trusted steps run with minimal restrictions
                    executeTrusted(stepFunction, execution)
                }
                dev.rubentxu.pipeline.annotations.SecurityLevel.RESTRICTED -> {
                    // Restricted steps run with resource limits and monitoring
                    executeRestricted(stepFunction, execution, context)
                }
                dev.rubentxu.pipeline.annotations.SecurityLevel.ISOLATED -> {
                    // Isolated steps run in maximum sandbox
                    executeIsolated(stepFunction, execution, context)
                }
            }
        } finally {
            activeStepExecutions.remove(executionId)
            execution.endTime = System.currentTimeMillis()
            logExecutionMetrics(execution)
        }
    }
    
    /**
     * Execute trusted step with minimal restrictions
     */
    private suspend fun <T> executeTrusted(
        stepFunction: suspend () -> T,
        execution: StepExecution
    ): T {
        return withTimeout(execution.resourceLimits.maxWallTimeSeconds * 1000L) {
            stepFunction()
        }
    }
    
    /**
     * Execute restricted step with resource monitoring
     */
    private suspend fun <T> executeRestricted(
        stepFunction: suspend () -> T,
        execution: StepExecution,
        context: PipelineContext
    ): T {
        val resourceMonitor = ResourceMonitor(execution.resourceLimits, logger)
        
        return withContext(Dispatchers.Default) {
            withTimeout(execution.resourceLimits.maxWallTimeSeconds * 1000L) {
                resourceMonitor.startMonitoring()
                try {
                    stepFunction()
                } finally {
                    resourceMonitor.stopMonitoring()
                }
            }
        }
    }
    
    /**
     * Execute isolated step in maximum sandbox
     */
    private suspend fun <T> executeIsolated(
        stepFunction: suspend () -> T,
        execution: StepExecution,
        context: PipelineContext
    ): T {
        // TODO: Implement full isolation when DSL execution context is available
        // For now, execute with monitoring only
        
        return withContext(Dispatchers.Default) {
            // Apply strict resource limits and monitoring
            val resourceMonitor = ResourceMonitor(execution.resourceLimits, logger)
            
            withTimeout(execution.resourceLimits.maxWallTimeSeconds * 1000L) {
                resourceMonitor.startMonitoring()
                try {
                    stepFunction()
                } finally {
                    resourceMonitor.stopMonitoring()
                }
            }
        }
    }
    
    /**
     * Get allowed packages for a step based on its security requirements
     */
    private fun getAllowedPackagesForStep(stepName: String): List<String> {
        return when (stepName) {
            "sh", "echo" -> listOf(
                "dev.rubentxu.pipeline.context",
                "kotlinx.coroutines",
                "kotlin.collections",
                "kotlin.text"
            )
            "readFile", "writeFile", "fileExists" -> listOf(
                "dev.rubentxu.pipeline.context",
                "java.nio.file",
                "java.io",
                "kotlin.text"
            )
            else -> listOf(
                "dev.rubentxu.pipeline.context",
                "kotlin.collections",
                "kotlin.text"
            )
        }
    }
    
    /**
     * Get current step execution statistics
     */
    fun getExecutionStatistics(): StepExecutionStatistics {
        val activeCount = activeStepExecutions.size
        val executions = activeStepExecutions.values.toList()
        
        return StepExecutionStatistics(
            activeExecutions = activeCount,
            longestRunningExecution = executions.maxByOrNull { 
                System.currentTimeMillis() - it.startTime 
            }?.let { it.stepName to (System.currentTimeMillis() - it.startTime) },
            totalMemoryUsage = executions.sumOf { it.resourceLimits.maxMemoryMB },
            securityLevelDistribution = executions.groupingBy { it.securityLevel }.eachCount()
        )
    }
    
    /**
     * Validates if a step can be executed based on current resource constraints
     */
    fun validateStepExecution(
        stepMetadata: StepMetadata,
        context: PipelineContext
    ): StepSecurityValidation {
        val errors = mutableListOf<String>()
        
        // Check resource limits
        val stats = getExecutionStatistics()
        val newMemoryUsage = stats.totalMemoryUsage + context.resourceLimits.maxMemoryMB
        
        if (newMemoryUsage > 2048) { // Global memory limit
            errors.add("Insufficient memory available for step execution")
        }
        
        if (stats.activeExecutions > 10) { // Max concurrent steps
            errors.add("Too many concurrent step executions")
        }
        
        // Check security level constraints
        if (stepMetadata.securityLevel == dev.rubentxu.pipeline.annotations.SecurityLevel.ISOLATED && 
            stats.securityLevelDistribution[dev.rubentxu.pipeline.annotations.SecurityLevel.ISOLATED] ?: 0 > 2) {
            errors.add("Too many isolated steps running concurrently")
        }
        
        return if (errors.isEmpty()) {
            StepSecurityValidation.Valid
        } else {
            StepSecurityValidation.Invalid(errors)
        }
    }
    
    /**
     * Logs execution metrics for monitoring and debugging
     */
    private fun logExecutionMetrics(execution: StepExecution) {
        val duration = execution.endTime - execution.startTime
        logger.info("Step '${execution.stepName}' completed in ${duration}ms " +
                   "(Security: ${execution.securityLevel}, Memory limit: ${execution.resourceLimits.maxMemoryMB}MB)")
    }
    
    /**
     * Cleanup resources and stop all active executions
     */
    fun shutdown() {
        logger.info("Shutting down StepSecurityManager...")
        activeStepExecutions.clear()
    }
}

/**
 * Represents an active step execution
 */
data class StepExecution(
    val id: String,
    val stepName: String,
    val securityLevel: dev.rubentxu.pipeline.annotations.SecurityLevel,
    val resourceLimits: ResourceLimits,
    val startTime: Long,
    var endTime: Long = 0L
)

/**
 * Statistics about step executions
 */
data class StepExecutionStatistics(
    val activeExecutions: Int,
    val longestRunningExecution: Pair<String, Long>?,
    val totalMemoryUsage: Long,
    val securityLevelDistribution: Map<dev.rubentxu.pipeline.annotations.SecurityLevel, Int>
)

/**
 * Security validation result for step execution
 */
sealed class StepSecurityValidation {
    object Valid : StepSecurityValidation()
    data class Invalid(override val errors: List<String>) : StepSecurityValidation()
    
    val isValid: Boolean get() = this is Valid
    open val errors: List<String> get() = emptyList()
}

/**
 * Resource monitor for step execution
 */
private class ResourceMonitor(
    private val limits: ResourceLimits,
    private val logger: ILogger
) {
    private var monitoring = false
    private var monitoringJob: Job? = null
    
    fun startMonitoring() {
        monitoring = true
        monitoringJob = CoroutineScope(Dispatchers.Default).launch {
            while (monitoring) {
                delay(1000) // Check every second
                
                // In a real implementation, this would check:
                // - Current memory usage
                // - CPU usage
                // - File descriptor count
                // - Network connections
                // And enforce limits accordingly
                
                val currentMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                if (currentMemory > limits.maxMemoryMB * 1024 * 1024) {
                    logger.warn("Step approaching memory limit: ${currentMemory / 1024 / 1024}MB")
                }
            }
        }
    }
    
    fun stopMonitoring() {
        monitoring = false
        monitoringJob?.cancel()
    }
}