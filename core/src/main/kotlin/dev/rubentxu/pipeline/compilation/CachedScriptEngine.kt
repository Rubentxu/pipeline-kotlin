package dev.rubentxu.pipeline.compilation

import dev.rubentxu.pipeline.dsl.engines.PipelineScript
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Enhanced script engine that provides caching capabilities for Kotlin script compilation.
 * This engine wraps the standard Kotlin scripting host with intelligent caching to improve
 * performance for repeated script executions.
 */
class CachedScriptEngine(
    private val cache: ScriptCompilationCache = ScriptCompilationCacheFactory.createProductionCache(),
    private val logger: ILogger = PipelineLogger.getLogger()
) {
    
    private val scriptingHost = BasicJvmScriptingHost()
    private val baseConfiguration = createJvmCompilationConfigurationFromTemplate<PipelineScript> {
        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
    }
    
    /**
     * Compiles a script from file with caching support.
     * 
     * @param scriptFile The script file to compile
     * @param configuration Optional additional compilation configuration
     * @return Result containing the compiled script or compilation errors
     */
    suspend fun compile(
        scriptFile: File,
        configuration: ScriptCompilationConfiguration = baseConfiguration
    ): ResultWithDiagnostics<CompiledScript> = withContext(Dispatchers.IO) {
        val startTime = Instant.now()
        
        try {
            val scriptContent = scriptFile.readText()
            val finalConfiguration = mergeConfigurations(baseConfiguration, configuration)
            
            // Try to get from cache first
            cache.get(scriptContent, finalConfiguration)?.let { cachedScript ->
                val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                logger.debug("Script compilation cache hit for ${scriptFile.name} (${duration}ms)")
                return@withContext ResultWithDiagnostics.Success(cachedScript)
            }
            
            // Compile the script
            logger.debug("Compiling script: ${scriptFile.name}")
            val scriptSource = scriptFile.toScriptSource()
            val result = scriptingHost.compiler.invoke(scriptSource, finalConfiguration)
            
            when (result) {
                is ResultWithDiagnostics.Success -> {
                    // Cache the successful compilation
                    cache.put(scriptContent, finalConfiguration, result.value)
                    
                    val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    logger.info("Script compiled successfully: ${scriptFile.name} (${duration}ms)")
                    
                    result
                }
                is ResultWithDiagnostics.Failure -> {
                    val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    logger.error("Script compilation failed for ${scriptFile.name} (${duration}ms)")
                    logCompilationErrors(result.reports)
                    
                    result
                }
            }
        } catch (e: Exception) {
            val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            logger.error("Script compilation exception for ${scriptFile.name} (${duration}ms): ${e.message}")
            
            ResultWithDiagnostics.Failure(
                ScriptDiagnostic(
                    ScriptDiagnostic.unspecifiedError,
                    "Compilation failed with exception: ${e.message}",
                    exception = e
                )
            )
        }
    }
    
    /**
     * Compiles a script from string content with caching support.
     * 
     * @param scriptContent The script content as string
     * @param scriptName Optional name for the script (used for logging)
     * @param configuration Optional additional compilation configuration
     * @return Result containing the compiled script or compilation errors
     */
    suspend fun compile(
        scriptContent: String,
        scriptName: String = "inline-script",
        configuration: ScriptCompilationConfiguration = baseConfiguration
    ): ResultWithDiagnostics<CompiledScript> = withContext(Dispatchers.IO) {
        val startTime = Instant.now()
        
        try {
            val finalConfiguration = mergeConfigurations(baseConfiguration, configuration)
            
            // Try to get from cache first
            cache.get(scriptContent, finalConfiguration)?.let { cachedScript ->
                val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                logger.debug("Script compilation cache hit for $scriptName (${duration}ms)")
                return@withContext ResultWithDiagnostics.Success(cachedScript)
            }
            
            // Compile the script
            logger.debug("Compiling script: $scriptName")
            val scriptSource = scriptContent.toScriptSource(scriptName)
            val result = scriptingHost.compiler.invoke(scriptSource, finalConfiguration)
            
            when (result) {
                is ResultWithDiagnostics.Success -> {
                    // Cache the successful compilation
                    cache.put(scriptContent, finalConfiguration, result.value)
                    
                    val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    logger.info("Script compiled successfully: $scriptName (${duration}ms)")
                    
                    result
                }
                is ResultWithDiagnostics.Failure -> {
                    val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    logger.error("Script compilation failed for $scriptName (${duration}ms)")
                    logCompilationErrors(result.reports)
                    
                    result
                }
            }
        } catch (e: Exception) {
            val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            logger.error("Script compilation exception for $scriptName (${duration}ms): ${e.message}")
            
            ResultWithDiagnostics.Failure(
                ScriptDiagnostic(
                    ScriptDiagnostic.unspecifiedError,
                    "Compilation failed with exception: ${e.message}",
                    exception = e
                )
            )
        }
    }
    
    /**
     * Executes a compiled script with the given configuration and context.
     * 
     * @param compiledScript The compiled script to execute
     * @param configuration Optional evaluation configuration
     * @return Result containing the evaluation result or execution errors
     */
    suspend fun execute(
        compiledScript: CompiledScript,
        configuration: ScriptEvaluationConfiguration = ScriptEvaluationConfiguration.Default
    ): ResultWithDiagnostics<EvaluationResult> = withContext(Dispatchers.Default) {
        val startTime = Instant.now()
        
        try {
            logger.debug("Executing compiled script")
            val result = scriptingHost.evaluator(compiledScript, configuration)
            
            val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            
            when (result) {
                is ResultWithDiagnostics.Success -> {
                    logger.debug("Script execution completed successfully (${duration}ms)")
                    result
                }
                is ResultWithDiagnostics.Failure -> {
                    logger.error("Script execution failed (${duration}ms)")
                    logExecutionErrors(result.reports)
                    result
                }
            }
        } catch (e: Exception) {
            val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            logger.error("Script execution exception (${duration}ms): ${e.message}")
            
            ResultWithDiagnostics.Failure(
                ScriptDiagnostic(
                    ScriptDiagnostic.unspecifiedError,
                    "Execution failed with exception: ${e.message}",
                    exception = e
                )
            )
        }
    }
    
    /**
     * Compiles and executes a script in one operation.
     * 
     * @param scriptFile The script file to compile and execute
     * @param compilationConfig Optional compilation configuration
     * @param evaluationConfig Optional evaluation configuration
     * @return Result containing the evaluation result or errors
     */
    suspend fun compileAndExecute(
        scriptFile: File,
        compilationConfig: ScriptCompilationConfiguration = baseConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration = ScriptEvaluationConfiguration.Default
    ): ResultWithDiagnostics<EvaluationResult> {
        
        val compilationResult = compile(scriptFile, compilationConfig)
        
        return when (compilationResult) {
            is ResultWithDiagnostics.Success -> {
                execute(compilationResult.value, evaluationConfig)
            }
            is ResultWithDiagnostics.Failure -> {
                // Convert compilation failure to evaluation failure
                @Suppress("UNCHECKED_CAST")
                compilationResult as ResultWithDiagnostics<EvaluationResult>
            }
        }
    }
    
    /**
     * Compiles and executes a script from string content in one operation.
     * 
     * @param scriptContent The script content as string
     * @param scriptName Optional name for the script
     * @param compilationConfig Optional compilation configuration
     * @param evaluationConfig Optional evaluation configuration
     * @return Result containing the evaluation result or errors
     */
    suspend fun compileAndExecute(
        scriptContent: String,
        scriptName: String = "inline-script",
        compilationConfig: ScriptCompilationConfiguration = baseConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration = ScriptEvaluationConfiguration.Default
    ): ResultWithDiagnostics<EvaluationResult> {
        
        val compilationResult = compile(scriptContent, scriptName, compilationConfig)
        
        return when (compilationResult) {
            is ResultWithDiagnostics.Success -> {
                execute(compilationResult.value, evaluationConfig)
            }
            is ResultWithDiagnostics.Failure -> {
                // Convert compilation failure to evaluation failure
                @Suppress("UNCHECKED_CAST")
                compilationResult as ResultWithDiagnostics<EvaluationResult>
            }
        }
    }
    
    /**
     * Gets cache statistics for monitoring performance.
     */
    fun getCacheStats(): CacheStats = cache.getStats()
    
    /**
     * Clears the compilation cache.
     */
    suspend fun clearCache() {
        cache.clear()
        logger.info("Script compilation cache cleared")
    }
    
    /**
     * Gets the current cache size.
     */
    suspend fun getCacheSize(): Int = cache.size()
    
    private fun mergeConfigurations(
        base: ScriptCompilationConfiguration,
        additional: ScriptCompilationConfiguration
    ): ScriptCompilationConfiguration {
        return if (additional == base) {
            base
        } else {
            // For now, just use the additional configuration
            // In a more sophisticated implementation, you would merge the configurations properly
            additional
        }
    }
    
    private fun logCompilationErrors(reports: List<ScriptDiagnostic>) {
        reports.forEach { diagnostic ->
            when (diagnostic.severity) {
                ScriptDiagnostic.Severity.ERROR -> logger.error("Compilation error: ${diagnostic.message}")
                ScriptDiagnostic.Severity.WARNING -> logger.warn("Compilation warning: ${diagnostic.message}")
                ScriptDiagnostic.Severity.INFO -> logger.info("Compilation info: ${diagnostic.message}")
                ScriptDiagnostic.Severity.DEBUG -> logger.debug("Compilation debug: ${diagnostic.message}")
                ScriptDiagnostic.Severity.FATAL -> logger.error("Compilation fatal error: ${diagnostic.message}")
            }
        }
    }
    
    private fun logExecutionErrors(reports: List<ScriptDiagnostic>) {
        reports.forEach { diagnostic ->
            when (diagnostic.severity) {
                ScriptDiagnostic.Severity.ERROR -> logger.error("Execution error: ${diagnostic.message}")
                ScriptDiagnostic.Severity.WARNING -> logger.warn("Execution warning: ${diagnostic.message}")
                ScriptDiagnostic.Severity.INFO -> logger.info("Execution info: ${diagnostic.message}")
                ScriptDiagnostic.Severity.DEBUG -> logger.debug("Execution debug: ${diagnostic.message}")
                ScriptDiagnostic.Severity.FATAL -> logger.error("Execution fatal error: ${diagnostic.message}")
            }
        }
    }
}

