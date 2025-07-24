package dev.rubentxu.pipeline.error

import dev.rubentxu.pipeline.logger.interfaces.ILogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Enhanced script engine with source mapping and enhanced error reporting.
 * Provides better error messages and contextual information for pipeline scripts.
 */
class EnhancedScriptEngine(
    private val logger: ILogger
) {
    
    private val scriptingHost = BasicJvmScriptingHost()
    private val baseConfiguration = createJvmCompilationConfigurationFromTemplate<Any> {
        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
    }
    
    // Store source maps for compiled scripts
    private val sourceMaps = ConcurrentHashMap<String, SourceMap>()
    
    /**
     * Enhanced compilation result that includes source mapping information.
     */
    data class EnhancedCompilationResult(
        val compilationResult: ResultWithDiagnostics<CompiledScript>,
        val sourceMap: SourceMap?,
        val enhancedErrors: List<EnhancedError> = emptyList()
    )
    
    /**
     * Enhanced execution result with better error reporting.
     */
    data class EnhancedExecutionResult(
        val executionResult: ResultWithDiagnostics<EvaluationResult>,
        val enhancedErrors: List<EnhancedError> = emptyList()
    )
    
    /**
     * Compiles a script with enhanced error reporting and source mapping.
     */
    suspend fun compileEnhanced(
        scriptFile: File,
        configuration: ScriptCompilationConfiguration = baseConfiguration
    ): EnhancedCompilationResult = withContext(Dispatchers.IO) {
        val startTime = Instant.now()
        val scriptContent = scriptFile.readText()
        val scriptName = scriptFile.name
        
        try {
            // Create source map
            val sourceMap = SourceMapBuilder.createBasicMapping(
                originalFile = scriptFile.absolutePath,
                originalContent = scriptContent,
                compiledName = scriptName
            )
            
            // Store source map
            sourceMaps[scriptName] = sourceMap
            
            // Perform compilation
            val scriptSource = scriptContent.toScriptSource(scriptName)
            val compilationResult = scriptingHost.compiler.invoke(scriptSource, configuration)
            
            // Convert any errors to enhanced errors
            val enhancedErrors = when (compilationResult) {
                is ResultWithDiagnostics.Failure -> {
                    compilationResult.reports.map { diagnostic ->
                        createEnhancedErrorFromDiagnostic(diagnostic, sourceMap)
                    }
                }
                else -> emptyList()
            }
            
            EnhancedCompilationResult(
                compilationResult = compilationResult,
                sourceMap = sourceMap,
                enhancedErrors = enhancedErrors
            )
            
        } catch (e: Exception) {
            val sourceMap = sourceMaps[scriptName]
            val enhancedError = EnhancedError.fromCompilationException(e, sourceMap)
            
            EnhancedCompilationResult(
                compilationResult = ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        ScriptDiagnostic.unspecifiedError,
                        "Compilation failed with exception: ${e.message}",
                        exception = e
                    )
                ),
                sourceMap = sourceMap,
                enhancedErrors = listOf(enhancedError)
            )
        }
    }
    
    /**
     * Compiles a script from string content with enhanced error reporting.
     */
    suspend fun compileEnhanced(
        scriptContent: String,
        scriptName: String = "inline-script",
        configuration: ScriptCompilationConfiguration = baseConfiguration
    ): EnhancedCompilationResult = withContext(Dispatchers.IO) {
        
        try {
            // Create source map
            val sourceMap = SourceMapBuilder.createBasicMapping(
                originalFile = scriptName,
                originalContent = scriptContent,
                compiledName = scriptName
            )
            
            // Store source map
            sourceMaps[scriptName] = sourceMap
            
            // Perform compilation
            val scriptSource = scriptContent.toScriptSource(scriptName)
            val compilationResult = scriptingHost.compiler.invoke(scriptSource, configuration)
            
            // Convert any errors to enhanced errors
            val enhancedErrors = when (compilationResult) {
                is ResultWithDiagnostics.Failure -> {
                    compilationResult.reports.map { diagnostic ->
                        createEnhancedErrorFromDiagnostic(diagnostic, sourceMap)
                    }
                }
                else -> emptyList()
            }
            
            EnhancedCompilationResult(
                compilationResult = compilationResult,
                sourceMap = sourceMap,
                enhancedErrors = enhancedErrors
            )
            
        } catch (e: Exception) {
            val sourceMap = sourceMaps[scriptName]
            val enhancedError = EnhancedError.fromCompilationException(e, sourceMap)
            
            EnhancedCompilationResult(
                compilationResult = ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        ScriptDiagnostic.unspecifiedError,
                        "Compilation failed with exception: ${e.message}",
                        exception = e
                    )
                ),
                sourceMap = sourceMap,
                enhancedErrors = listOf(enhancedError)
            )
        }
    }
    
    /**
     * Executes a compiled script with enhanced error reporting.
     */
    suspend fun executeEnhanced(
        compiledScript: CompiledScript,
        scriptName: String,
        configuration: ScriptEvaluationConfiguration = ScriptEvaluationConfiguration.Default
    ): EnhancedExecutionResult = withContext(Dispatchers.Default) {
        
        try {
            // Get source map
            val sourceMap = sourceMaps[scriptName]
            
            // Perform execution
            val executionResult = scriptingHost.evaluator.invoke(compiledScript, configuration)
            
            // Convert any errors to enhanced errors
            val enhancedErrors = when (executionResult) {
                is ResultWithDiagnostics.Failure -> {
                    executionResult.reports.map { diagnostic ->
                        createEnhancedErrorFromDiagnostic(diagnostic, sourceMap)
                    }
                }
                else -> emptyList()
            }
            
            EnhancedExecutionResult(
                executionResult = executionResult,
                enhancedErrors = enhancedErrors
            )
            
        } catch (e: Exception) {
            val sourceMap = sourceMaps[scriptName]
            val enhancedError = EnhancedError.fromRuntimeException(e, sourceMap)
            
            EnhancedExecutionResult(
                executionResult = ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        ScriptDiagnostic.unspecifiedError,
                        "Execution failed with exception: ${e.message}",
                        exception = e
                    )
                ),
                enhancedErrors = listOf(enhancedError)
            )
        }
    }
    
    /**
     * Compiles and executes a script with enhanced error reporting.
     */
    suspend fun compileAndExecuteEnhanced(
        scriptFile: File,
        compilationConfig: ScriptCompilationConfiguration = baseConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration = ScriptEvaluationConfiguration.Default
    ): Pair<EnhancedCompilationResult, EnhancedExecutionResult?> {
        
        val compilationResult = compileEnhanced(scriptFile, compilationConfig)
        
        return when (compilationResult.compilationResult) {
            is ResultWithDiagnostics.Success -> {
                val executionResult = executeEnhanced(
                    compilationResult.compilationResult.value,
                    scriptFile.name,
                    evaluationConfig
                )
                Pair(compilationResult, executionResult)
            }
            is ResultWithDiagnostics.Failure -> {
                Pair(compilationResult, null)
            }
        }
    }
    
    /**
     * Compiles and executes a script from string content with enhanced error reporting.
     */
    suspend fun compileAndExecuteEnhanced(
        scriptContent: String,
        scriptName: String = "inline-script",
        compilationConfig: ScriptCompilationConfiguration = baseConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration = ScriptEvaluationConfiguration.Default
    ): Pair<EnhancedCompilationResult, EnhancedExecutionResult?> {
        
        val compilationResult = compileEnhanced(scriptContent, scriptName, compilationConfig)
        
        return when (compilationResult.compilationResult) {
            is ResultWithDiagnostics.Success -> {
                val executionResult = executeEnhanced(
                    compilationResult.compilationResult.value,
                    scriptName,
                    evaluationConfig
                )
                Pair(compilationResult, executionResult)
            }
            is ResultWithDiagnostics.Failure -> {
                Pair(compilationResult, null)
            }
        }
    }
    
    /**
     * Gets the source map for a compiled script.
     */
    fun getSourceMap(scriptName: String): SourceMap? = sourceMaps[scriptName]
    
    /**
     * Gets all stored source maps.
     */
    fun getAllSourceMaps(): Map<String, SourceMap> = sourceMaps.toMap()
    
    /**
     * Clears all source maps.
     */
    fun clearSourceMaps() {
        sourceMaps.clear()
    }
    
    private fun createEnhancedErrorFromDiagnostic(
        diagnostic: ScriptDiagnostic,
        sourceMap: SourceMap?
    ): EnhancedError {
        val location = diagnostic.location?.let { loc ->
            sourceMap?.mapToOriginal(loc.start.line, loc.start.col)
        }
        
        val sourceContext = location?.let { pos ->
            sourceMap?.getSourceContext(pos)
        }
        
        val severity = when (diagnostic.severity) {
            ScriptDiagnostic.Severity.ERROR, ScriptDiagnostic.Severity.FATAL -> ErrorSeverity.ERROR
            ScriptDiagnostic.Severity.WARNING -> ErrorSeverity.WARNING
            ScriptDiagnostic.Severity.INFO -> ErrorSeverity.INFO
            ScriptDiagnostic.Severity.DEBUG -> ErrorSeverity.DEBUG
        }
        
        return EnhancedError(
            code = "SCRIPT_ERROR",
            message = diagnostic.message,
            severity = severity,
            location = location,
            sourceContext = sourceContext,
            cause = diagnostic.exception,
            suggestions = generateSuggestionsFromDiagnostic(diagnostic)
        )
    }
    
    private fun generateSuggestionsFromDiagnostic(diagnostic: ScriptDiagnostic): List<ErrorSuggestion> {
        val suggestions = mutableListOf<ErrorSuggestion>()
        val message = diagnostic.message.lowercase()
        
        when {
            "unresolved reference" in message -> {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Check if the referenced variable or function is declared and imported correctly"
                ))
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.IMPROVEMENT,
                    description = "Consider checking available @Step functions or importing required modules"
                ))
            }
            "type mismatch" in message -> {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Check the types of your variables and function parameters"
                ))
            }
            "missing" in message -> {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Check for missing semicolons, brackets, or parentheses"
                ))
            }
            "deprecated" in message -> {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.IMPROVEMENT,
                    description = "Consider updating to the newer API or method"
                ))
            }
        }
        
        return suggestions
    }
}