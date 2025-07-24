package dev.rubentxu.pipeline.lsp.analysis

import dev.rubentxu.pipeline.error.EnhancedError
import dev.rubentxu.pipeline.error.ErrorSuggestionEngine
import dev.rubentxu.pipeline.error.SourceContext
import dev.rubentxu.pipeline.error.SourcePosition
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory

/**
 * Analyzes Pipeline DSL scripts and provides AST-based information.
 * 
 * Integrates with the existing pipeline DSL infrastructure to:
 * - Parse .pipeline.kts files using Kotlin scripting APIs
 * - Validate DSL syntax and semantics
 * - Extract symbols for outline view
 * - Provide structural information for completion and navigation
 */
class PipelineAstAnalyzer {
    
    private val logger = LoggerFactory.getLogger(PipelineAstAnalyzer::class.java)
    
    /**
     * Validates a pipeline script and returns diagnostics with enhanced error reporting.
     */
    fun validatePipelineScript(content: String, uri: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        
        try {
            logger.debug("Validating pipeline script: $uri")
            
            // Basic syntax validation with enhanced errors
            validateBasicSyntaxEnhanced(content, diagnostics, uri)
            
            // DSL structure validation with enhanced errors
            validateDslStructureEnhanced(content, diagnostics, uri)
            
            // TODO: Integrate with existing DslValidator from core module
            // This would provide comprehensive validation using the existing
            // pipeline DSL validation infrastructure
            
            logger.debug("Validation complete: ${diagnostics.size} issues found")
            
        } catch (e: Exception) {
            logger.error("Error during validation", e)
            
            // Create enhanced error for the exception
            val enhancedError = EnhancedError.fromRuntimeException(e, null, "VALIDATION_ERROR")
            diagnostics.add(createDiagnosticFromEnhancedError(enhancedError, uri))
        }
        
        return diagnostics
    }
    
    /**
     * Extracts document symbols for outline view.
     */
    fun extractDocumentSymbols(content: String, uri: String): List<Either<SymbolInformation, DocumentSymbol>> {
        val symbols = mutableListOf<Either<SymbolInformation, DocumentSymbol>>()
        
        try {
            logger.debug("Extracting symbols from: $uri")
            
            val lines = content.lines()
            
            // Find pipeline block
            findPipelineBlock(lines, symbols, uri)
            
            // Find stages
            findStages(lines, symbols, uri)
            
            // Find steps
            findSteps(lines, symbols, uri)
            
            logger.debug("Extracted ${symbols.size} symbols")
            
        } catch (e: Exception) {
            logger.error("Error extracting symbols", e)
        }
        
        return symbols
    }
    
    /**
     * Basic syntax validation (braces, parentheses, etc.)
     */
    private fun validateBasicSyntax(content: String, diagnostics: MutableList<Diagnostic>) {
        val lines = content.lines()
        var braceCount = 0
        var parenCount = 0
        
        lines.forEachIndexed { lineIndex, line ->
            line.forEachIndexed { charIndex, char ->
                when (char) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount < 0) {
                            diagnostics.add(createDiagnostic(
                                lineIndex, charIndex, lineIndex, charIndex + 1,
                                "Unmatched closing brace",
                                DiagnosticSeverity.Error
                            ))
                        }
                    }
                    '(' -> parenCount++
                    ')' -> {
                        parenCount--
                        if (parenCount < 0) {
                            diagnostics.add(createDiagnostic(
                                lineIndex, charIndex, lineIndex, charIndex + 1,
                                "Unmatched closing parenthesis",
                                DiagnosticSeverity.Error
                            ))
                        }
                    }
                }
            }
        }
        
        // Check for unclosed braces/parentheses
        if (braceCount > 0) {
            diagnostics.add(createDiagnostic(
                lines.size - 1, 0, lines.size - 1, lines.lastOrNull()?.length ?: 0,
                "Unclosed braces ($braceCount remaining)",
                DiagnosticSeverity.Error
            ))
        }
        
        if (parenCount > 0) {
            diagnostics.add(createDiagnostic(
                lines.size - 1, 0, lines.size - 1, lines.lastOrNull()?.length ?: 0,
                "Unclosed parentheses ($parenCount remaining)",
                DiagnosticSeverity.Error
            ))
        }
    }
    
    /**
     * Validates DSL structure (pipeline blocks, required sections, etc.)
     */
    private fun validateDslStructure(content: String, diagnostics: MutableList<Diagnostic>) {
        val lines = content.lines()
        var hasPipelineBlock = false
        
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            
            // Check for pipeline block
            if (trimmed.startsWith("pipeline") && trimmed.contains("{")) {
                hasPipelineBlock = true
            }
            
            // Check for common mistakes
            if (trimmed.startsWith("stage(") && !trimmed.contains(")")) {
                diagnostics.add(createDiagnostic(
                    lineIndex, 0, lineIndex, line.length,
                    "Stage declaration missing closing parenthesis",
                    DiagnosticSeverity.Error
                ))
            }
            
            // Warn about deprecated syntax (if any)
            if (trimmed.contains("steps {") && !trimmed.startsWith("//")) {
                diagnostics.add(createDiagnostic(
                    lineIndex, trimmed.indexOf("steps"), lineIndex, trimmed.indexOf("steps") + 5,
                    "Consider using specific step functions instead of generic steps block",
                    DiagnosticSeverity.Information
                ))
            }
        }
        
        // Check for required pipeline block
        if (!hasPipelineBlock && content.trim().isNotEmpty()) {
            diagnostics.add(createDiagnostic(
                0, 0, 0, 0,
                "Pipeline script should contain a 'pipeline { }' block",
                DiagnosticSeverity.Warning
            ))
        }
    }
    
    /**
     * Finds and creates symbols for pipeline blocks.
     */
    private fun findPipelineBlock(lines: List<String>, symbols: MutableList<Either<SymbolInformation, DocumentSymbol>>, uri: String) {
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("pipeline") && trimmed.contains("{")) {
                val symbol = DocumentSymbol().apply {
                    name = "pipeline"
                    kind = SymbolKind.Module
                    range = Range(Position(index, 0), Position(index, line.length))
                    selectionRange = Range(Position(index, line.indexOf("pipeline")), Position(index, line.indexOf("pipeline") + 8))
                    detail = "Pipeline configuration"
                }
                symbols.add(Either.forRight(symbol))
            }
        }
    }
    
    /**
     * Finds and creates symbols for stages.
     */
    private fun findStages(lines: List<String>, symbols: MutableList<Either<SymbolInformation, DocumentSymbol>>, uri: String) {
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("stage(")) {
                // Extract stage name from stage("name") syntax
                val nameMatch = Regex("stage\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)").find(trimmed)
                val stageName = nameMatch?.groupValues?.get(1) ?: "unnamed"
                
                val symbol = DocumentSymbol().apply {
                    name = "stage: $stageName"
                    kind = SymbolKind.Function
                    range = Range(Position(index, 0), Position(index, line.length))
                    selectionRange = Range(Position(index, line.indexOf("stage")), Position(index, line.indexOf("stage") + 5))
                    detail = "Pipeline stage"
                }
                symbols.add(Either.forRight(symbol))
            }
        }
    }
    
    /**
     * Finds and creates symbols for steps.
     */
    private fun findSteps(lines: List<String>, symbols: MutableList<Either<SymbolInformation, DocumentSymbol>>, uri: String) {
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // Look for common step patterns
            val stepPatterns = listOf(
                "sh\\s*\\(" to "sh",
                "echo\\s*\\(" to "echo", 
                "checkout\\s*\\(" to "checkout",
                "readFile\\s*\\(" to "readFile",
                "writeFile\\s*\\(" to "writeFile"
            )
            
            stepPatterns.forEach { (pattern, stepName) ->
                if (Regex(pattern).find(trimmed) != null) {
                    val symbol = DocumentSymbol().apply {
                        name = stepName
                        kind = SymbolKind.Method
                        range = Range(Position(index, 0), Position(index, line.length))
                        selectionRange = Range(Position(index, trimmed.indexOf(stepName)), Position(index, trimmed.indexOf(stepName) + stepName.length))
                        detail = "Pipeline step"
                    }
                    symbols.add(Either.forRight(symbol))
                }
            }
        }
    }
    
    /**
     * Enhanced basic syntax validation with contextual errors.
     */
    private fun validateBasicSyntaxEnhanced(content: String, diagnostics: MutableList<Diagnostic>, uri: String) {
        val lines = content.lines()
        var braceCount = 0
        var parenCount = 0
        
        lines.forEachIndexed { lineIndex, line ->
            line.forEachIndexed { charIndex, char ->
                when (char) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount < 0) {
                            val enhancedError = createUnmatchedBraceError(lineIndex, charIndex, content, uri)
                            diagnostics.add(createDiagnosticFromEnhancedError(enhancedError, uri))
                        }
                    }
                    '(' -> parenCount++
                    ')' -> {
                        parenCount--
                        if (parenCount < 0) {
                            val enhancedError = createUnmatchedParenError(lineIndex, charIndex, content, uri)
                            diagnostics.add(createDiagnosticFromEnhancedError(enhancedError, uri))
                        }
                    }
                }
            }
        }
        
        // Check for unclosed braces/parentheses
        if (braceCount > 0) {
            val enhancedError = createUnclosedBracesError(lines.size - 1, content, braceCount, uri)
            diagnostics.add(createDiagnosticFromEnhancedError(enhancedError, uri))
        }
        
        if (parenCount > 0) {
            val enhancedError = createUnclosedParenError(lines.size - 1, content, parenCount, uri)
            diagnostics.add(createDiagnosticFromEnhancedError(enhancedError, uri))
        }
    }
    
    /**
     * Enhanced DSL structure validation with contextual errors.
     */
    private fun validateDslStructureEnhanced(content: String, diagnostics: MutableList<Diagnostic>, uri: String) {
        val lines = content.lines()
        var hasPipelineBlock = false
        
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            
            // Check for pipeline block
            if (trimmed.startsWith("pipeline") && trimmed.contains("{")) {
                hasPipelineBlock = true
            }
            
            // Check for common mistakes with enhanced errors
            if (trimmed.startsWith("stage(") && !trimmed.contains(")")) {
                val enhancedError = createIncompleteStageError(lineIndex, content, uri)
                diagnostics.add(createDiagnosticFromEnhancedError(enhancedError, uri))
            }
            
            // Check for potentially dangerous operations
            if (containsDangerousOperation(trimmed)) {
                val enhancedError = createSecurityWarning(lineIndex, content, trimmed, uri)
                diagnostics.add(createDiagnosticFromEnhancedError(enhancedError, uri))
            }
        }
        
        // Check for required pipeline block
        if (!hasPipelineBlock && content.trim().isNotEmpty()) {
            val enhancedError = createMissingPipelineBlockError(content, uri)
            diagnostics.add(createDiagnosticFromEnhancedError(enhancedError, uri))
        }
    }
    
    /**
     * Creates an enhanced error for unmatched braces.
     */
    private fun createUnmatchedBraceError(line: Int, column: Int, content: String, file: String): EnhancedError {
        val position = SourcePosition(line + 1, column + 1, file)
        val sourceContext = createSourceContext(content, position)
        val suggestions = ErrorSuggestionEngine.generateSuggestions(
            "Unmatched closing brace",
            "UNMATCHED_BRACE",
            sourceContext
        )
        
        return EnhancedError(
            code = "UNMATCHED_BRACE",
            message = "Unmatched closing brace",
            location = position,
            sourceContext = sourceContext,
            suggestions = suggestions
        )
    }
    
    /**
     * Creates an enhanced error for unmatched parentheses.
     */
    private fun createUnmatchedParenError(line: Int, column: Int, content: String, file: String): EnhancedError {
        val position = SourcePosition(line + 1, column + 1, file)
        val sourceContext = createSourceContext(content, position)
        val suggestions = ErrorSuggestionEngine.generateSuggestions(
            "Unmatched closing parenthesis",
            "UNMATCHED_PAREN",
            sourceContext
        )
        
        return EnhancedError(
            code = "UNMATCHED_PAREN",
            message = "Unmatched closing parenthesis",
            location = position,
            sourceContext = sourceContext,
            suggestions = suggestions
        )
    }
    
    /**
     * Creates an enhanced error for unclosed braces.
     */
    private fun createUnclosedBracesError(line: Int, content: String, count: Int, file: String): EnhancedError {
        val position = SourcePosition(line + 1, 0, file)
        val sourceContext = createSourceContext(content, position)
        val suggestions = ErrorSuggestionEngine.generateSuggestions(
            "Unclosed braces ($count remaining)",
            "UNCLOSED_BRACES",
            sourceContext
        )
        
        return EnhancedError(
            code = "UNCLOSED_BRACES",
            message = "Unclosed braces ($count remaining)",
            location = position,
            sourceContext = sourceContext,
            suggestions = suggestions
        )
    }
    
    /**
     * Creates an enhanced error for unclosed parentheses.
     */
    private fun createUnclosedParenError(line: Int, content: String, count: Int, file: String): EnhancedError {
        val position = SourcePosition(line + 1, 0, file)
        val sourceContext = createSourceContext(content, position)
        val suggestions = ErrorSuggestionEngine.generateSuggestions(
            "Unclosed parentheses ($count remaining)",
            "UNCLOSED_PAREN",
            sourceContext
        )
        
        return EnhancedError(
            code = "UNCLOSED_PAREN",
            message = "Unclosed parentheses ($count remaining)",
            location = position,
            sourceContext = sourceContext,
            suggestions = suggestions
        )
    }
    
    /**
     * Creates an enhanced error for incomplete stage declarations.
     */
    private fun createIncompleteStageError(line: Int, content: String, file: String): EnhancedError {
        val position = SourcePosition(line + 1, 0, file)
        val sourceContext = createSourceContext(content, position)
        val suggestions = ErrorSuggestionEngine.generateSuggestions(
            "Stage declaration missing closing parenthesis",
            "INCOMPLETE_STAGE",
            sourceContext
        )
        
        return EnhancedError(
            code = "INCOMPLETE_STAGE",
            message = "Stage declaration missing closing parenthesis",
            location = position,
            sourceContext = sourceContext,
            suggestions = suggestions
        )
    }
    
    /**
     * Creates an enhanced warning for security issues.
     */
    private fun createSecurityWarning(line: Int, content: String, lineContent: String, file: String): EnhancedError {
        val position = SourcePosition(line + 1, 0, file)
        val sourceContext = createSourceContext(content, position)
        val suggestions = ErrorSuggestionEngine.generateSuggestions(
            "Potentially dangerous operation: $lineContent",
            "SECURITY_WARNING",
            sourceContext
        )
        
        return EnhancedError(
            code = "SECURITY_WARNING",
            message = "Potentially dangerous operation detected",
            severity = dev.rubentxu.pipeline.error.ErrorSeverity.WARNING,
            location = position,
            sourceContext = sourceContext,
            suggestions = suggestions
        )
    }
    
    /**
     * Creates an enhanced error for missing pipeline block.
     */
    private fun createMissingPipelineBlockError(content: String, file: String): EnhancedError {
        val position = SourcePosition(1, 0, file)
        val sourceContext = createSourceContext(content, position)
        val suggestions = ErrorSuggestionEngine.generateSuggestions(
            "Pipeline script should contain a 'pipeline { }' block",
            "MISSING_PIPELINE_BLOCK",
            sourceContext
        )
        
        return EnhancedError(
            code = "MISSING_PIPELINE_BLOCK",
            message = "Pipeline script should contain a 'pipeline { }' block",
            severity = dev.rubentxu.pipeline.error.ErrorSeverity.WARNING,
            location = position,
            sourceContext = sourceContext,
            suggestions = suggestions
        )
    }
    
    /**
     * Creates source context from content and position.
     */
    private fun createSourceContext(content: String, position: SourcePosition): SourceContext {
        val lines = content.lines()
        val contextLines = 3
        val startLine = maxOf(1, position.line - contextLines)
        val endLine = minOf(lines.size, position.line + contextLines)
        
        return SourceContext(
            errorLine = position.line,
            errorColumn = position.column,
            startLine = startLine,
            endLine = endLine,
            lines = lines.subList(startLine - 1, endLine),
            file = position.file
        )
    }
    
    /**
     * Checks if a line contains potentially dangerous operations.
     */
    private fun containsDangerousOperation(line: String): Boolean {
        val dangerousPatterns = listOf(
            "rm -rf",
            "sudo ",
            "chmod 777",
            "eval(",
            "exec(",
            "System.exit"
        )
        
        return dangerousPatterns.any { pattern ->
            line.contains(pattern, ignoreCase = true)
        }
    }
    
    /**
     * Creates an LSP Diagnostic from an EnhancedError.
     */
    private fun createDiagnosticFromEnhancedError(enhancedError: EnhancedError, uri: String): Diagnostic {
        val diagnostic = Diagnostic()
        
        // Set position
        val errorLocation = enhancedError.location
        if (errorLocation != null) {
            diagnostic.range = Range(
                Position(errorLocation.line - 1, errorLocation.column),
                Position(errorLocation.line - 1, errorLocation.column + 1)
            )
        } else {
            diagnostic.range = Range(Position(0, 0), Position(0, 0))
        }
        
        // Set severity
        diagnostic.severity = when (enhancedError.severity) {
            dev.rubentxu.pipeline.error.ErrorSeverity.ERROR -> DiagnosticSeverity.Error
            dev.rubentxu.pipeline.error.ErrorSeverity.WARNING -> DiagnosticSeverity.Warning
            dev.rubentxu.pipeline.error.ErrorSeverity.INFO -> DiagnosticSeverity.Information
            dev.rubentxu.pipeline.error.ErrorSeverity.DEBUG -> DiagnosticSeverity.Hint
        }
        
        // Set message with suggestions
        diagnostic.message = if (enhancedError.suggestions.isNotEmpty()) {
            buildString {
                append(enhancedError.message)
                append("\n\nSuggestions:")
                enhancedError.suggestions.forEach { suggestion ->
                    append("\n• ${suggestion.description}")
                }
            }
        } else {
            enhancedError.message
        }
        
        diagnostic.code = Either.forLeft(enhancedError.code)
        diagnostic.source = "pipeline-lsp-enhanced"
        
        return diagnostic
    }
    
    /**
     * Helper function to create diagnostic objects.
     */
    private fun createDiagnostic(
        startLine: Int, startChar: Int,
        endLine: Int, endChar: Int,
        message: String,
        severity: DiagnosticSeverity
    ): Diagnostic {
        return Diagnostic().apply {
            range = Range(Position(startLine, startChar), Position(endLine, endChar))
            this.severity = severity
            this.message = message
            source = "pipeline-lsp"
        }
    }
}