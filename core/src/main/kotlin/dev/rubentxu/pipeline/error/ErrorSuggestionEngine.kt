package dev.rubentxu.pipeline.error


/**
 * Engine for generating intelligent suggestions for common pipeline DSL errors.
 */
object ErrorSuggestionEngine {
    
    private val commonMistakes = mapOf(
        // Step-related mistakes
        "unresolved_reference_sh" to SuggestionTemplate(
            pattern = Regex("unresolved reference.*sh", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Use sh() function to execute shell commands",
                    fixText = """sh("your-command-here")"""
                )
            )
        ),
        
        "unresolved_reference_echo" to SuggestionTemplate(
            pattern = Regex("unresolved reference.*echo", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Use echo() function to print messages",
                    fixText = """echo("your-message-here")"""
                )
            )
        ),
        
        // DSL structure mistakes
        "missing_pipeline_block" to SuggestionTemplate(
            pattern = Regex("pipeline.*not.*defined", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Every pipeline script needs a pipeline block",
                    fixText = """
                        pipeline {
                            stages {
                                stage("Build") {
                                    steps {
                                        // Your steps here
                                    }
                                }
                            }
                        }
                    """.trimIndent()
                )
            )
        ),
        
        "missing_stages_block" to SuggestionTemplate(
            pattern = Regex("stages.*not.*defined", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Pipeline blocks need a stages section",
                    fixText = """
                        stages {
                            stage("Build") {
                                steps {
                                    // Your steps here
                                }
                            }
                        }
                    """.trimIndent()
                )
            )
        ),
        
        // Syntax mistakes
        "unmatched_braces" to SuggestionTemplate(
            pattern = Regex("unmatched.*brace|missing.*\\}", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Check that all opening braces { have matching closing braces }",
                    fixText = "Add missing closing brace }"
                ),
                ErrorSuggestion(
                    type = SuggestionType.IMPROVEMENT,
                    description = "Use proper indentation to make brace matching easier to spot"
                )
            )
        ),
        
        "unmatched_parentheses" to SuggestionTemplate(
            pattern = Regex("unmatched.*parenthes|missing.*\\)", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Check that all opening parentheses ( have matching closing parentheses )",
                    fixText = "Add missing closing parenthesis )"
                )
            )
        ),
        
        // Type-related mistakes
        "string_expected" to SuggestionTemplate(
            pattern = Regex("expected.*string|type.*mismatch.*string", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Wrap the value in quotes to make it a string",
                    fixText = """"your-value-here""""
                ),
                ErrorSuggestion(
                    type = SuggestionType.IMPROVEMENT,
                    description = "Use string interpolation for dynamic values: \"Hello \$variable\""
                )
            )
        ),
        
        // Step parameter mistakes
        "wrong_parameter_count" to SuggestionTemplate(
            pattern = Regex("wrong.*parameter.*count|too.*many.*arguments|too.*few.*arguments", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Check the step documentation for the correct number of parameters"
                ),
                ErrorSuggestion(
                    type = SuggestionType.IMPROVEMENT,
                    description = "Use named parameters for better readability: step(parameter = \"value\")"
                )
            )
        ),
        
        // Common step mistakes
        "file_not_found" to SuggestionTemplate(
            pattern = Regex("file.*not.*found|no.*such.*file", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Check that the file path is correct and the file exists"
                ),
                ErrorSuggestion(
                    type = SuggestionType.IMPROVEMENT,
                    description = "Use relative paths or check the working directory with pwd command"
                )
            )
        ),
        
        // Security-related suggestions
        "dangerous_command" to SuggestionTemplate(
            pattern = Regex("dangerous.*command|security.*policy", RegexOption.IGNORE_CASE),
            suggestions = listOf(
                ErrorSuggestion(
                    type = SuggestionType.WARNING,
                    description = "This command might be blocked by security policies"
                ),
                ErrorSuggestion(
                    type = SuggestionType.IMPROVEMENT,
                    description = "Consider using pipeline-provided alternatives or request permission from administrators"
                )
            )
        )
    )
    
    /**
     * Generates suggestions for an error message.
     */
    fun generateSuggestions(
        errorMessage: String,
        errorCode: String? = null,
        sourceContext: SourceContext? = null
    ): List<ErrorSuggestion> {
        val suggestions = mutableListOf<ErrorSuggestion>()
        
        // Check against common mistake patterns
        commonMistakes.values.forEach { template ->
            if (template.pattern.containsMatchIn(errorMessage)) {
                suggestions.addAll(template.suggestions)
            }
        }
        
        // Add context-specific suggestions
        sourceContext?.let { context ->
            suggestions.addAll(generateContextSpecificSuggestions(context, errorMessage))
        }
        
        // Add step-specific suggestions if this looks like a step error
        if (errorMessage.contains("unresolved reference", ignoreCase = true)) {
            suggestions.addAll(generateStepSuggestions(errorMessage))
        }
        
        // Add general suggestions if no specific ones were found
        if (suggestions.isEmpty()) {
            suggestions.addAll(generateGeneralSuggestions(errorMessage, errorCode))
        }
        
        return suggestions.distinctBy { it.description }
    }
    
    /**
     * Generates suggestions based on the source code context.
     */
    private fun generateContextSpecificSuggestions(
        context: SourceContext,
        errorMessage: String
    ): List<ErrorSuggestion> {
        val suggestions = mutableListOf<ErrorSuggestion>()
        val errorLine = context.lines.getOrNull(context.errorLine - context.startLine)
        
        if (errorLine != null) {
            // Check for common patterns in the error line
            when {
                errorLine.trim().startsWith("stage") && !errorLine.contains("{") -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "Stage blocks need opening braces",
                        fixText = "stage(\"${extractStageName(errorLine)}\") { ... }"
                    ))
                }
                
                errorLine.trim().endsWith("{") && !hasMatchingCloseBrace(context, context.errorLine) -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.FIX,
                        description = "This opening brace needs a matching closing brace",
                        fixText = "Add } at the appropriate location"
                    ))
                }
                
                errorLine.contains("=") && !errorLine.contains("==") -> {
                    suggestions.add(ErrorSuggestion(
                        type = SuggestionType.WARNING,
                        description = "Did you mean to use == for comparison instead of = for assignment?"
                    ))
                }
            }
        }
        
        return suggestions
    }
    
    /**
     * Generates suggestions for step-related errors.
     */
    private fun generateStepSuggestions(errorMessage: String): List<ErrorSuggestion> {
        val suggestions = mutableListOf<ErrorSuggestion>()
        
        // Extract the unresolved reference name
        val referencePattern = Regex("unresolved reference: (\\w+)", RegexOption.IGNORE_CASE)
        val match = referencePattern.find(errorMessage)
        
        if (match != null) {
            val referenceName = match.groupValues[1]
            
            // Fallback to built-in steps
            val availableSteps = listOf("sh", "echo", "readFile", "writeFile", "checkout", "archiveArtifacts")
            
            // Find similar step names
            val similarSteps = availableSteps.filter { step ->
                step.lowercase() == referenceName.lowercase() ||
                levenshteinDistance(step.lowercase(), referenceName.lowercase()) <= 2
            }
            
            if (similarSteps.isNotEmpty()) {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.FIX,
                    description = "Did you mean one of these steps: ${similarSteps.joinToString(", ")}?",
                    fixText = "${similarSteps.first()}(...)"
                ))
            }
            
            // Suggest looking at available steps
            suggestions.add(ErrorSuggestion(
                type = SuggestionType.INFO,
                description = "Check available steps with IDE autocompletion or documentation"
            ))
        }
        
        return suggestions
    }
    
    /**
     * Generates general suggestions when no specific patterns match.
     */
    private fun generateGeneralSuggestions(
        errorMessage: String,
        errorCode: String?
    ): List<ErrorSuggestion> {
        val suggestions = mutableListOf<ErrorSuggestion>()
        
        when {
            errorMessage.contains("compilation", ignoreCase = true) -> {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.INFO,
                    description = "Check your syntax and ensure all required elements are present"
                ))
            }
            
            errorMessage.contains("runtime", ignoreCase = true) -> {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.INFO,
                    description = "This error occurred during script execution - check your logic and data"
                ))
            }
            
            errorMessage.contains("permission", ignoreCase = true) -> {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.INFO,
                    description = "This might be a security or file permission issue"
                ))
            }
            
            else -> {
                suggestions.add(ErrorSuggestion(
                    type = SuggestionType.INFO,
                    description = "Check the pipeline documentation or contact support for help with this error"
                ))
            }
        }
        
        return suggestions
    }
    
    private fun extractStageName(line: String): String {
        val stagePattern = Regex("stage\\s*\\(\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        return stagePattern.find(line)?.groupValues?.get(1) ?: "Stage Name"
    }
    
    private fun hasMatchingCloseBrace(context: SourceContext, openLine: Int): Boolean {
        var braceCount = 1
        val startIndex = openLine - context.startLine + 1
        
        for (i in startIndex until context.lines.size) {
            val line = context.lines[i]
            braceCount += line.count { it == '{' }
            braceCount -= line.count { it == '}' }
            
            if (braceCount == 0) return true
        }
        
        return false
    }
    
    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        
        return dp[a.length][b.length]
    }
}

/**
 * Template for generating suggestions based on error patterns.
 */
private data class SuggestionTemplate(
    val pattern: Regex,
    val suggestions: List<ErrorSuggestion>
)