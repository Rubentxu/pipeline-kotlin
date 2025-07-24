package dev.rubentxu.pipeline.error


/**
 * Enhanced error information with source mapping and contextual data.
 */
data class EnhancedError(
    val code: String,
    val message: String,
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
    val location: SourcePosition? = null,
    val sourceContext: SourceContext? = null,
    val cause: Throwable? = null,
    val suggestions: List<ErrorSuggestion> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
) {
    
    /**
     * Formats the error as a comprehensive, user-friendly message.
     */
    fun format(includeContext: Boolean = true): String = buildString {
        // Error header
        appendLine("${severity.symbol} [$code] $message")
        
        // Location information
        location?.let { pos ->
            appendLine("  at ${pos.file}:${pos.line}:${pos.column}")
        }
        
        // Source context
        if (includeContext && sourceContext != null) {
            appendLine()
            appendLine("Source:")
            appendLine(sourceContext.format())
        }
        
        // Suggestions
        if (suggestions.isNotEmpty()) {
            appendLine()
            appendLine("Suggestions:")
            suggestions.forEach { suggestion ->
                appendLine("  ${suggestion.type.symbol} ${suggestion.description}")
                if (suggestion.fixText != null) {
                    appendLine("    Fix: ${suggestion.fixText}")
                }
            }
        }
        
        // Additional metadata
        if (metadata.isNotEmpty()) {
            appendLine()
            appendLine("Additional information:")
            metadata.forEach { (key, value) ->
                appendLine("  $key: $value")
            }
        }
        
        // Cause information
        cause?.let { throwable ->
            appendLine()
            appendLine("Caused by: ${throwable.javaClass.simpleName}: ${throwable.message}")
        }
    }
    
    /**
     * Creates a shorter, IDE-friendly version of the error.
     */
    fun formatCompact(): String {
        val locationInfo = location?.let { " at ${it.file}:${it.line}:${it.column}" } ?: ""
        return "[$code] $message$locationInfo"
    }
    
    companion object {
        
        /**
         * Creates an EnhancedError from a compilation exception.
         */
        fun fromCompilationException(
            exception: Throwable,
            sourceMap: SourceMap? = null,
            code: String = "COMPILATION_ERROR"
        ): EnhancedError {
            // Try to extract line information from exception message
            val lineInfo = extractLineInfo(exception.message)
            val mappedLocation = lineInfo?.let { (line, column) ->
                sourceMap?.mapToOriginal(line, column)
            }
            
            val sourceContext = mappedLocation?.let { pos ->
                sourceMap?.getSourceContext(pos)
            }
            
            return EnhancedError(
                code = code,
                message = exception.message ?: "Unknown compilation error",
                severity = ErrorSeverity.ERROR,
                location = mappedLocation,
                sourceContext = sourceContext,
                cause = exception,
                suggestions = generateCompilationSuggestions(exception)
            )
        }
        
        /**
         * Creates an EnhancedError from a runtime exception.
         */
        fun fromRuntimeException(
            exception: Throwable,
            sourceMap: SourceMap? = null,
            code: String = "RUNTIME_ERROR"
        ): EnhancedError {
            // Extract location from stack trace
            val location = extractLocationFromStackTrace(exception, sourceMap)
            val sourceContext = location?.let { pos ->
                sourceMap?.getSourceContext(pos)
            }
            
            return EnhancedError(
                code = code,
                message = exception.message ?: "Unknown runtime error",
                severity = ErrorSeverity.ERROR,
                location = location,
                sourceContext = sourceContext,
                cause = exception,
                suggestions = generateRuntimeSuggestions(exception)
            )
        }
        
        private fun extractLineInfo(message: String?): Pair<Int, Int>? {
            if (message == null) return null
            
            // Common patterns for line information in error messages
            val patterns = listOf(
                "\\(line (\\d+)(?:, column (\\d+))?\\)",
                "at line (\\d+)(?:, column (\\d+))?",
                "line (\\d+)(?::(\\d+))?"
            )
            
            for (pattern in patterns) {
                val regex = Regex(pattern)
                val match = regex.find(message)
                if (match != null) {
                    val line = match.groupValues[1].toIntOrNull() ?: continue
                    val column = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                    return Pair(line, column)
                }
            }
            
            return null
        }
        
        private fun extractLocationFromStackTrace(
            exception: Throwable,
            sourceMap: SourceMap?
        ): SourcePosition? {
            // Look through stack trace for relevant entries
            exception.stackTrace.forEach { element ->
                if (sourceMap != null && element.fileName == sourceMap.compiledName) {
                    return sourceMap.mapToOriginal(element.lineNumber, 0)
                }
            }
            return null
        }
        
        private fun generateCompilationSuggestions(exception: Throwable): List<ErrorSuggestion> {
            val suggestions = mutableListOf<ErrorSuggestion>()
            val message = exception.message?.lowercase() ?: ""
            
            when {
                "unresolved reference" in message -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "Check if the referenced variable or function is declared and imported correctly"
                    ))
                }
                "type mismatch" in message -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "Check the types of your variables and function parameters"
                    ))
                }
                "missing semicolon" in message || "expected" in message -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "Check for missing semicolons, brackets, or parentheses"
                    ))
                }
            }
            
            return suggestions
        }
        
        private fun generateRuntimeSuggestions(exception: Throwable): List<ErrorSuggestion> {
            val suggestions = mutableListOf<ErrorSuggestion>()
            
            when (exception) {
                is NullPointerException -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "Check for null values and use safe navigation operator (?.) or null checks"
                    ))
                }
                is ClassCastException -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "Verify the types being cast are compatible or use safe casting (as?)"
                    ))
                }
                is IndexOutOfBoundsException -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "Check array/list bounds before accessing elements"
                    ))
                }
                is IllegalArgumentException -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "Check the arguments passed to the function are valid"
                    ))
                }
            }
            
            return suggestions
        }
    }
}

/**
 * Error severity levels.
 */
enum class ErrorSeverity(val symbol: String) {
    ERROR("❌"),
    WARNING("⚠️"),
    INFO("ℹ️"),
    DEBUG("🔍")
}

/**
 * Suggestion for fixing or improving the code.
 */
data class ErrorSuggestion(
    val type: SuggestionType,
    val description: String,
    val fixText: String? = null,
    val quickFix: QuickFix? = null
)

/**
 * Types of suggestions.
 */
enum class SuggestionType(val symbol: String) {
    FIX("🔧"),
    IMPROVEMENT("💡"),
    WARNING("⚠️"),
    INFO("ℹ️")
}

/**
 * Quick fix action that can be applied automatically.
 */
data class QuickFix(
    val description: String,
    val textEdits: List<TextEdit>
)

/**
 * Text edit operation.
 */
data class TextEdit(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val newText: String
)