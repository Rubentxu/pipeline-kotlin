package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.DslExecutionContext
import dev.rubentxu.pipeline.dsl.DslIsolationLevel
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import kotlinx.coroutines.*
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Central manager for script execution sandboxes.
 *
 * SandboxManager provides a unified interface for secure script execution across
 * different sandbox implementations. It automatically selects the appropriate sandbox
 * based on security requirements, manages execution contexts, and provides centralized
 * monitoring and control of all active script executions.
 *
 * ## Key Responsibilities
 * - **Sandbox Selection**: Automatically chooses the appropriate sandbox implementation
 * - **Execution Management**: Coordinates concurrent script executions
 * - **Resource Monitoring**: Tracks active executions and resource usage
 * - **Security Enforcement**: Ensures consistent security policies across sandboxes
 * - **Timeout Management**: Enforces execution time limits
 * - **Lifecycle Management**: Handles sandbox initialization and cleanup
 *
 * ## Sandbox Selection Strategy
 * The manager selects sandboxes based on the isolation level:
 * - **PROCESS**: [ProcessLevelSandbox] for maximum isolation
 * - **THREAD**: [GraalVMIsolateSandbox] for balanced performance and security
 * - **NONE**: Direct execution with minimal security (development only)
 *
 * ## Concurrency Model
 * The manager supports concurrent execution of multiple scripts:
 * - Each script runs in its own isolation context
 * - Resource limits are enforced per-script
 * - Timeout handling is managed centrally
 * - Cleanup is performed automatically
 *
 * ## Usage Example
 * ```kotlin
 * val sandboxManager = SandboxManager(logger)
 * 
 * val context = DslExecutionContext.builder()
 *     .isolationLevel(DslIsolationLevel.PROCESS)
 *     .memoryLimit(128 * 1024 * 1024) // 128MB
 *     .timeLimit(30_000) // 30 seconds
 *     .build()
 *
 * val result = sandboxManager.executeSecurely<String>(
 *     scriptContent = "println('Hello, secure world!')",
 *     scriptName = "greeting.kts",
 *     executionContext = context,
 *     compilationConfig = defaultCompilationConfig,
 *     evaluationConfig = defaultEvaluationConfig
 * )
 * ```
 *
 * ## Security Considerations
 * - All scripts are executed in isolated environments
 * - Resource limits are enforced strictly
 * - Timeout violations result in immediate termination
 * - Active executions are monitored for security violations
 * - Cleanup is performed even on abnormal termination
 *
 * @param logger Pipeline logger for security event logging and monitoring
 * @since 1.0.0
 * @see ScriptExecutionSandbox
 * @see GraalVMIsolateSandbox
 * @see ProcessLevelSandbox
 * @see DslExecutionContext
 */
class SandboxManager(
    private val logger: ILogger
) {
    

    private val processLevelSandbox = ProcessLevelSandbox(logger)
    
    private val activeSandboxes = ConcurrentHashMap<String, ScriptExecutionSandbox>()
    private val executionCounter = AtomicLong(0)
    
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + supervisorJob)
    
    /**
     * Execute a script securely with automatic sandbox selection and resource management.
     *
     * This method provides the primary interface for secure script execution. It automatically
     * selects the appropriate sandbox implementation based on the isolation level, manages
     * execution timeouts, and provides comprehensive error handling and resource cleanup.
     *
     * ## Execution Flow
     * 1. **Sandbox Selection**: Choose sandbox based on isolation level and security requirements
     * 2. **Context Setup**: Create execution context with resource limits and security policies
     * 3. **Timeout Management**: Apply execution time limits if configured
     * 4. **Script Execution**: Execute the script in the selected sandbox
     * 5. **Result Processing**: Handle success/failure and resource cleanup
     * 6. **Cleanup**: Remove from active executions and release resources
     *
     * ## Security Features
     * - **Automatic Sandbox Selection**: Based on isolation level and security requirements
     * - **Resource Limit Enforcement**: Memory, CPU, and time constraints
     * - **Timeout Protection**: Prevents infinite loops and runaway scripts
     * - **Concurrent Execution**: Safe handling of multiple simultaneous scripts
     * - **Exception Handling**: Comprehensive error handling with security context
     *
     * ## Error Handling
     * The method handles various error scenarios:
     * - **Timeout Exceptions**: Scripts that exceed time limits
     * - **Resource Exhaustion**: Scripts that consume too many resources
     * - **Security Violations**: Unauthorized access attempts
     * - **Compilation Errors**: Script syntax and dependency issues
     * - **Runtime Exceptions**: Unhandled exceptions during execution
     *
     * ## Performance Considerations
     * - Sandbox selection is cached for performance
     * - Resource monitoring adds minimal overhead
     * - Concurrent executions are managed efficiently
     * - Cleanup is performed asynchronously when possible
     *
     * @param T The expected return type of the script execution
     * @param scriptContent The script source code to execute
     * @param scriptName Identifier for the script (used for logging and tracking)
     * @param executionContext Execution context with security policies and resource limits
     * @param compilationConfig Kotlin script compilation configuration
     * @param evaluationConfig Script evaluation configuration
     * @return [SandboxExecutionResult] containing either successful execution result or failure details
     * @throws SecurityViolationException if severe security violations are detected
     * @see SandboxExecutionResult
     * @see DslExecutionContext
     * @since 1.0.0
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
                withTimeout(executionContext.resourceLimits.maxWallTimeMs) {
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
            if (limits.maxMemoryMb != null && limits.maxMemoryMb > 2048) {
                issues.add("Memory limit exceeds maximum allowed (2048MB): ${limits.maxMemoryMb}MB")
            }
            
            if (limits.maxCpuTimeMs != null && limits.maxCpuTimeMs > 300_000) {
                issues.add("CPU time limit exceeds maximum allowed (5 minutes): ${limits.maxCpuTimeMs}ms")
            }
            
            if (limits.maxThreads != null && limits.maxThreads > 10) {
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

            else -> {
                logger.debug("Selected GraalVM isolate sandbox for standard execution")
                processLevelSandbox
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