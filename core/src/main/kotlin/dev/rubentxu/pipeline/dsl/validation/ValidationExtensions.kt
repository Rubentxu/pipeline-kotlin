package dev.rubentxu.pipeline.dsl.validation

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.security.SandboxManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Extension functions to integrate enhanced validation into DSL engines
 */

/**
 * Validates a DSL script with enhanced error reporting
 */
suspend fun DslEngine<*>.validateWithEnhancedReporting(
    scriptContent: String,
    scriptName: String = "script.kts",
    compilationContext: DslCompilationContext = createDefaultCompilationContext(),
    executionContext: DslExecutionContext = createDefaultExecutionContext(),
    sandboxManager: SandboxManager,
    logger: IPipelineLogger
): DslValidationReport {
    
    val validator = DslValidator(sandboxManager, logger)
    return validator.validateScript(scriptContent, scriptName, compilationContext, executionContext)
}

/**
 * Validates a DSL script file with enhanced error reporting
 */
suspend fun DslEngine<*>.validateFileWithEnhancedReporting(
    scriptFile: java.io.File,
    compilationContext: DslCompilationContext = createDefaultCompilationContext(),
    executionContext: DslExecutionContext = createDefaultExecutionContext(),
    sandboxManager: SandboxManager,
    logger: IPipelineLogger
): DslValidationReport {
    
    val scriptContent = scriptFile.readText()
    return validateWithEnhancedReporting(
        scriptContent = scriptContent,
        scriptName = scriptFile.name,
        compilationContext = compilationContext,
        executionContext = executionContext,
        sandboxManager = sandboxManager,
        logger = logger
    )
}

/**
 * Converts a DslValidationReport to a standard DslValidationResult
 */
fun DslValidationReport.toStandardResult(): DslValidationResult {
    return if (isValid) {
        DslValidationResult.Valid
    } else {
        val errors = issues.filter { it.severity == DslValidationSeverity.ERROR }
            .map { issue ->
                DslError(
                    code = issue.code,
                    message = issue.message,
                    location = issue.location,
                    severity = DslSeverity.ERROR
                )
            }
        
        val warnings = (issues.filter { it.severity == DslValidationSeverity.WARNING } + 
                       warnings.map { warning ->
                           DslValidationIssue(
                               code = warning.code,
                               message = warning.message,
                               severity = DslValidationSeverity.WARNING,
                               location = warning.location,
                               suggestion = warning.suggestion
                           )
                       })
            .map { issue ->
                DslWarning(
                    code = issue.code,
                    message = issue.message,
                    location = issue.location,
                    severity = DslSeverity.WARNING
                )
            }
        
        DslValidationResult.Invalid(errors, warnings)
    }
}

/**
 * Creates a validation stream that continuously validates script changes
 */
fun DslEngine<*>.createValidationStream(
    scriptContent: String,
    scriptName: String = "script.kts",
    compilationContext: DslCompilationContext = createDefaultCompilationContext(),
    executionContext: DslExecutionContext = createDefaultExecutionContext(),
    sandboxManager: SandboxManager,
    logger: IPipelineLogger
): Flow<DslValidationReport> = flow {
    
    val validator = DslValidator(sandboxManager, logger)
    
    // Initial validation
    val report = validator.validateScript(scriptContent, scriptName, compilationContext, executionContext)
    emit(report)
    
    // For continuous validation, we would need to watch for file changes
    // This is a simplified version that validates once
}

/**
 * Validates multiple scripts in batch
 */
suspend fun DslEngine<*>.validateBatch(
    scripts: List<Pair<String, String>>, // content to name pairs
    compilationContext: DslCompilationContext = createDefaultCompilationContext(),
    executionContext: DslExecutionContext = createDefaultExecutionContext(),
    sandboxManager: SandboxManager,
    logger: IPipelineLogger
): List<DslValidationReport> {
    
    val validator = DslValidator(sandboxManager, logger)
    
    return scripts.map { (content, name) ->
        validator.validateScript(content, name, compilationContext, executionContext)
    }
}

/**
 * Validates a script and provides quick fix suggestions
 */
suspend fun DslEngine<*>.validateWithQuickFixes(
    scriptContent: String,
    scriptName: String = "script.kts",
    compilationContext: DslCompilationContext = createDefaultCompilationContext(),
    executionContext: DslExecutionContext = createDefaultExecutionContext(),
    sandboxManager: SandboxManager,
    logger: IPipelineLogger
): DslValidationReportWithFixes {
    
    val report = validateWithEnhancedReporting(
        scriptContent, scriptName, compilationContext, executionContext, sandboxManager, logger
    )
    
    val quickFixes = generateQuickFixes(report.issues, scriptContent)
    
    return DslValidationReportWithFixes(report, quickFixes)
}


/**
 * Generates quick fixes for common validation issues
 */
private fun generateQuickFixes(
    issues: List<DslValidationIssue>,
    scriptContent: String
): List<DslQuickFix> {
    val fixes = mutableListOf<DslQuickFix>()
    
    issues.forEach { issue ->
        when (issue.code) {
            "UNMATCHED_BRACES" -> {
                fixes.add(DslQuickFix(
                    title = "Add missing closing brace",
                    description = "Add a closing brace '}' to match the opening brace",
                    fixType = DslQuickFixType.ADD_TEXT,
                    targetLocation = issue.location,
                    replacementText = "}"
                ))
            }
            
            "UNMATCHED_PARENTHESES" -> {
                fixes.add(DslQuickFix(
                    title = "Add missing closing parenthesis",
                    description = "Add a closing parenthesis ')' to match the opening parenthesis",
                    fixType = DslQuickFixType.ADD_TEXT,
                    targetLocation = issue.location,
                    replacementText = ")"
                ))
            }
            
            "DANGEROUS_API_USAGE" -> {
                when {
                    issue.message.contains("System.exit(") -> {
                        fixes.add(DslQuickFix(
                            title = "Remove System.exit() call",
                            description = "Remove the dangerous System.exit() call",
                            fixType = DslQuickFixType.REMOVE_TEXT,
                            targetLocation = issue.location,
                            replacementText = "// Removed unsafe System.exit() call"
                        ))
                    }
                    issue.message.contains("Runtime.getRuntime()") -> {
                        fixes.add(DslQuickFix(
                            title = "Replace Runtime.getRuntime() with safe alternative",
                            description = "Use DSL-provided runtime functions instead",
                            fixType = DslQuickFixType.REPLACE_TEXT,
                            targetLocation = issue.location,
                            replacementText = "// Use DSL runtime functions instead"
                        ))
                    }
                }
            }
            
            "BLOCKING_SLEEP_DETECTED" -> {
                fixes.add(DslQuickFix(
                    title = "Replace Thread.sleep() with delay()",
                    description = "Use coroutine delay() instead of blocking Thread.sleep()",
                    fixType = DslQuickFixType.REPLACE_TEXT,
                    targetLocation = issue.location,
                    replacementText = "delay(" // Partial replacement suggestion
                ))
            }
            
            "MISSING_PIPELINE_BLOCK" -> {
                fixes.add(DslQuickFix(
                    title = "Add pipeline block",
                    description = "Add a basic pipeline block structure",
                    fixType = DslQuickFixType.ADD_TEXT,
                    targetLocation = DslLocation(1, 1),
                    replacementText = """
                        pipeline {
                            agent any
                            stages {
                                stage('Build') {
                                    steps {
                                        // Add your build steps here
                                    }
                                }
                            }
                        }
                    """.trimIndent()
                ))
            }
        }
    }
    
    return fixes
}

/**
 * Validation report with quick fixes
 */
data class DslValidationReportWithFixes(
    val report: DslValidationReport,
    val quickFixes: List<DslQuickFix>
) {
    fun getFormattedReportWithFixes(): String {
        val sb = StringBuilder()
        sb.append(report.getFormattedReport())
        
        if (quickFixes.isNotEmpty()) {
            sb.appendLine("\n🔧 Quick Fixes Available:")
            quickFixes.forEachIndexed { index, fix ->
                sb.appendLine("${index + 1}. ${fix.title}")
                sb.appendLine("   ${fix.description}")
                fix.targetLocation?.let { loc ->
                    sb.appendLine("   📍 Location: ${loc.file}:${loc.line}:${loc.column}")
                }
                sb.appendLine()
            }
        }
        
        return sb.toString()
    }
}

/**
 * Quick fix suggestion
 */
data class DslQuickFix(
    val title: String,
    val description: String,
    val fixType: DslQuickFixType,
    val targetLocation: DslLocation?,
    val replacementText: String,
    val additionalChanges: List<DslTextChange> = emptyList()
)

/**
 * Types of quick fixes
 */
enum class DslQuickFixType {
    ADD_TEXT,
    REMOVE_TEXT,
    REPLACE_TEXT,
    MOVE_TEXT,
    REFORMAT
}

/**
 * Text change for quick fixes
 */
data class DslTextChange(
    val location: DslLocation,
    val changeType: DslQuickFixType,
    val newText: String
)

/**
 * Utility function to validate script content and log results
 */
suspend fun validateAndLogResults(
    engine: DslEngine<*>,
    scriptContent: String,
    scriptName: String,
    sandboxManager: SandboxManager,
    logger: IPipelineLogger
): Boolean {
    val report = engine.validateWithEnhancedReporting(
        scriptContent = scriptContent,
        scriptName = scriptName,
        sandboxManager = sandboxManager,
        logger = logger
    )
    
    logger.info(report.getFormattedReport())
    
    return report.isValid
}

/**
 * Utility function to validate a script file and log results
 */
suspend fun validateFileAndLogResults(
    engine: DslEngine<*>,
    scriptFile: java.io.File,
    sandboxManager: SandboxManager,
    logger: IPipelineLogger
): Boolean {
    val report = engine.validateFileWithEnhancedReporting(
        scriptFile = scriptFile,
        sandboxManager = sandboxManager,
        logger = logger
    )
    
    logger.info(report.getFormattedReport())
    
    return report.isValid
}