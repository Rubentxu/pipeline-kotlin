package dev.rubentxu.pipeline.context

import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.logger.IPipelineLogger
import java.nio.file.Path

/**
 * Central context interface for @Step functions, similar to Composer in Jetpack Compose.
 * 
 * This interface provides all the capabilities needed by @Step functions while maintaining
 * security boundaries and controlled access to pipeline resources.
 * 
 * Unlike StepExecutionContext which is more focused on execution scope,
 * PipelineContext is designed for the @Step system and provides richer functionality.
 */
interface PipelineContext {
    
    // Core pipeline access
    val pipeline: Pipeline
    val logger: IPipelineLogger
    val workingDirectory: Path
    val environment: Map<String, String>
    
    // Step execution capabilities
    suspend fun executeShell(command: String, options: ShellOptions = ShellOptions()): ShellResult
    suspend fun executeStep(stepName: String, config: Map<String, Any> = emptyMap()): Any
    
    // File operations
    suspend fun readFile(path: String, encoding: String = "UTF-8"): String
    suspend fun writeFile(path: String, content: String, encoding: String = "UTF-8")
    suspend fun fileExists(path: String): Boolean
    
    // Environment and configuration
    fun getEnvVar(name: String): String?
    fun getEnvVar(name: String, defaultValue: String): String
    fun getSecret(name: String): String?
    
    // State management (similar to Compose remember)
    fun <T> remember(key: Any, computation: () -> T): T
    fun invalidate()
    
    // Security and resource management
    val securityLevel: SecurityLevel
    val resourceLimits: ResourceLimits
    
    // Step registration and discovery
    fun getAvailableSteps(): List<String>
    fun getStepMetadata(stepName: String): StepMetadata?
    
    // Pipeline context hierarchy (similar to CompositionLocal)
    fun <T> provide(key: ContextKey<T>, value: T, block: () -> Unit)
    fun <T> consume(key: ContextKey<T>): T?
}

/**
 * Shell execution options
 */
data class ShellOptions(
    val returnStdout: Boolean = false,
    val workingDir: String? = null,
    val timeout: Long? = null,
    val environment: Map<String, String> = emptyMap(),
    val ignoreExitCode: Boolean = false
)

/**
 * Shell execution result
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean = exitCode == 0
)

/**
 * Security levels for step execution
 */
enum class SecurityLevel {
    TRUSTED,    // Full access to pipeline capabilities
    RESTRICTED, // Limited access with resource constraints
    ISOLATED    // Minimal access, maximum sandboxing
}

/**
 * Resource limits for step execution
 */
data class ResourceLimits(
    val maxMemoryMB: Long = 512,
    val maxCpuTimeSeconds: Long = 300,
    val maxWallTimeSeconds: Long = 600,
    val maxFileDescriptors: Int = 100
)

/**
 * Metadata about a step
 */
data class StepMetadata(
    val name: String,
    val description: String,
    val category: String,
    val parameters: List<ParameterMetadata>,
    val securityLevel: SecurityLevel
)

/**
 * Metadata about a step parameter
 */
data class ParameterMetadata(
    val name: String,
    val type: String,
    val required: Boolean,
    val defaultValue: Any? = null,
    val description: String = ""
)

/**
 * Context key for typed context values
 */
class ContextKey<T>(val name: String) {
    override fun toString(): String = "ContextKey($name)"
}

/**
 * Current PipelineContext provider, similar to CompositionLocal.current
 */
object LocalPipelineContext {
    private val contextThreadLocal = ThreadLocal<PipelineContext?>()
    
    val current: PipelineContext
        get() = contextThreadLocal.get() ?: error("No PipelineContext in scope")
    
    internal fun runWith(context: PipelineContext, block: () -> Unit) {
        val previous = contextThreadLocal.get()
        try {
            contextThreadLocal.set(context)
            block()
        } finally {
            contextThreadLocal.set(previous)
        }
    }
    
    internal suspend fun runWithSuspend(context: PipelineContext, block: suspend () -> Unit) {
        val previous = contextThreadLocal.get()
        try {
            contextThreadLocal.set(context)
            block()
        } finally {
            contextThreadLocal.set(previous)
        }
    }
}