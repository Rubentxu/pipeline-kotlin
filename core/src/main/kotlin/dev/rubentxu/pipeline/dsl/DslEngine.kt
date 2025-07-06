package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.compilation.ScriptCompilationCache
import dev.rubentxu.pipeline.events.EventBus
import dev.rubentxu.pipeline.logger.IPipelineLogger
import kotlinx.coroutines.flow.Flow
import java.io.File
import kotlin.script.experimental.api.*

/**
 * Generic DSL engine interface that can execute different types of Kotlin DSLs.
 * This allows the system to be extended with third-party DSL libraries while
 * maintaining consistent security, caching, and execution patterns.
 */
interface DslEngine<TResult : Any> {
    
    /**
     * Unique identifier for this DSL engine type.
     */
    val engineId: String
    
    /**
     * Human-readable name for this DSL engine.
     */
    val engineName: String
    
    /**
     * Version of this DSL engine.
     */
    val engineVersion: String
    
    /**
     * File extensions that this engine can handle.
     */
    val supportedExtensions: Set<String>
    
    /**
     * Compiles a DSL script from file.
     */
    suspend fun compile(
        scriptFile: File,
        context: DslCompilationContext
    ): DslCompilationResult<TResult>
    
    /**
     * Compiles a DSL script from string content.
     */
    suspend fun compile(
        scriptContent: String,
        scriptName: String,
        context: DslCompilationContext
    ): DslCompilationResult<TResult>
    
    /**
     * Executes a compiled DSL script.
     */
    suspend fun execute(
        compiledScript: CompiledScript,
        context: DslExecutionContext
    ): DslExecutionResult<TResult>
    
    /**
     * Compiles and executes a DSL script in one operation.
     */
    suspend fun compileAndExecute(
        scriptFile: File,
        compilationContext: DslCompilationContext,
        executionContext: DslExecutionContext
    ): DslExecutionResult<TResult>
    
    /**
     * Validates DSL script syntax without full compilation.
     */
    suspend fun validate(
        scriptContent: String,
        context: DslCompilationContext
    ): DslValidationResult
    
    /**
     * Gets metadata about this DSL engine.
     */
    fun getEngineInfo(): DslEngineInfo
    
    /**
     * Creates a default compilation context for this DSL.
     */
    fun createDefaultCompilationContext(): DslCompilationContext
    
    /**
     * Creates a default execution context for this DSL.
     */
    fun createDefaultExecutionContext(): DslExecutionContext
}

/**
 * Context for DSL compilation containing configuration and dependencies.
 */
data class DslCompilationContext(
    val classPath: List<File> = emptyList(),
    val imports: Set<String> = emptySet(),
    val configuration: Map<String, Any> = emptyMap(),
    val allowedPackages: Set<String> = emptySet(),
    val blockedPackages: Set<String> = emptySet(),
    val enableCaching: Boolean = true,
    val securityPolicy: DslSecurityPolicy = DslSecurityPolicy.DEFAULT
)

/**
 * Context for DSL execution containing runtime configuration.
 */
data class DslExecutionContext(
    val variables: Map<String, Any> = emptyMap(),
    val workingDirectory: File = File(System.getProperty("user.dir")),
    val environmentVariables: Map<String, String> = emptyMap(),
    val timeout: Long? = null,
    val resourceLimits: DslResourceLimits? = null,
    val executionPolicy: DslExecutionPolicy = DslExecutionPolicy()
)

/**
 * Security policy for DSL execution.
 */
data class DslSecurityPolicy(
    val allowNetworkAccess: Boolean = false,
    val allowFileSystemAccess: Boolean = true,
    val allowedDirectories: Set<File> = emptySet(),
    val allowReflection: Boolean = false,
    val allowNativeCode: Boolean = false,
    val sandboxEnabled: Boolean = true
) {
    companion object {
        val DEFAULT = DslSecurityPolicy()
        val RESTRICTED = DslSecurityPolicy(
            allowNetworkAccess = false,
            allowFileSystemAccess = false,
            allowReflection = false,
            allowNativeCode = false,
            sandboxEnabled = true
        )
        val PERMISSIVE = DslSecurityPolicy(
            allowNetworkAccess = true,
            allowFileSystemAccess = true,
            allowReflection = true,
            allowNativeCode = false,
            sandboxEnabled = false
        )
    }
}

/**
 * Resource limits for DSL execution.
 */
data class DslResourceLimits(
    val maxMemoryMb: Int? = null,
    val maxCpuTimeMs: Long? = null,
    val maxWallTimeMs: Long? = null,
    val maxThreads: Int? = null,
    val maxFileHandles: Int? = null
)

/**
 * Execution policy for DSL scripts.
 */
data class DslExecutionPolicy(
    val isolationLevel: DslIsolationLevel = DslIsolationLevel.THREAD,
    val allowConcurrentExecution: Boolean = true,
    val persistResults: Boolean = false,
    val enableEventPublishing: Boolean = true
)

/**
 * Isolation levels for DSL execution.
 */
enum class DslIsolationLevel {
    NONE,           // No isolation
    THREAD,         // Thread-level isolation
    CLASSLOADER,    // ClassLoader isolation
    PROCESS,        // Process isolation
    CONTAINER       // Container isolation
}

/**
 * Result of DSL compilation.
 */
sealed class DslCompilationResult<out TResult> {
    data class Success<TResult>(
        val compiledScript: CompiledScript,
        val metadata: DslCompilationMetadata
    ) : DslCompilationResult<TResult>()
    
    data class Failure(
        val errors: List<DslError>,
        val warnings: List<DslWarning> = emptyList()
    ) : DslCompilationResult<Nothing>()
}

/**
 * Result of DSL execution.
 */
sealed class DslExecutionResult<out TResult> {
    data class Success<TResult>(
        val result: TResult,
        val metadata: DslExecutionMetadata
    ) : DslExecutionResult<TResult>()
    
    data class Failure(
        val error: DslError,
        val metadata: DslExecutionMetadata? = null
    ) : DslExecutionResult<Nothing>()
}

/**
 * Result of DSL validation.
 */
sealed class DslValidationResult {
    object Valid : DslValidationResult()
    data class Invalid(
        val errors: List<DslError>,
        val warnings: List<DslWarning> = emptyList()
    ) : DslValidationResult()
}

/**
 * Metadata about DSL compilation.
 */
data class DslCompilationMetadata(
    val compilationTimeMs: Long,
    val cacheHit: Boolean,
    val dependenciesResolved: Int,
    val warningsCount: Int,
    val compiledAt: java.time.Instant = java.time.Instant.now()
)

/**
 * Metadata about DSL execution.
 */
data class DslExecutionMetadata(
    val executionTimeMs: Long,
    val memoryUsedMb: Long? = null,
    val threadsUsed: Int? = null,
    val eventsPublished: Int = 0,
    val executedAt: java.time.Instant = java.time.Instant.now()
)

/**
 * Information about a DSL engine.
 */
data class DslEngineInfo(
    val engineId: String,
    val engineName: String,
    val version: String,
    val description: String,
    val author: String,
    val supportedExtensions: Set<String>,
    val capabilities: Set<DslCapability>,
    val dependencies: List<String> = emptyList()
)

/**
 * Capabilities that a DSL engine might support.
 */
enum class DslCapability {
    COMPILATION_CACHING,
    SYNTAX_VALIDATION,
    TYPE_CHECKING,
    CODE_COMPLETION,
    DEBUGGING,
    HOT_RELOAD,
    INCREMENTAL_COMPILATION,
    PARALLEL_EXECUTION,
    PERSISTENCE,
    EVENT_STREAMING
}

/**
 * DSL-specific error information.
 */
data class DslError(
    val code: String,
    val message: String,
    val location: DslLocation? = null,
    val cause: Throwable? = null,
    val severity: DslSeverity = DslSeverity.ERROR
)

/**
 * DSL-specific warning information.
 */
data class DslWarning(
    val code: String,
    val message: String,
    val location: DslLocation? = null,
    val severity: DslSeverity = DslSeverity.WARNING
)

/**
 * Location information within a DSL script.
 */
data class DslLocation(
    val line: Int,
    val column: Int,
    val file: String? = null
)

/**
 * Severity levels for DSL diagnostics.
 */
enum class DslSeverity {
    INFO,
    WARNING,
    ERROR,
    FATAL
}

/**
 * Registry for managing multiple DSL engines.
 */
interface DslEngineRegistry {
    
    /**
     * Registers a DSL engine.
     */
    fun <TResult : Any> registerEngine(engine: DslEngine<TResult>)
    
    /**
     * Unregisters a DSL engine.
     */
    fun unregisterEngine(engineId: String)
    
    /**
     * Gets a DSL engine by ID.
     */
    fun <TResult : Any> getEngine(engineId: String): DslEngine<TResult>?
    
    /**
     * Gets a DSL engine that can handle the given file extension.
     */
    fun <TResult : Any> getEngineForExtension(extension: String): DslEngine<TResult>?
    
    /**
     * Gets all registered DSL engines.
     */
    fun getAllEngines(): List<DslEngine<*>>
    
    /**
     * Gets engines that support a specific capability.
     */
    fun getEnginesWithCapability(capability: DslCapability): List<DslEngine<*>>
}

/**
 * Base execution context that provides common services to all DSL engines.
 */
interface ExecutionContext {
    val logger: IPipelineLogger
    val eventBus: EventBus
    val compilationCache: ScriptCompilationCache
    val workingDirectory: File
    val environmentVariables: Map<String, String>
    
    /**
     * Creates a scoped execution context for a specific DSL execution.
     */
    fun createScopedContext(
        dslType: String,
        executionId: String,
        additionalVariables: Map<String, Any> = emptyMap()
    ): ScopedExecutionContext
}

/**
 * Scoped execution context for a specific DSL execution instance.
 */
interface ScopedExecutionContext : ExecutionContext {
    val dslType: String
    val executionId: String
    val startTime: java.time.Instant
    val additionalVariables: Map<String, Any>
    
    /**
     * Publishes an execution event.
     */
    suspend fun publishEvent(event: dev.rubentxu.pipeline.events.DomainEvent)
    
    /**
     * Gets execution statistics.
     */
    fun getExecutionStats(): DslExecutionStats
}


/**
 * Statistics for DSL execution.
 */
data class DslExecutionStats(
    val dslType: String,
    val executionId: String,
    val startTime: java.time.Instant,
    val endTime: java.time.Instant? = null,
    val compilationTimeMs: Long? = null,
    val executionTimeMs: Long? = null,
    val memoryUsedMb: Long? = null,
    val eventsPublished: Int = 0,
    val errorsCount: Int = 0,
    val warningsCount: Int = 0
)