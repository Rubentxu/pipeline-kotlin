package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.DslExecutionContext
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration

/**
 * Interface for secure script execution sandboxes
 * 
 * Provides isolation and security controls for executing potentially untrusted scripts
 */
interface ScriptExecutionSandbox {
    
    /**
     * Execute a script within an isolated sandbox environment
     * 
     * @param scriptContent The script source code to execute
     * @param scriptName Identifier for the script (for logging and tracking)
     * @param executionContext Execution context with security and resource settings
     * @param compilationConfig Script compilation configuration
     * @param evaluationConfig Script evaluation configuration
     * @return Result of the sandbox execution
     */
    fun <T> executeInSandbox(
        scriptContent: String,
        scriptName: String,
        executionContext: DslExecutionContext,
        compilationConfig: ScriptCompilationConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration
    ): SandboxExecutionResult<T>
    
    /**
     * Forcefully terminate script execution in a specific isolation context
     * 
     * @param isolationId The isolation context identifier
     * @return true if termination was successful, false otherwise
     */
    fun terminateExecution(isolationId: String): Boolean
    
    /**
     * Get current resource usage for an active isolation context
     * 
     * @param isolationId The isolation context identifier
     * @return Current resource usage or null if context not found
     */
    fun getResourceUsage(isolationId: String): SandboxResourceUsage?
    
    /**
     * Clean up all resources and shut down the sandbox
     */
    fun cleanup()
}

/**
 * Result of a sandbox execution
 */
sealed class SandboxExecutionResult<T> {
    
    /**
     * Successful execution result
     */
    data class Success<T>(
        val result: T,
        val isolationId: String,
        val resourceUsage: SandboxResourceUsage,
        val executionTime: Long
    ) : SandboxExecutionResult<T>()
    
    /**
     * Failed execution result
     */
    data class Failure<T>(
        val error: Throwable,
        val isolationId: String,
        val reason: String
    ) : SandboxExecutionResult<T>()
}

/**
 * Resource usage metrics for sandbox execution
 */
data class SandboxResourceUsage(
    val memoryUsedBytes: Long,
    val cpuTimeMs: Long,
    val wallTimeMs: Long,
    val threadsCreated: Int,
    val filesAccessed: List<String>,
    val networkConnections: List<String>
) {
    
    fun toHumanReadable(): String {
        return buildString {
            appendLine("Memory: ${memoryUsedBytes / 1024 / 1024} MB")
            appendLine("CPU Time: ${cpuTimeMs} ms")
            appendLine("Wall Time: ${wallTimeMs} ms")
            appendLine("Threads: $threadsCreated")
            appendLine("Files Accessed: ${filesAccessed.size}")
            appendLine("Network Connections: ${networkConnections.size}")
        }
    }
}

/**
 * Security violation detected during script execution
 */
class SecurityViolationException(
    message: String,
    val violationType: SecurityViolationType,
    val isolationId: String,
    cause: Throwable? = null
) : SecurityException(message, cause)

/**
 * Types of security violations that can be detected
 */
enum class SecurityViolationType {
    RESOURCE_LIMIT_EXCEEDED,
    UNAUTHORIZED_FILE_ACCESS,
    UNAUTHORIZED_NETWORK_ACCESS,
    UNAUTHORIZED_SYSTEM_ACCESS,
    MALICIOUS_CODE_DETECTED,
    EXECUTION_TIMEOUT
}