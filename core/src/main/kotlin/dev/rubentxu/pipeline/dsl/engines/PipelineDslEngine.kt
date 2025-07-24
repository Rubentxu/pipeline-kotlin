package dev.rubentxu.pipeline.dsl.engines

import dev.rubentxu.pipeline.compilation.CachedScriptEngine
import dev.rubentxu.pipeline.compilation.ScriptCompilationCacheFactory
import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * DSL Engine specifically for Pipeline DSL scripts.
 * This engine handles the compilation and execution of .pipeline.kts files
 * following the pipeline DSL syntax.
 */
class PipelineDslEngine(
    private val pipelineConfig: IPipelineConfig,
    private val logger: ILogger = PipelineLogger.getLogger(),
    private val enableCaching: Boolean = true
) : DslEngine<Pipeline> {
    
    override val engineId = "pipeline-dsl"
    override val engineName = "Kotlin Pipeline DSL Engine"
    override val engineVersion = "1.0.0"
    override val supportedExtensions = setOf(".pipeline.kts", ".kts")
    
    private val scriptEngine = CachedScriptEngine(
        cache = if (enableCaching) {
            ScriptCompilationCacheFactory.createProductionCache()
        } else {
            ScriptCompilationCacheFactory.createDevelopmentCache()
        },
        logger = logger
    )
    
    private val baseCompilationConfiguration = createJvmCompilationConfigurationFromTemplate<PipelineScript> {
        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
        implicitReceivers(PipelineBlock::class)
        defaultImports(
            "dev.rubentxu.pipeline.dsl.*",
            "dev.rubentxu.pipeline.model.pipeline.*",
            "pipeline.kotlin.extensions.*"
        )
    }
    
    override suspend fun compile(
        scriptFile: File,
        context: DslCompilationContext
    ): DslCompilationResult<Pipeline> = withContext(Dispatchers.IO) {
        val startTime = Instant.now()
        
        try {
            logger.debug("Compiling pipeline script: ${scriptFile.name}")
            
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
            logger.error("Pipeline compilation failed: ${e.message}")
            DslCompilationResult.Failure(
                listOf(DslError("COMPILATION_ERROR", e.message ?: "Unknown compilation error", cause = e))
            )
        }
    }
    
    override suspend fun compile(
        scriptContent: String,
        scriptName: String,
        context: DslCompilationContext
    ): DslCompilationResult<Pipeline> = withContext(Dispatchers.IO) {
        val startTime = Instant.now()
        
        try {
            logger.debug("Compiling pipeline script: $scriptName")
            
            val compilationConfig = createCompilationConfiguration(context)
            val result = scriptEngine.compile(scriptContent, scriptName, compilationConfig)
            
            val compilationTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            
            when (result) {
                is ResultWithDiagnostics.Success -> {
                    val metadata = DslCompilationMetadata(
                        compilationTimeMs = compilationTime,
                        cacheHit = false, // TODO: Get from cache stats
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
            logger.error("Pipeline compilation failed: ${e.message}")
            DslCompilationResult.Failure(
                listOf(DslError("COMPILATION_ERROR", e.message ?: "Unknown compilation error", cause = e))
            )
        }
    }
    
    override suspend fun execute(
        compiledScript: CompiledScript,
        context: DslExecutionContext
    ): DslExecutionResult<Pipeline> = withContext(Dispatchers.Default) {
        val startTime = Instant.now()
        
        try {
            logger.debug("Executing compiled pipeline script")
            
            // Create pipeline evaluation configuration
            val evaluationConfig = createEvaluationConfiguration(context)
            
            // Execute the script to get the pipeline definition
            val executionResult = scriptEngine.execute(compiledScript, evaluationConfig)
            
            when (executionResult) {
                is ResultWithDiagnostics.Success -> {
                    // Extract the PipelineDefinition from the script execution result
                    val pipelineDefinition = extractPipelineDefinitionFromResult(executionResult.value)
                    
                    // Build the Pipeline object from the definition
                    val pipeline = pipelineDefinition.build(pipelineConfig)
                    
                    val executionTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    val metadata = DslExecutionMetadata(
                        executionTimeMs = executionTime,
                        memoryUsedMb = null, // TODO: Measure memory usage
                        threadsUsed = null,
                        eventsPublished = 0 // TODO: Count events
                    )
                    
                    DslExecutionResult.Success(pipeline, metadata)
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
            logger.error("Pipeline execution failed: ${e.message}")
            
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
    ): DslExecutionResult<Pipeline> {
        
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
                        // If there are warnings, we could return them, but for now just return valid
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
    
    /**
     * Enhanced validation with comprehensive error reporting
     */
    suspend fun validateWithEnhancedReporting(
        scriptContent: String,
        scriptName: String = "script.kts",
        compilationContext: DslCompilationContext = createDefaultCompilationContext(),
        executionContext: DslExecutionContext = createDefaultExecutionContext(),
        sandboxManager: dev.rubentxu.pipeline.security.SandboxManager
    ): dev.rubentxu.pipeline.dsl.validation.DslValidationReport {
        val validator = dev.rubentxu.pipeline.dsl.validation.DslValidator(sandboxManager, logger)
        return validator.validateScript(scriptContent, scriptName, compilationContext, executionContext)
    }
    
    override fun getEngineInfo(): DslEngineInfo {
        return DslEngineInfo(
            engineId = engineId,
            engineName = engineName,
            version = engineVersion,
            description = "Engine for executing Kotlin Pipeline DSL scripts",
            author = "Pipeline DSL Team",
            supportedExtensions = supportedExtensions,
            capabilities = setOf(
                DslCapability.COMPILATION_CACHING,
                DslCapability.SYNTAX_VALIDATION,
                DslCapability.TYPE_CHECKING,
                DslCapability.EVENT_STREAMING
            ),
            dependencies = listOf(
                "kotlin-scripting-jvm",
                "kotlin-scripting-jvm-host"
            )
        )
    }
    
    override fun createDefaultCompilationContext(): DslCompilationContext {
        return DslCompilationContext(
            imports = setOf(
                "dev.rubentxu.pipeline.dsl.*",
                "dev.rubentxu.pipeline.model.pipeline.*",
                "pipeline.kotlin.extensions.*"
            ),
            allowedPackages = setOf(
                "kotlin.*",
                "java.lang.*",
                "java.util.*",
                "dev.rubentxu.pipeline.*"
            ),
            blockedPackages = setOf(
                "java.lang.reflect.*",
                "java.security.*",
                "sun.*"
            ),
            enableCaching = enableCaching,
            securityPolicy = DslSecurityPolicy.DEFAULT
        )
    }
    
    override fun createDefaultExecutionContext(): DslExecutionContext {
        return DslExecutionContext(
            workingDirectory = File(System.getProperty("user.dir")),
            environmentVariables = System.getenv(),
            resourceLimits = DslResourceLimits(
                maxMemoryMb = 512,
                maxCpuTimeMs = 300_000, // 5 minutes
                maxWallTimeMs = 600_000, // 10 minutes
                maxThreads = 10
            ),
            executionPolicy = DslExecutionPolicy(
                isolationLevel = DslIsolationLevel.THREAD,
                allowConcurrentExecution = true,
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
            
            // Add additional configuration from context
            context.configuration.forEach { (key, value) ->
                // Apply custom configuration if needed
            }
        }
    }
    
    private fun createEvaluationConfiguration(context: DslExecutionContext): ScriptEvaluationConfiguration {
        return ScriptEvaluationConfiguration {
            // Add context variables as script parameters
            if (context.variables.isNotEmpty()) {
                // TODO: Add variables to evaluation context
            }
            
            // Apply resource limits if specified
            context.resourceLimits?.let { limits ->
                // TODO: Apply resource limits
            }
        }
    }
    
    private fun extractPipelineDefinitionFromResult(evaluationResult: EvaluationResult): PipelineDefinition {
        // Extract the PipelineDefinition from the script execution result
        // The script should return a PipelineDefinition object
        return when (val returnValue = evaluationResult.returnValue.scriptInstance) {
            is PipelineDefinition -> returnValue
            else -> {
                // If the script didn't return a PipelineDefinition directly,
                // we need to check if it created one through the DSL
                // This is a fallback that assumes the DSL was used correctly
                logger.warn("Script did not return PipelineDefinition directly, using fallback extraction")
                
                // Try to get the last evaluated result which should be the pipeline() call result
                evaluationResult.returnValue.scriptInstance as? PipelineDefinition
                    ?: throw IllegalStateException("Script execution did not produce a valid PipelineDefinition")
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

