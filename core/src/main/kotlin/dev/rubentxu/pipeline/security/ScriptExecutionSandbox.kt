package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.DslExecutionContext
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration

/**
 * Interface for secure script execution sandboxes.
 *
 * ScriptExecutionSandbox provides a secure, isolated environment for executing potentially
 * untrusted scripts with comprehensive security controls, resource monitoring, and threat
 * detection. This interface abstracts different sandboxing implementations while maintaining
 * consistent security guarantees across the pipeline execution framework.
 *
 * ## Security Model
 * The sandbox enforces multiple layers of security:
 * - **Isolation**: Scripts run in isolated contexts (processes, threads, or VMs)
 * - **Resource Limits**: CPU, memory, and time constraints prevent resource exhaustion
 * - **Access Control**: File system and network access restrictions
 * - **Monitoring**: Real-time tracking of resource usage and behavior
 * - **Termination**: Forceful shutdown capabilities for runaway scripts
 *
 * ## Supported Implementations
 * - **GraalVM Isolate**: Thread-level isolation with JavaScript execution
 * - **Process Level**: Complete process isolation with separate JVM instances
 * - **Custom Sandboxes**: Pluggable architecture for specialized environments
 *
 * ## Usage Example
 * ```kotlin
 * val sandbox = GraalVMIsolateSandbox()
 * val context = DslExecutionContext.builder()
 *     .memoryLimit(128 * 1024 * 1024) // 128MB
 *     .timeLimit(30_000) // 30 seconds
 *     .build()
 *
 * val result = sandbox.executeInSandbox<String>(
 *     scriptContent = "println('Hello, secure world!')",
 *     scriptName = "greeting.kts",
 *     executionContext = context,
 *     compilationConfig = defaultCompilationConfig,
 *     evaluationConfig = defaultEvaluationConfig
 * )
 *
 * when (result) {
 *     is SandboxExecutionResult.Success -> {
 *         println("Script executed successfully: ${result.result}")
 *         println("Resource usage: ${result.resourceUsage.toHumanReadable()}")
 *     }
 *     is SandboxExecutionResult.Failure -> {
 *         println("Script execution failed: ${result.reason}")
 *         handleSecurityViolation(result.error)
 *     }
 * }
 * ```
 *
 * ## Security Considerations
 * - All scripts are considered potentially malicious until proven otherwise
 * - Resource limits are enforced strictly to prevent denial-of-service attacks
 * - File system access is restricted to designated working directories
 * - Network access can be completely disabled or restricted to specific hosts
 * - Execution timeouts prevent infinite loops and hanging scripts
 *
 * ## Integration with Pipeline Framework
 * The sandbox integrates with the pipeline execution system through:
 * - [SandboxManager] for centralized sandbox coordination
 * - [DslExecutionContext] for security policy configuration
 * - [Pipeline] for step-level security enforcement
 * - [PluginSecurityValidator] for plugin validation
 *
 * @since 1.0.0
 * @see SandboxManager
 * @see GraalVMIsolateSandbox
 * @see ProcessLevelSandbox
 * @see SecurityViolationException
 */
interface ScriptExecutionSandbox {
    
    /**
     * Execute a script within an isolated sandbox environment.
     *
     * This method provides the core functionality for secure script execution. The script
     * is executed in a controlled environment with enforced resource limits, access controls,
     * and comprehensive monitoring. The execution is synchronous and will block until
     * completion, timeout, or security violation.
     *
     * ## Security Enforcement
     * The method enforces the following security policies:
     * - **Resource Limits**: Memory, CPU, and time constraints from the execution context
     * - **File Access**: Restricted to working directory and approved paths
     * - **Network Access**: Configurable network restrictions or complete isolation
     * - **System Access**: Prevents access to system resources and native libraries
     * - **Reflection**: Controlled reflection access based on security policy
     *
     * ## Error Handling
     * The method handles various failure scenarios:
     * - **Compilation Errors**: Syntax errors and dependency issues
     * - **Runtime Exceptions**: Unhandled exceptions during execution
     * - **Security Violations**: Unauthorized access attempts
     * - **Resource Exhaustion**: Memory, CPU, or time limit exceeded
     * - **Timeout**: Execution exceeds allowed time limits
     *
     * ## Performance Considerations
     * - Sandbox initialization has overhead; reuse sandboxes when possible
     * - Resource monitoring adds minimal performance impact
     * - Isolation level affects performance (process > thread > in-memory)
     * - Script compilation is cached when possible
     *
     * ## Example Usage
     * ```kotlin
     * val result = executeInSandbox<String>(
     *     scriptContent = """
     *         val message = "Hello from sandbox"
     *         println(message)
     *         message
     *     """,
     *     scriptName = "test-script.kts",
     *     executionContext = context,
     *     compilationConfig = standardCompilationConfig,
     *     evaluationConfig = standardEvaluationConfig
     * )
     * ```
     *
     * @param T The expected return type of the script execution
     * @param scriptContent The script source code to execute. Must be valid Kotlin script syntax.
     * @param scriptName Identifier for the script used for logging, tracking, and error reporting
     * @param executionContext Execution context containing security policies, resource limits,
     *                         and environment configuration
     * @param compilationConfig Kotlin script compilation configuration including dependencies,
     *                          imports, and compilation options
     * @param evaluationConfig Script evaluation configuration including implicit receivers,
     *                         variables, and evaluation policies
     * @return [SandboxExecutionResult] containing either successful execution result with
     *         resource usage metrics or failure information with error details
     * @throws SecurityViolationException if a security policy violation is detected
     * @see SandboxExecutionResult
     * @see DslExecutionContext
     * @since 1.0.0
     */
    fun <T> executeInSandbox(
        scriptContent: String,
        scriptName: String,
        executionContext: DslExecutionContext,
        compilationConfig: ScriptCompilationConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration
    ): SandboxExecutionResult<T>
    
    /**
     * Forcefully terminate script execution in a specific isolation context.
     *
     * This method provides emergency termination capabilities for runaway scripts,
     * infinite loops, or security violations. It attempts to gracefully stop the
     * execution first, then escalates to forceful termination if necessary.
     *
     * ## Termination Process
     * 1. **Graceful Shutdown**: Attempt to signal the script to stop execution
     * 2. **Resource Cleanup**: Release allocated resources and handles
     * 3. **Forceful Termination**: Kill the execution context if graceful shutdown fails
     * 4. **Isolation Cleanup**: Clean up the isolation environment
     *
     * ## Use Cases
     * - **Timeout Enforcement**: When scripts exceed their allotted execution time
     * - **Resource Protection**: When scripts consume excessive system resources
     * - **Security Response**: When malicious behavior is detected
     * - **User Cancellation**: When users manually cancel long-running operations
     *
     * ## Implementation Notes
     * - Thread-based sandboxes use thread interruption and context cancellation
     * - Process-based sandboxes use process termination signals
     * - Some termination attempts may fail due to system limitations
     * - Cleanup is performed regardless of termination success
     *
     * @param isolationId The unique identifier of the isolation context to terminate.
     *                    This ID is provided in [SandboxExecutionResult] responses.
     * @return `true` if termination was successful and the context was stopped,
     *         `false` if termination failed or the context was not found
     * @see SandboxExecutionResult
     * @since 1.0.0
     */
    fun terminateExecution(isolationId: String): Boolean
    
    /**
     * Get current resource usage for an active isolation context.
     *
     * This method provides real-time monitoring of resource consumption for
     * actively executing scripts. It's essential for implementing resource
     * limits, performance monitoring, and security compliance.
     *
     * ## Resource Metrics
     * The returned metrics include:
     * - **Memory Usage**: Current memory consumption in bytes
     * - **CPU Time**: Total CPU time consumed since script start
     * - **Wall Time**: Elapsed real-world time since script start
     * - **Thread Count**: Number of threads created by the script
     * - **File Access**: List of files accessed by the script
     * - **Network Activity**: Network connections initiated by the script
     *
     * ## Monitoring Use Cases
     * - **Performance Analysis**: Understanding script resource requirements
     * - **Limit Enforcement**: Checking if scripts are approaching resource limits
     * - **Security Auditing**: Tracking file and network access patterns
     * - **Cost Optimization**: Identifying resource-intensive operations
     * - **Debugging**: Diagnosing performance issues in scripts
     *
     * ## Implementation Notes
     * - Resource tracking has minimal performance overhead
     * - Metrics are updated in real-time during script execution
     * - Some metrics may be approximate due to system limitations
     * - Inactive contexts are automatically cleaned up
     *
     * @param isolationId The unique identifier of the isolation context to monitor.
     *                    This ID is provided in [SandboxExecutionResult] responses.
     * @return [SandboxResourceUsage] containing current resource metrics, or
     *         `null` if the context is not found or no longer active
     * @see SandboxResourceUsage
     * @since 1.0.0
     */
    fun getResourceUsage(isolationId: String): SandboxResourceUsage?
    
    /**
     * Clean up all resources and shut down the sandbox.
     *
     * This method performs comprehensive cleanup of all sandbox resources,
     * including active executions, isolation contexts, and system resources.
     * It should be called when the sandbox is no longer needed to prevent
     * resource leaks and ensure proper system cleanup.
     *
     * ## Cleanup Process
     * 1. **Active Executions**: Terminate all running scripts gracefully
     * 2. **Isolation Contexts**: Clean up all isolation environments
     * 3. **System Resources**: Release threads, processes, and file handles
     * 4. **Memory**: Free allocated memory and clear caches
     * 5. **Network**: Close open network connections
     * 6. **Monitoring**: Stop resource monitoring threads
     *
     * ## Usage Guidelines
     * - Call this method when shutting down the application
     * - Use try-with-resources pattern when possible
     * - Don't call sandbox methods after cleanup
     * - Multiple calls to cleanup are safe but unnecessary
     *
     * ## Implementation Notes
     * - Cleanup is performed in reverse order of initialization
     * - Failed cleanup attempts are logged but don't throw exceptions
     * - Background threads are given time to terminate gracefully
     * - Forceful cleanup is used if graceful cleanup fails
     *
     * @since 1.0.0
     */
    fun cleanup()
}

/**
 * Result of a sandbox execution operation.
 *
 * This sealed class represents the outcome of executing a script within a sandbox
 * environment. It provides a type-safe way to handle both successful and failed
 * executions, with comprehensive metadata for monitoring and debugging.
 *
 * ## Result Types
 * - [Success]: Script executed successfully with result and resource usage
 * - [Failure]: Script execution failed with error details and context
 *
 * ## Usage Pattern
 * ```kotlin
 * when (val result = sandbox.executeInSandbox<String>(...)) {
 *     is SandboxExecutionResult.Success -> {
 *         println("Script result: ${result.result}")
 *         println("Execution time: ${result.executionTime}ms")
 *         logResourceUsage(result.resourceUsage)
 *     }
 *     is SandboxExecutionResult.Failure -> {
 *         logger.error("Script failed: ${result.reason}", result.error)
 *         handleFailure(result.error, result.isolationId)
 *     }
 * }
 * ```
 *
 * @param T The type of the script execution result
 * @since 1.0.0
 * @see ScriptExecutionSandbox.executeInSandbox
 */
sealed class SandboxExecutionResult<T> {
    
    /**
     * Successful execution result containing the script output and execution metadata.
     *
     * This result indicates that the script executed successfully without security
     * violations, resource limit violations, or runtime errors. It includes the
     * actual script result, resource usage metrics, and execution timing.
     *
     * @param result The actual result returned by the script execution
     * @param isolationId Unique identifier for the isolation context used
     * @param resourceUsage Detailed resource consumption metrics
     * @param executionTime Total execution time in milliseconds
     * @since 1.0.0
     */
    data class Success<T>(
        val result: T,
        val isolationId: String,
        val resourceUsage: SandboxResourceUsage,
        val executionTime: Long
    ) : SandboxExecutionResult<T>()
    
    /**
     * Failed execution result containing error information and context.
     *
     * This result indicates that the script execution failed due to compilation
     * errors, runtime exceptions, security violations, or resource limit violations.
     * It includes the error details and context for debugging and recovery.
     *
     * @param error The exception or error that caused the execution to fail
     * @param isolationId Unique identifier for the isolation context used
     * @param reason Human-readable description of the failure reason
     * @since 1.0.0
     */
    data class Failure<T>(
        val error: Throwable,
        val isolationId: String,
        val reason: String
    ) : SandboxExecutionResult<T>()
}

/**
 * Resource usage metrics for sandbox execution.
 *
 * This data class provides comprehensive resource consumption metrics for
 * script execution within a sandbox environment. It's essential for monitoring,
 * debugging, and enforcing resource limits in secure script execution.
 *
 * ## Metric Categories
 * - **Memory**: RAM consumption in bytes
 * - **CPU**: Processing time consumed
 * - **Time**: Wall-clock execution time
 * - **Threads**: Concurrency resource usage
 * - **Files**: File system access patterns
 * - **Network**: Network connectivity usage
 *
 * ## Usage Example
 * ```kotlin
 * val usage = result.resourceUsage
 * println("Memory used: ${usage.memoryUsedBytes / 1024 / 1024} MB")
 * println("CPU time: ${usage.cpuTimeMs} ms")
 * println("Files accessed: ${usage.filesAccessed.joinToString()}")
 * println("Human readable: ${usage.toHumanReadable()}")
 * ```
 *
 * @param memoryUsedBytes Total memory consumed by the script in bytes
 * @param cpuTimeMs Total CPU time consumed by the script in milliseconds
 * @param wallTimeMs Total wall-clock time from start to completion in milliseconds
 * @param threadsCreated Number of threads created during script execution
 * @param filesAccessed List of file paths accessed during execution
 * @param networkConnections List of network endpoints contacted during execution
 * @since 1.0.0
 */
data class SandboxResourceUsage(
    val memoryUsedBytes: Long,
    val cpuTimeMs: Long,
    val wallTimeMs: Long,
    val threadsCreated: Int,
    val filesAccessed: List<String>,
    val networkConnections: List<String>
) {
    
    /**
     * Convert resource usage metrics to human-readable format.
     *
     * This method provides a formatted string representation of the resource
     * usage metrics, suitable for logging, debugging, or user display.
     *
     * ## Format
     * The output includes:
     * - Memory usage in megabytes
     * - CPU time in milliseconds
     * - Wall time in milliseconds
     * - Thread count
     * - File access count
     * - Network connection count
     *
     * @return Multi-line string with formatted resource usage information
     * @since 1.0.0
     */
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
 * Security violation detected during script execution.
 *
 * This exception is thrown when a script attempts to perform an operation
 * that violates the security policies of the sandbox environment. It provides
 * detailed information about the violation type, context, and affected resources.
 *
 * ## Common Scenarios
 * - **Resource Exhaustion**: Script exceeds memory, CPU, or time limits
 * - **Unauthorized Access**: Attempts to access restricted files or network
 * - **Malicious Behavior**: Suspicious patterns detected in script execution
 * - **Policy Violations**: Direct violations of configured security policies
 *
 * ## Exception Handling
 * ```kotlin
 * try {
 *     sandbox.executeInSandbox(script, ...)
 * } catch (e: SecurityViolationException) {
 *     when (e.violationType) {
 *         SecurityViolationType.RESOURCE_LIMIT_EXCEEDED -> {
 *             logger.warn("Script exceeded resource limits: ${e.message}")
 *             notifyResourceExhaustion(e.isolationId)
 *         }
 *         SecurityViolationType.UNAUTHORIZED_FILE_ACCESS -> {
 *             logger.error("Unauthorized file access: ${e.message}")
 *             blockScript(e.isolationId)
 *         }
 *         // Handle other violation types...
 *     }
 * }
 * ```
 *
 * @param message Detailed description of the security violation
 * @param violationType The specific type of security violation that occurred
 * @param isolationId Unique identifier of the isolation context where the violation occurred
 * @param cause Optional underlying cause of the security violation
 * @since 1.0.0
 * @see SecurityViolationType
 */
class SecurityViolationException(
    message: String,
    val violationType: SecurityViolationType,
    val isolationId: String,
    cause: Throwable? = null
) : SecurityException(message, cause)

/**
 * Types of security violations that can be detected during script execution.
 *
 * This enumeration defines the different categories of security violations
 * that the sandbox system can detect and respond to. Each violation type
 * corresponds to a specific security policy or resource limit.
 *
 * ## Violation Categories
 * - **Resource Violations**: Limits on computational resources
 * - **Access Violations**: Unauthorized access to system resources
 * - **Behavioral Violations**: Suspicious or malicious behavior patterns
 * - **Policy Violations**: Direct violations of configured security policies
 *
 * ## Response Strategies
 * Different violation types typically warrant different response strategies:
 * - **RESOURCE_LIMIT_EXCEEDED**: Graceful degradation or termination
 * - **UNAUTHORIZED_ACCESS**: Immediate termination and security alert
 * - **MALICIOUS_CODE_DETECTED**: Security incident response
 * - **EXECUTION_TIMEOUT**: Cleanup and resource recovery
 *
 * @since 1.0.0
 * @see SecurityViolationException
 */
enum class SecurityViolationType {
    /** Script has exceeded configured resource limits (memory, CPU, time) */
    RESOURCE_LIMIT_EXCEEDED,
    
    /** Script attempted to access files outside the allowed directory tree */
    UNAUTHORIZED_FILE_ACCESS,
    
    /** Script attempted to make network connections to unauthorized hosts */
    UNAUTHORIZED_NETWORK_ACCESS,
    
    /** Script attempted to access system resources or native libraries */
    UNAUTHORIZED_SYSTEM_ACCESS,
    
    /** Script exhibited patterns consistent with malicious behavior */
    MALICIOUS_CODE_DETECTED,
    
    /** Script execution exceeded the configured timeout period */
    EXECUTION_TIMEOUT
}