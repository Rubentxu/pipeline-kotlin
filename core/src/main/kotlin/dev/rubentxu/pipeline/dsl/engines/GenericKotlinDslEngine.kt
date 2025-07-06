package dev.rubentxu.pipeline.dsl.engines

import dev.rubentxu.pipeline.compilation.CachedScriptEngine
import dev.rubentxu.pipeline.compilation.ScriptCompilationCacheFactory
import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Generic DSL Engine that can execute third-party Kotlin DSL libraries.
 * This engine provides a flexible framework for integrating external DSLs
 * while maintaining consistent security and execution patterns.
 */
class GenericKotlinDslEngine(
    override val engineId: String,
    override val engineName: String,
    override val engineVersion: String = "1.0.0",
    override val supportedExtensions: Set<String>,
    private val scriptDefinitionClass: kotlin.reflect.KClass<*>,
    private val implicitReceivers: List<kotlin.reflect.KClass<*>> = emptyList(),
    private val defaultImports: Set<String> = emptySet(),
    private val resultExtractor: (EvaluationResult) -> Any = { result -> 
        when (val returnValue = result.returnValue) {
            is ResultValue.Value -> returnValue.value ?: Unit
            is ResultValue.Unit -> Unit
            is ResultValue.Error -> throw returnValue.error
            else -> Unit
        }
    },
    private val description: String = "Generic Kotlin DSL Engine",
    private val author: String = "Unknown",
    private val dependencies: List<String> = emptyList(),
    private val capabilities: Set<DslCapability> = setOf(
        DslCapability.COMPILATION_CACHING,
        DslCapability.SYNTAX_VALIDATION
    ),
    private val logger: IPipelineLogger = PipelineLogger.getLogger(),
    private val enableCaching: Boolean = true
) : DslEngine<Any> {
    
    private val scriptEngine = CachedScriptEngine(
        cache = if (enableCaching) {
            ScriptCompilationCacheFactory.createProductionCache()
        } else {
            ScriptCompilationCacheFactory.createDevelopmentCache()
        },
        logger = logger
    )
    
    private val baseCompilationConfiguration by lazy {
        createJvmCompilationConfigurationFromTemplate<Any> {
            jvm {
                dependenciesFromCurrentContext(wholeClasspath = true)
            }
            
            // TODO: Add implicit receivers if specified
            // implicitReceivers configuration needs proper implementation
            
            // TODO: Add default imports if specified  
            // defaultImports configuration needs proper implementation
        }
    }
    
    override suspend fun compile(
        scriptFile: File,
        context: DslCompilationContext
    ): DslCompilationResult<Any> = withContext(Dispatchers.IO) {
        val startTime = Instant.now()
        
        try {
            logger.debug("Compiling DSL script: ${scriptFile.name} with engine: $engineId")
            
            val compilationConfig = createCompilationConfiguration(context)
            val result = scriptEngine.compile(scriptFile, compilationConfig)
            
            val compilationTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            
            when (result) {
                is ResultWithDiagnostics.Success -> {
                    val metadata = DslCompilationMetadata(
                        compilationTimeMs = compilationTime,
                        cacheHit = false, // TODO: Get from cache stats
                        dependenciesResolved = 0, // TODO: Count dependencies
                        warningsCount = result.reports.count { 
                            it.severity == ScriptDiagnostic.Severity.WARNING 
                        }
                    )
                    
                    DslCompilationResult.Success(result.value, metadata)
                }
                
                is ResultWithDiagnostics.Failure -> {
                    val errors = result.reports
                        .filter { it.severity == ScriptDiagnostic.Severity.ERROR }
                        .map { convertToDslError(it) }
                    
                    val warnings = result.reports
                        .filter { it.severity == ScriptDiagnostic.Severity.WARNING }
                        .map { convertToDslWarning(it) }
                    
                    DslCompilationResult.Failure(errors, warnings)
                }
            }
        } catch (e: Exception) {
            logger.error("DSL compilation failed for engine $engineId: ${e.message}")
            DslCompilationResult.Failure(
                listOf(DslError("COMPILATION_ERROR", e.message ?: "Unknown compilation error", cause = e))
            )
        }
    }
    
    override suspend fun compile(
        scriptContent: String,
        scriptName: String,
        context: DslCompilationContext
    ): DslCompilationResult<Any> = withContext(Dispatchers.IO) {
        val startTime = Instant.now()
        
        try {
            logger.debug("Compiling DSL script: $scriptName with engine: $engineId")
            
            val compilationConfig = createCompilationConfiguration(context)
            val result = scriptEngine.compile(scriptContent, scriptName, compilationConfig)
            
            val compilationTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            
            when (result) {
                is ResultWithDiagnostics.Success -> {
                    val metadata = DslCompilationMetadata(
                        compilationTimeMs = compilationTime,
                        cacheHit = false,
                        dependenciesResolved = 0,
                        warningsCount = result.reports.count { 
                            it.severity == ScriptDiagnostic.Severity.WARNING 
                        }
                    )
                    
                    DslCompilationResult.Success(result.value, metadata)
                }
                
                is ResultWithDiagnostics.Failure -> {
                    val errors = result.reports
                        .filter { it.severity == ScriptDiagnostic.Severity.ERROR }
                        .map { convertToDslError(it) }
                    
                    val warnings = result.reports
                        .filter { it.severity == ScriptDiagnostic.Severity.WARNING }
                        .map { convertToDslWarning(it) }
                    
                    DslCompilationResult.Failure(errors, warnings)
                }
            }
        } catch (e: Exception) {
            logger.error("DSL compilation failed for engine $engineId: ${e.message}")
            DslCompilationResult.Failure(
                listOf(DslError("COMPILATION_ERROR", e.message ?: "Unknown compilation error", cause = e))
            )
        }
    }
    
    override suspend fun execute(
        compiledScript: CompiledScript,
        context: DslExecutionContext
    ): DslExecutionResult<Any> = withContext(Dispatchers.Default) {
        val startTime = Instant.now()
        
        try {
            logger.debug("Executing compiled DSL script with engine: $engineId")
            
            val evaluationConfig = createEvaluationConfiguration(context)
            val executionResult = scriptEngine.execute(compiledScript, evaluationConfig)
            
            when (executionResult) {
                is ResultWithDiagnostics.Success -> {
                    // Extract the result using the provided extractor function
                    val result = try {
                        resultExtractor(executionResult.value)
                    } catch (e: Exception) {
                        logger.warn("Failed to extract result from DSL execution: ${e.message}")
                        when (val returnValue = executionResult.value.returnValue) {
                            is ResultValue.Value -> returnValue.value ?: Unit
                            is ResultValue.Unit -> Unit
                            is ResultValue.Error -> throw returnValue.error
                            else -> Unit
                        }
                    }
                    
                    val executionTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    val metadata = DslExecutionMetadata(
                        executionTimeMs = executionTime,
                        memoryUsedMb = null, // TODO: Measure memory usage
                        threadsUsed = null,
                        eventsPublished = 0 // TODO: Count events
                    )
                    
                    DslExecutionResult.Success(result, metadata)
                }
                
                is ResultWithDiagnostics.Failure -> {
                    val error = executionResult.reports.firstOrNull()?.let { convertToDslError(it) }
                        ?: DslError("EXECUTION_ERROR", "Script execution failed")
                    
                    val executionTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    val metadata = DslExecutionMetadata(executionTimeMs = executionTime)
                    
                    DslExecutionResult.Failure(error, metadata)
                }
            }
        } catch (e: Exception) {
            logger.error("DSL execution failed for engine $engineId: ${e.message}")
            
            val executionTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            val metadata = DslExecutionMetadata(executionTimeMs = executionTime)
            val error = DslError("EXECUTION_ERROR", e.message ?: "Unknown execution error", cause = e)
            
            DslExecutionResult.Failure(error, metadata)
        }
    }
    
    override suspend fun compileAndExecute(
        scriptFile: File,
        compilationContext: DslCompilationContext,
        executionContext: DslExecutionContext
    ): DslExecutionResult<Any> {
        
        val compilationResult = compile(scriptFile, compilationContext)
        
        return when (compilationResult) {
            is DslCompilationResult.Success -> {
                execute(compilationResult.compiledScript, executionContext)
            }
            is DslCompilationResult.Failure -> {
                val error = compilationResult.errors.firstOrNull()
                    ?: DslError("COMPILATION_ERROR", "Compilation failed")
                
                DslExecutionResult.Failure(error)
            }
        }
    }
    
    override suspend fun validate(
        scriptContent: String,
        context: DslCompilationContext
    ): DslValidationResult {
        return try {
            val compilationResult = compile(scriptContent, "validation-script", context)
            
            when (compilationResult) {
                is DslCompilationResult.Success -> {
                    if (compilationResult.metadata.warningsCount > 0) {
                        // Could extract warnings from compilation result if needed
                        DslValidationResult.Valid
                    } else {
                        DslValidationResult.Valid
                    }
                }
                is DslCompilationResult.Failure -> {
                    DslValidationResult.Invalid(compilationResult.errors, compilationResult.warnings)
                }
            }
        } catch (e: Exception) {
            DslValidationResult.Invalid(
                listOf(DslError("VALIDATION_ERROR", e.message ?: "Validation failed", cause = e))
            )
        }
    }
    
    override fun getEngineInfo(): DslEngineInfo {
        return DslEngineInfo(
            engineId = engineId,
            engineName = engineName,
            version = engineVersion,
            description = description,
            author = author,
            supportedExtensions = supportedExtensions,
            capabilities = capabilities,
            dependencies = dependencies
        )
    }
    
    override fun createDefaultCompilationContext(): DslCompilationContext {
        return DslCompilationContext(
            imports = defaultImports,
            allowedPackages = setOf(
                "kotlin.*",
                "java.lang.*",
                "java.util.*",
                "java.io.*",
                "java.time.*"
            ),
            blockedPackages = setOf(
                "java.lang.reflect.*",
                "java.security.*",
                "sun.*",
                "jdk.internal.*"
            ),
            enableCaching = enableCaching,
            securityPolicy = DslSecurityPolicy.DEFAULT
        )
    }
    
    override fun createDefaultExecutionContext(): DslExecutionContext {
        return DslExecutionContext(
            workingDirectory = File(System.getProperty("user.dir")),
            environmentVariables = emptyMap(), // Don't expose system env by default
            resourceLimits = DslResourceLimits(
                maxMemoryMb = 256,
                maxCpuTimeMs = 60_000, // 1 minute
                maxWallTimeMs = 120_000, // 2 minutes
                maxThreads = 5
            ),
            executionPolicy = DslExecutionPolicy(
                isolationLevel = DslIsolationLevel.THREAD,
                allowConcurrentExecution = false, // Conservative default
                enableEventPublishing = true
            )
        )
    }
    
    private fun createCompilationConfiguration(context: DslCompilationContext): ScriptCompilationConfiguration {
        return ScriptCompilationConfiguration(baseCompilationConfiguration) {
            // Add additional imports from context
            if (context.imports.isNotEmpty()) {
                defaultImports.append(context.imports.toList())
            }
            
            // Add classpath from context
            if (context.classPath.isNotEmpty()) {
                jvm {
                    dependenciesFromCurrentContext(wholeClasspath = true)
                }
            }
        }
    }
    
    private fun createEvaluationConfiguration(context: DslExecutionContext): ScriptEvaluationConfiguration {
        return ScriptEvaluationConfiguration {
            // Add context variables as script parameters
            if (context.variables.isNotEmpty()) {
                // TODO: Add variables to evaluation context
                // This depends on the specific DSL requirements
            }
            
            // Apply resource limits if specified
            context.resourceLimits?.let { limits ->
                // TODO: Apply resource limits
                // This would require custom evaluation configuration
            }
        }
    }
    
    private fun convertToDslError(diagnostic: ScriptDiagnostic): DslError {
        return DslError(
            code = diagnostic.severity.name,
            message = diagnostic.message,
            location = diagnostic.location?.let { loc ->
                DslLocation(
                    line = loc.start.line ?: 0,
                    column = loc.start.col ?: 0
                )
            },
            cause = diagnostic.exception,
            severity = when (diagnostic.severity) {
                ScriptDiagnostic.Severity.ERROR -> DslSeverity.ERROR
                ScriptDiagnostic.Severity.WARNING -> DslSeverity.WARNING
                ScriptDiagnostic.Severity.INFO -> DslSeverity.INFO
                ScriptDiagnostic.Severity.DEBUG -> DslSeverity.INFO
                ScriptDiagnostic.Severity.FATAL -> DslSeverity.FATAL
            }
        )
    }
    
    private fun convertToDslWarning(diagnostic: ScriptDiagnostic): DslWarning {
        return DslWarning(
            code = diagnostic.severity.name,
            message = diagnostic.message,
            location = diagnostic.location?.let { loc ->
                DslLocation(
                    line = loc.start.line ?: 0,
                    column = loc.start.col ?: 0
                )
            },
            severity = when (diagnostic.severity) {
                ScriptDiagnostic.Severity.WARNING -> DslSeverity.WARNING
                ScriptDiagnostic.Severity.INFO -> DslSeverity.INFO
                ScriptDiagnostic.Severity.DEBUG -> DslSeverity.INFO
                else -> DslSeverity.WARNING
            }
        )
    }
}

/**
 * Builder for creating GenericKotlinDslEngine instances with fluent API.
 */
class GenericKotlinDslEngineBuilder {
    private var engineId: String = ""
    private var engineName: String = ""
    private var engineVersion: String = "1.0.0"
    private var supportedExtensions: Set<String> = emptySet()
    private var scriptDefinitionClass: kotlin.reflect.KClass<*>? = null
    private var implicitReceivers: List<kotlin.reflect.KClass<*>> = emptyList()
    private var defaultImports: Set<String> = emptySet()
    private var resultExtractor: (EvaluationResult) -> Any = { result -> 
        when (val returnValue = result.returnValue) {
            is ResultValue.Value -> returnValue.value ?: Unit
            is ResultValue.Unit -> Unit
            is ResultValue.Error -> throw returnValue.error
            else -> Unit
        }
    }
    private var description: String = "Generic Kotlin DSL Engine"
    private var author: String = "Unknown"
    private var dependencies: List<String> = emptyList()
    private var capabilities: Set<DslCapability> = setOf(
        DslCapability.COMPILATION_CACHING,
        DslCapability.SYNTAX_VALIDATION
    )
    private var logger: IPipelineLogger = PipelineLogger.getLogger()
    private var enableCaching: Boolean = true
    
    fun engineId(id: String) = apply { this.engineId = id }
    fun engineName(name: String) = apply { this.engineName = name }
    fun engineVersion(version: String) = apply { this.engineVersion = version }
    fun supportedExtensions(extensions: Set<String>) = apply { this.supportedExtensions = extensions }
    fun supportedExtensions(vararg extensions: String) = apply { this.supportedExtensions = extensions.toSet() }
    fun scriptDefinitionClass(clazz: kotlin.reflect.KClass<*>) = apply { this.scriptDefinitionClass = clazz }
    fun implicitReceivers(receivers: List<kotlin.reflect.KClass<*>>) = apply { this.implicitReceivers = receivers }
    fun implicitReceivers(vararg receivers: kotlin.reflect.KClass<*>) = apply { this.implicitReceivers = receivers.toList() }
    fun defaultImports(imports: Set<String>) = apply { this.defaultImports = imports }
    fun defaultImports(vararg imports: String) = apply { this.defaultImports = imports.toSet() }
    fun resultExtractor(extractor: (EvaluationResult) -> Any) = apply { this.resultExtractor = extractor }
    fun description(desc: String) = apply { this.description = desc }
    fun author(auth: String) = apply { this.author = auth }
    fun dependencies(deps: List<String>) = apply { this.dependencies = deps }
    fun dependencies(vararg deps: String) = apply { this.dependencies = deps.toList() }
    fun capabilities(caps: Set<DslCapability>) = apply { this.capabilities = caps }
    fun capabilities(vararg caps: DslCapability) = apply { this.capabilities = caps.toSet() }
    fun logger(log: IPipelineLogger) = apply { this.logger = log }
    fun enableCaching(enable: Boolean) = apply { this.enableCaching = enable }
    
    fun build(): GenericKotlinDslEngine {
        require(engineId.isNotBlank()) { "Engine ID cannot be blank" }
        require(engineName.isNotBlank()) { "Engine name cannot be blank" }
        require(supportedExtensions.isNotEmpty()) { "At least one supported extension is required" }
        requireNotNull(scriptDefinitionClass) { "Script definition class is required" }
        
        return GenericKotlinDslEngine(
            engineId = engineId,
            engineName = engineName,
            engineVersion = engineVersion,
            supportedExtensions = supportedExtensions,
            scriptDefinitionClass = scriptDefinitionClass!!,
            implicitReceivers = implicitReceivers,
            defaultImports = defaultImports,
            resultExtractor = resultExtractor,
            description = description,
            author = author,
            dependencies = dependencies,
            capabilities = capabilities,
            logger = logger,
            enableCaching = enableCaching
        )
    }
}

/**
 * DSL function for building GenericKotlinDslEngine
 */
fun genericKotlinDslEngine(configure: GenericKotlinDslEngineBuilder.() -> Unit): GenericKotlinDslEngine {
    return GenericKotlinDslEngineBuilder().apply(configure).build()
}