package dev.rubentxu.pipeline.lsp.analysis

// TODO: Add these imports when step registry integration is fully working
// import dev.rubentxu.pipeline.annotations.StepCategory
// import dev.rubentxu.pipeline.steps.registry.GlobalUnifiedStepRegistry
// import dev.rubentxu.pipeline.steps.registry.EnhancedStepMetadata
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory

/**
 * Provides code completion for Pipeline DSL constructs and @Step functions.
 * 
 * Integrates with the existing step registry system to provide:
 * - DSL block completion (pipeline, stages, steps)
 * - Built-in step function completion
 * - Custom step function completion
 * - Parameter hints and documentation
 */
class StepCompletionProvider {
    
    private val logger = LoggerFactory.getLogger(StepCompletionProvider::class.java)
    
    /**
     * Provides completion items based on the current position in the document.
     */
    fun getCompletions(content: String, position: Position): List<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()
        
        try {
            val lines = content.lines()
            val currentLine = if (position.line < lines.size) lines[position.line] else ""
            val currentLinePrefix = currentLine.substring(0, minOf(position.character, currentLine.length))
            
            logger.debug("Providing completions for position ${position.line}:${position.character}")
            logger.debug("Current line: '$currentLine'")
            logger.debug("Line prefix: '$currentLinePrefix'")
            
            // Determine context and provide appropriate completions
            when (determineCompletionContext(currentLinePrefix, lines, position.line)) {
                CompletionContext.ROOT_LEVEL -> {
                    addRootLevelCompletions(completions)
                }
                CompletionContext.PIPELINE_BLOCK -> {
                    addPipelineBlockCompletions(completions)
                }
                CompletionContext.STAGES_BLOCK -> {
                    addStagesBlockCompletions(completions)
                }
                CompletionContext.STAGE_BLOCK -> {
                    addStageBlockCompletions(completions)
                }
                CompletionContext.STEPS_CONTEXT -> {
                    addStepCompletions(completions, currentLinePrefix)
                }
                CompletionContext.UNKNOWN -> {
                    // Provide general completions
                    addGeneralCompletions(completions)
                }
            }
            
            logger.debug("Provided ${completions.size} completions")
            
        } catch (e: Exception) {
            logger.error("Error providing completions", e)
        }
        
        return completions
    }
    
    /**
     * Determines the completion context based on the current position.
     */
    private fun determineCompletionContext(currentLinePrefix: String, lines: List<String>, currentLine: Int): CompletionContext {
        val trimmedPrefix = currentLinePrefix.trim()
        
        // Check if we're at the root level
        if (currentLine == 0 || isAtRootLevel(lines, currentLine)) {
            return CompletionContext.ROOT_LEVEL
        }
        
        // Look backwards to find the current context
        var braceDepth = 0
        var currentContext = CompletionContext.UNKNOWN
        
        for (i in currentLine downTo 0) {
            val line = if (i == currentLine) currentLinePrefix else lines[i]
            val trimmed = line.trim()
            
            // Count braces to determine nesting level
            braceDepth += line.count { it == '}' } - line.count { it == '{' }
            
            when {
                trimmed.startsWith("pipeline") && trimmed.contains("{") -> {
                    if (braceDepth <= 0) return CompletionContext.PIPELINE_BLOCK
                }
                trimmed.startsWith("stages") && trimmed.contains("{") -> {
                    if (braceDepth <= 0) return CompletionContext.STAGES_BLOCK
                }
                trimmed.startsWith("stage(") -> {
                    if (braceDepth <= 0) return CompletionContext.STAGE_BLOCK
                }
                trimmed.startsWith("steps") && trimmed.contains("{") -> {
                    if (braceDepth <= 0) return CompletionContext.STEPS_CONTEXT
                }
            }
        }
        
        return currentContext
    }
    
    /**
     * Checks if the current position is at the root level of the file.
     */
    private fun isAtRootLevel(lines: List<String>, currentLine: Int): Boolean {
        var braceDepth = 0
        for (i in 0 until currentLine) {
            braceDepth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
        }
        return braceDepth == 0
    }
    
    /**
     * Adds root-level completions (pipeline block, imports, etc.)
     */
    private fun addRootLevelCompletions(completions: MutableList<CompletionItem>) {
        completions.add(CompletionItem().apply {
            label = "pipeline"
            kind = CompletionItemKind.Snippet
            detail = "Pipeline configuration block"
            documentation = Either.forLeft("Define the main pipeline configuration")
            insertText = "pipeline {\\n    \${1:// Pipeline configuration}\\n}"
            insertTextFormat = InsertTextFormat.Snippet
        })
        
        // Add common imports
        completions.add(CompletionItem().apply {
            label = "import dev.rubentxu.pipeline.steps.*"
            kind = CompletionItemKind.Module
            detail = "Import pipeline steps"
            insertText = "import dev.rubentxu.pipeline.steps.*"
        })
    }
    
    /**
     * Adds completions available within the pipeline block.
     */
    private fun addPipelineBlockCompletions(completions: MutableList<CompletionItem>) {
        completions.add(CompletionItem().apply {
            label = "agent"
            kind = CompletionItemKind.Property
            detail = "Pipeline agent configuration"
            documentation = Either.forLeft("Specify where the pipeline should run")
            insertText = "agent {\\n    \${1:// Agent configuration}\\n}"
            insertTextFormat = InsertTextFormat.Snippet
        })
        
        completions.add(CompletionItem().apply {
            label = "environment"
            kind = CompletionItemKind.Property
            detail = "Environment variables"
            documentation = Either.forLeft("Define environment variables for the pipeline")
            insertText = "environment {\\n    \${1:// Environment variables}\\n}"
            insertTextFormat = InsertTextFormat.Snippet
        })
        
        completions.add(CompletionItem().apply {
            label = "stages"
            kind = CompletionItemKind.Property
            detail = "Pipeline stages"
            documentation = Either.forLeft("Define the stages of the pipeline")
            insertText = "stages {\\n    \${1:// Stages}\\n}"
            insertTextFormat = InsertTextFormat.Snippet
        })
        
        completions.add(CompletionItem().apply {
            label = "post"
            kind = CompletionItemKind.Property
            detail = "Post-build actions"
            documentation = Either.forLeft("Actions to run after the pipeline")
            insertText = "post {\\n    \${1:// Post actions}\\n}"
            insertTextFormat = InsertTextFormat.Snippet
        })
    }
    
    /**
     * Adds completions available within the stages block.
     */
    private fun addStagesBlockCompletions(completions: MutableList<CompletionItem>) {
        completions.add(CompletionItem().apply {
            label = "stage"
            kind = CompletionItemKind.Function
            detail = "Pipeline stage"
            documentation = Either.forLeft("Define a pipeline stage")
            insertText = "stage(\"\${1:Stage Name}\") {\\n    \${2:// Stage steps}\\n}"
            insertTextFormat = InsertTextFormat.Snippet
        })
    }
    
    /**
     * Adds completions available within a stage block.
     */
    private fun addStageBlockCompletions(completions: MutableList<CompletionItem>) {
        completions.add(CompletionItem().apply {
            label = "steps"
            kind = CompletionItemKind.Property
            detail = "Stage steps"
            documentation = Either.forLeft("Define the steps for this stage")
            insertText = "steps {\\n    \${1:// Steps}\\n}"
            insertTextFormat = InsertTextFormat.Snippet
        })
        
        completions.add(CompletionItem().apply {
            label = "when"
            kind = CompletionItemKind.Property
            detail = "Conditional execution"
            documentation = Either.forLeft("Specify when this stage should run")
            insertText = "when {\\n    \${1:// Conditions}\\n}"
            insertTextFormat = InsertTextFormat.Snippet
        })
    }
    
    /**
     * Adds step function completions.
     */
    private fun addStepCompletions(completions: MutableList<CompletionItem>, currentLinePrefix: String) {
        // Built-in steps
        addBuiltInStepCompletions(completions)
        
        // Custom steps from step registry
        addCustomStepCompletions(completions)
        
        // Add step categories as hints
        if (currentLinePrefix.trim().isEmpty()) {
            addStepCategoryHints(completions)
        }
    }
    
    /**
     * Adds built-in step function completions.
     */
    private fun addBuiltInStepCompletions(completions: MutableList<CompletionItem>) {
        val builtInSteps = listOf(
            StepCompletion("sh", "Execute shell command", "sh(\"\${1:command}\")", "Execute a shell command"),
            StepCompletion("echo", "Print message", "echo(\"\${1:message}\")", "Print a message to the console"),
            StepCompletion("checkout", "Checkout code", "checkout(scm)", "Checkout source code"),
            StepCompletion("readFile", "Read file", "readFile(\"\${1:filename}\")", "Read contents of a file"),
            StepCompletion("writeFile", "Write file", "writeFile(file: \"\${1:filename}\", text: \"\${2:content}\")", "Write content to a file"),
            StepCompletion("archiveArtifacts", "Archive artifacts", "archiveArtifacts(\"\${1:pattern}\")", "Archive build artifacts"),
            StepCompletion("publishTestResults", "Publish test results", "publishTestResults(\"\${1:pattern}\")", "Publish test results"),
            StepCompletion("dir", "Change directory", "dir(\"\${1:directory}\") {\\n    \${2:// Steps}\\n}", "Execute steps in a directory"),
            StepCompletion("timeout", "Set timeout", "timeout(\${1:time}) {\\n    \${2:// Steps}\\n}", "Execute steps with timeout"),
            StepCompletion("retry", "Retry on failure", "retry(\${1:count}) {\\n    \${2:// Steps}\\n}", "Retry steps on failure")
        )
        
        builtInSteps.forEach { step ->
            completions.add(CompletionItem().apply {
                label = step.name
                kind = CompletionItemKind.Function
                detail = step.detail
                documentation = Either.forLeft(step.documentation)
                insertText = step.insertText
                insertTextFormat = InsertTextFormat.Snippet
            })
        }
    }
    
    /**
     * Adds custom step completions from the step registry.
     * TODO: Complete integration with GlobalUnifiedStepRegistry when imports are resolved
     */
    private fun addCustomStepCompletions(completions: MutableList<CompletionItem>) {
        logger.debug("Custom step completions from registry - integration pending")
        
        // Placeholder for step registry integration
        // For now, add a comment completion to indicate that custom steps would appear here
        completions.add(CompletionItem().apply {
            label = "// Custom @Step functions will appear here"
            kind = CompletionItemKind.Text
            detail = "Step registry integration pending"
            insertText = "// Custom @Step functions available when registry is loaded\\n"
            sortText = "z_custom_steps_placeholder"
        })
        
        // TODO: Uncomment and fix when imports work correctly:
        /*
        try {
            logger.debug("Adding custom step completions from registry")
            
            // Get all registered steps from the global registry
            val registeredSteps = GlobalUnifiedStepRegistry.getAllSteps()
            
            logger.debug("Found \${registeredSteps.size} registered steps")
            
            registeredSteps.forEach { stepMetadata ->
                val parameters = buildParametersString(stepMetadata)
                val insertText = if (parameters.isNotEmpty()) {
                    "\${stepMetadata.name}($parameters)"
                } else {
                    "\${stepMetadata.name}()"
                }
                
                completions.add(CompletionItem().apply {
                    label = stepMetadata.name
                    kind = CompletionItemKind.Function
                    detail = "\${stepMetadata.description} (\${stepMetadata.category.name})"
                    documentation = Either.forLeft(buildStepDocumentation(stepMetadata))
                    this.insertText = insertText
                    
                    // Sort custom steps after built-in ones
                    sortText = "z_\${stepMetadata.name}"
                    
                    // Add security level as additional info
                    additionalTextEdits = emptyList()
                })
            }
            
        } catch (e: Exception) {
            logger.warn("Error loading custom steps from registry", e)
        }
        */
    }
    
    /**
     * Adds category hints for better organization.
     * TODO: Complete integration with registry stats when imports are resolved
     */
    private fun addStepCategoryHints(completions: MutableList<CompletionItem>) {
        logger.debug("Step category hints - integration pending")
        
        // Placeholder category hints based on known step categories
        val categoryHints = listOf(
            "Build steps" to "Compilation, packaging, and build automation",
            "SCM steps" to "Source control and versioning operations", 
            "Test steps" to "Testing and quality assurance",
            "Deploy steps" to "Deployment and release management",
            "Utility steps" to "General purpose utilities"
        )
        
        categoryHints.forEach { (categoryName, description) ->
            completions.add(CompletionItem().apply {
                label = "// $categoryName"
                kind = CompletionItemKind.Text
                detail = description
                insertText = "// $categoryName\\n"
                sortText = "0_category_${categoryName.lowercase().replace(" ", "_")}"
            })
        }
        
        // TODO: Replace with actual registry stats when available
        /*
        val registryStats = try {
            GlobalUnifiedStepRegistry.getRegistryStats()
        } catch (e: Exception) {
            logger.warn("Could not get registry stats", e)
            return
        }
        
        registryStats.stepsByCategory.forEach { (category, count) ->
            if (count > 0) {
                completions.add(CompletionItem().apply {
                    label = "// \${category.name.lowercase().replaceFirstChar { it.uppercase() }} steps ($count available)"
                    kind = CompletionItemKind.Text
                    detail = "\${category.name} category steps"
                    insertText = "// \${category.name.lowercase().replaceFirstChar { it.uppercase() }} steps\\n"
                    sortText = "0_category_\${category.name}"
                })
            }
        }
        */
    }
    
    // TODO: Uncomment these helper functions when step registry integration is complete
    /*
    /**
     * Builds parameter string for step function completion.
     */
    private fun buildParametersString(stepMetadata: dev.rubentxu.pipeline.steps.registry.EnhancedStepMetadata): String {
        return stepMetadata.parameterTypes.mapIndexed { index, paramType ->
            val paramName = when (paramType.simpleName) {
                "String" -> "\"${index + 1}_value\""
                "Int", "Integer" -> "${index + 1}"
                "Boolean" -> "true"
                "Long" -> "${index + 1}L"
                "Double" -> "${index + 1}.0"
                else -> "param${index + 1}"
            }
            paramName
        }.joinToString(", ")
    }
    
    /**
     * Builds documentation string for a step.
     */
    private fun buildStepDocumentation(stepMetadata: dev.rubentxu.pipeline.steps.registry.EnhancedStepMetadata): String {
        return buildString {
            appendLine("**${stepMetadata.name}**")
            appendLine()
            if (stepMetadata.description.isNotBlank()) {
                appendLine(stepMetadata.description)
                appendLine()
            }
            appendLine("**Category:** ${stepMetadata.category.name}")
            appendLine("**Security Level:** ${stepMetadata.securityLevel.name}")
            if (stepMetadata.isSuspending) {
                appendLine("**Type:** Suspending function")
            }
            if (stepMetadata.parameterTypes.isNotEmpty()) {
                appendLine("**Parameters:** ${stepMetadata.parameterTypes.joinToString(", ") { it.simpleName }}")
            }
            appendLine("**Return Type:** ${stepMetadata.returnType.simpleName}")
        }
    }
    */
    
    /**
     * Adds general completions when context is unclear.
     */
    private fun addGeneralCompletions(completions: MutableList<CompletionItem>) {
        // Add some common keywords
        completions.add(CompletionItem().apply {
            label = "true"
            kind = CompletionItemKind.Keyword
            detail = "Boolean value"
        })
        
        completions.add(CompletionItem().apply {
            label = "false"
            kind = CompletionItemKind.Keyword
            detail = "Boolean value"
        })
    }
    
    /**
     * Represents the completion context within a pipeline script.
     */
    private enum class CompletionContext {
        ROOT_LEVEL,
        PIPELINE_BLOCK,
        STAGES_BLOCK,
        STAGE_BLOCK,
        STEPS_CONTEXT,
        UNKNOWN
    }
    
    /**
     * Data class for step completion information.
     */
    private data class StepCompletion(
        val name: String,
        val detail: String,
        val insertText: String,
        val documentation: String
    )
}