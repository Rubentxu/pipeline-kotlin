package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.DslExecutionContext
import dev.rubentxu.pipeline.dsl.DslIsolationLevel
import dev.rubentxu.pipeline.logger.IPipelineLogger
import kotlinx.coroutines.*
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Central manager for script execution sandboxes
 * 
 * Coordinates different sandbox implementations and provides unified security management
 */
class SandboxManager(
    private val logger: IPipelineLogger
) {
    
    private val graalVMSandbox = GraalVMIsolateSandbox(logger)
    private val processLevelSandbox = ProcessLevelSandbox(logger)
    
    private val activeSandboxes = ConcurrentHashMap<String, ScriptExecutionSandbox>()
    private val executionCounter = AtomicLong(0)
    
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + supervisorJob)
    
    /**
     * Execute a script with appropriate sandbox based on isolation level
     */
    suspend fun <T> executeSecurely(
        scriptContent: String,
        scriptName: String,
        executionContext: DslExecutionContext,
        compilationConfig: ScriptCompilationConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration
    ): SandboxExecutionResult<T> = withContext(Dispatchers.Default) {
        
        val executionId = executionCounter.incrementAndGet()
        val fullScriptName = "$scriptName-$executionId"
        
        logger.info("Starting secure execution for script: $fullScriptName")
        
        val sandbox = selectSandbox(executionContext)
        activeSandboxes[fullScriptName] = sandbox
        
        try {
            val result = if (executionContext.resourceLimits?.maxWallTimeMs != null) {
                // Execute with timeout
                withTimeout(executionContext.resourceLimits.maxWallTimeMs!!) {
                    sandbox.executeInSandbox<T>(
                        scriptContent, fullScriptName, executionContext, 
                        compilationConfig, evaluationConfig
                    )
                }
            } else {
                // Execute without timeout
                sandbox.executeInSandbox<T>(
                    scriptContent, fullScriptName, executionContext,
                    compilationConfig, evaluationConfig
                )
            }
            
            logger.info("Secure execution completed for script: $fullScriptName")
            result
            
        } catch (e: TimeoutCancellationException) {
            logger.error("Script execution timed out: $fullScriptName")
            sandbox.terminateExecution(fullScriptName)
            SandboxExecutionResult.Failure(
                error = SecurityViolationException(
                    "Script execution timed out after ${executionContext.resourceLimits?.maxWallTimeMs}ms",
                    SecurityViolationType.EXECUTION_TIMEOUT,
                    fullScriptName,
                    e
                ),
                isolationId = fullScriptName,
                reason = "Execution timeout"
            )
        } catch (e: Exception) {
            logger.error("Secure execution failed for script $fullScriptName: ${e.message}")
            SandboxExecutionResult.Failure(
                error = e,
                isolationId = fullScriptName,
                reason = "Execution failed: ${e.message}"
            )
        } finally {
            activeSandboxes.remove(fullScriptName)
        }
    }
    
    /**
     * Terminate execution of a specific script
     */
    fun terminateExecution(scriptName: String): Boolean {
        val sandbox = activeSandboxes[scriptName]
        return if (sandbox != null) {
            logger.info("Terminating execution for script: $scriptName")
            sandbox.terminateExecution(scriptName)
        } else {
            logger.warn("No active execution found for script: $scriptName")
            false
        }
    }
    
    /**
     * Get resource usage for an active script execution
     */
    fun getResourceUsage(scriptName: String): SandboxResourceUsage? {
        val sandbox = activeSandboxes[scriptName]
        return sandbox?.getResourceUsage(scriptName)
    }
    
    /**
     * Get status of all active executions
     */
    fun getActiveExecutions(): Map<String, SandboxResourceUsage?> {
        return activeSandboxes.mapValues { (scriptName, sandbox) ->
            sandbox.getResourceUsage(scriptName)
        }
    }
    
    /**
     * Validate security policy before execution
     */
    fun validateSecurityPolicy(executionContext: DslExecutionContext): SecurityPolicyValidation {
        val issues = mutableListOf<String>()
        
        // Validate resource limits
        executionContext.resourceLimits?.let { limits ->
            if (limits.maxMemoryMb != null && limits.maxMemoryMb!! > 2048) {
                issues.add("Memory limit exceeds maximum allowed (2048MB): ${limits.maxMemoryMb}MB")
            }
            
            if (limits.maxCpuTimeMs != null && limits.maxCpuTimeMs!! > 300_000) {
                issues.add("CPU time limit exceeds maximum allowed (5 minutes): ${limits.maxCpuTimeMs}ms")
            }
            
            if (limits.maxThreads != null && limits.maxThreads!! > 10) {
                issues.add("Thread limit exceeds maximum allowed (10): ${limits.maxThreads}")
            }
        }
        
        // Validate isolation level capabilities
        when (executionContext.executionPolicy.isolationLevel) {
            DslIsolationLevel.PROCESS -> {
                // Highest security - all restrictions apply
                if (executionContext.environmentVariables.any { it.key.startsWith("SYSTEM_") }) {
                    issues.add("System environment variables not allowed in PROCESS isolation level")
                }
            }
            DslIsolationLevel.THREAD -> {
                // Medium security - some restrictions
                if (executionContext.environmentVariables.size > 50) {
                    issues.add("Too many environment variables for THREAD isolation level: ${executionContext.environmentVariables.size}")
                }
            }
            else -> {
                // Lower security levels - fewer restrictions
            }
        }
        
        return SecurityPolicyValidation(
            isValid = issues.isEmpty(),
            issues = issues
        )
    }
    
    /**
     * Clean up all sandbox resources
     */
    fun shutdown() {
        logger.info("Shutting down sandbox manager - ${activeSandboxes.size} active executions")
        
        // Terminate all active executions
        activeSandboxes.keys.forEach { scriptName ->
            terminateExecution(scriptName)
        }
        
        // Clean up sandbox implementations
        try {
            graalVMSandbox.cleanup()
            processLevelSandbox.cleanup()
        } catch (e: Exception) {
            logger.error("Error during sandbox cleanup: ${e.message}")
        }
        
        // Cancel coroutine scope
        supervisorJob.cancel()
        
        logger.info("Sandbox manager shutdown complete")
    }
    
    private fun selectSandbox(executionContext: DslExecutionContext): ScriptExecutionSandbox {
        return when (executionContext.executionPolicy.isolationLevel) {
            DslIsolationLevel.PROCESS -> {
                logger.debug("Selected process-level sandbox for high security execution")
                processLevelSandbox
            }
            DslIsolationLevel.THREAD -> {
                logger.debug("Selected GraalVM isolate sandbox for medium security execution")
                graalVMSandbox
            }
            else -> {
                logger.debug("Selected GraalVM isolate sandbox for standard execution")
                graalVMSandbox
            }
        }
    }
}

/**
 * Result of security policy validation
 */
data class SecurityPolicyValidation(
    val isValid: Boolean,
    val issues: List<String>
) {
    fun throwIfInvalid() {
        if (!isValid) {
            throw SecurityException("Security policy validation failed: ${issues.joinToString(", ")}")
        }
    }
}