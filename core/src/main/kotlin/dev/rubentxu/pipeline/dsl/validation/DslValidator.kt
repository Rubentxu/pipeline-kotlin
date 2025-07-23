package dev.rubentxu.pipeline.dsl.validation

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.security.SandboxManager

/**
 * Enhanced DSL validation system that provides comprehensive validation
 * with detailed error messages and security policy checks.
 */
class DslValidator(
    private val sandboxManager: SandboxManager,
    private val logger: ILogger
) {
    
    /**
     * Validates DSL script content with comprehensive checks
     */
    suspend fun validateScript(
        scriptContent: String,
        scriptName: String = "script.kts",
        context: DslCompilationContext = DslCompilationContext(),
        executionContext: DslExecutionContext = DslExecutionContext()
    ): DslValidationReport {
        
        logger.debug("🔍 Starting comprehensive DSL validation for: $scriptName")
        
        val issues = mutableListOf<DslValidationIssue>()
        val warnings = mutableListOf<DslValidationWarning>()
        
        // 1. Syntax validation
        val syntaxIssues = validateSyntax(scriptContent, scriptName)
        issues.addAll(syntaxIssues)
        
        // 2. Security policy validation
        val securityValidation = validateSecurityPolicy(scriptContent, context, executionContext)
        issues.addAll(securityValidation.issues)
        warnings.addAll(securityValidation.warnings)
        
        // 3. Resource limits validation
        val resourceIssues = validateResourceLimits(executionContext.resourceLimits)
        issues.addAll(resourceIssues)
        
        // 4. Dependencies validation
        val dependencyIssues = validateDependencies(context)
        issues.addAll(dependencyIssues)
        
        // 5. DSL-specific validation
        val dslIssues = validateDslSpecificRules(scriptContent, scriptName)
        issues.addAll(dslIssues)
        
        // 6. Performance validation
        val performanceWarnings = validatePerformance(scriptContent, executionContext)
        warnings.addAll(performanceWarnings)
        
        val isValid = issues.isEmpty() || issues.all { it.severity != DslValidationSeverity.ERROR }
        
        logger.info("✅ DSL validation completed for $scriptName - Valid: $isValid, Issues: ${issues.size}, Warnings: ${warnings.size}")
        
        return DslValidationReport(
            scriptName = scriptName,
            isValid = isValid,
            issues = issues,
            warnings = warnings,
            validationTimeMs = System.currentTimeMillis(), // Simplified timing
            recommendations = generateRecommendations(issues, warnings)
        )
    }
    
    /**
     * Validates script syntax and basic structure
     */
    private suspend fun validateSyntax(scriptContent: String, scriptName: String): List<DslValidationIssue> {
        val issues = mutableListOf<DslValidationIssue>()
        
        // Check for basic syntax issues
        if (scriptContent.isBlank()) {
            issues.add(DslValidationIssue(
                code = "EMPTY_SCRIPT",
                message = "Script content is empty",
                severity = DslValidationSeverity.ERROR,
                location = DslLocation(1, 1, scriptName),
                suggestion = "Add some DSL content to your script"
            ))
        }
        
        // Check for unmatched braces
        val openBraces = scriptContent.count { it == '{' }
        val closeBraces = scriptContent.count { it == '}' }
        if (openBraces != closeBraces) {
            issues.add(DslValidationIssue(
                code = "UNMATCHED_BRACES",
                message = "Unmatched braces detected: $openBraces opening, $closeBraces closing",
                severity = DslValidationSeverity.ERROR,
                location = findLastBraceLocation(scriptContent, scriptName),
                suggestion = "Check that all opening braces '{' have corresponding closing braces '}'"
            ))
        }
        
        // Check for unmatched parentheses
        val openParens = scriptContent.count { it == '(' }
        val closeParens = scriptContent.count { it == ')' }
        if (openParens != closeParens) {
            issues.add(DslValidationIssue(
                code = "UNMATCHED_PARENTHESES",
                message = "Unmatched parentheses detected: $openParens opening, $closeParens closing",
                severity = DslValidationSeverity.ERROR,
                location = findLastParenLocation(scriptContent, scriptName),
                suggestion = "Check that all opening parentheses '(' have corresponding closing parentheses ')'"
            ))
        }
        
        // Check for potential encoding issues
        if (scriptContent.contains("�")) {
            issues.add(DslValidationIssue(
                code = "ENCODING_ISSUE",
                message = "Potential character encoding issue detected",
                severity = DslValidationSeverity.WARNING,
                location = DslLocation(1, 1, scriptName),
                suggestion = "Ensure your script is saved with UTF-8 encoding"
            ))
        }
        
        return issues
    }
    
    /**
     * Validates security policies and constraints
     */
    private suspend fun validateSecurityPolicy(
        scriptContent: String,
        context: DslCompilationContext,
        executionContext: DslExecutionContext
    ): SecurityValidationResult {
        val issues = mutableListOf<DslValidationIssue>()
        val warnings = mutableListOf<DslValidationWarning>()
        
        // Validate sandbox policy
        val policyValidation = sandboxManager.validateSecurityPolicy(executionContext)
        if (!policyValidation.isValid) {
            issues.addAll(policyValidation.issues.map { issue ->
                DslValidationIssue(
                    code = "SECURITY_POLICY_VIOLATION",
                    message = issue,
                    severity = DslValidationSeverity.ERROR,
                    suggestion = "Adjust your security policy configuration to comply with sandbox requirements"
                )
            })
        }
        
        // Check for dangerous patterns
        val dangerousPatterns = listOf(
            "Runtime.getRuntime()" to "Direct runtime access is not allowed",
            "System.exit(" to "System.exit() calls are not allowed",
            "ProcessBuilder" to "Process creation is restricted",
            "Thread(" to "Direct thread creation should be avoided",
            "Class.forName(" to "Dynamic class loading is restricted",
            "URLClassLoader" to "Custom class loaders are not allowed",
            "java.lang.reflect" to "Reflection usage is restricted"
        )
        
        dangerousPatterns.forEach { (pattern, message) ->
            if (scriptContent.contains(pattern)) {
                val location = findPatternLocation(scriptContent, pattern)
                issues.add(DslValidationIssue(
                    code = "DANGEROUS_API_USAGE",
                    message = message,
                    severity = DslValidationSeverity.ERROR,
                    location = location,
                    suggestion = "Remove or replace this dangerous API call with a safer alternative"
                ))
            }
        }
        
        // Check for suspicious network patterns
        val networkPatterns = listOf(
            "java.net.Socket" to "Direct socket access",
            "java.net.ServerSocket" to "Server socket creation",
            "java.net.URL(" to "URL creation for network access",
            "HttpURLConnection" to "HTTP connection usage"
        )
        
        networkPatterns.forEach { (pattern, description) ->
            if (scriptContent.contains(pattern)) {
                val location = findPatternLocation(scriptContent, pattern)
                if (context.securityPolicy.allowNetworkAccess) {
                    warnings.add(DslValidationWarning(
                        code = "NETWORK_ACCESS_DETECTED",
                        message = "$description detected in script",
                        location = location,
                        suggestion = "Ensure network access is necessary and secure"
                    ))
                } else {
                    issues.add(DslValidationIssue(
                        code = "NETWORK_ACCESS_DENIED",
                        message = "$description is not allowed by security policy",
                        severity = DslValidationSeverity.ERROR,
                        location = location,
                        suggestion = "Enable network access in security policy or remove network operations"
                    ))
                }
            }
        }
        
        return SecurityValidationResult(issues, warnings)
    }
    
    /**
     * Validates resource limits configuration
     */
    private fun validateResourceLimits(limits: DslResourceLimits?): List<DslValidationIssue> {
        val issues = mutableListOf<DslValidationIssue>()
        
        if (limits == null) {
            issues.add(DslValidationIssue(
                code = "NO_RESOURCE_LIMITS",
                message = "No resource limits specified",
                severity = DslValidationSeverity.WARNING,
                suggestion = "Consider adding resource limits to prevent resource exhaustion"
            ))
            return issues
        }
        
        // Validate memory limits
        limits.maxMemoryMb?.let { memory ->
            if (memory > 2048) {
                issues.add(DslValidationIssue(
                    code = "EXCESSIVE_MEMORY_LIMIT",
                    message = "Memory limit of ${memory}MB is very high",
                    severity = DslValidationSeverity.WARNING,
                    suggestion = "Consider reducing memory limit to a more reasonable value (< 2048MB)"
                ))
            }
            if (memory < 64) {
                issues.add(DslValidationIssue(
                    code = "INSUFFICIENT_MEMORY_LIMIT",
                    message = "Memory limit of ${memory}MB might be too low",
                    severity = DslValidationSeverity.WARNING,
                    suggestion = "Consider increasing memory limit to at least 64MB"
                ))
            }
        }
        
        // Validate CPU time limits
        limits.maxCpuTimeMs?.let { cpuTime ->
            if (cpuTime > 300_000) { // 5 minutes
                issues.add(DslValidationIssue(
                    code = "EXCESSIVE_CPU_TIME",
                    message = "CPU time limit of ${cpuTime}ms is very high",
                    severity = DslValidationSeverity.WARNING,
                    suggestion = "Consider reducing CPU time limit to prevent long-running scripts"
                ))
            }
        }
        
        // Validate thread limits
        limits.maxThreads?.let { threads ->
            if (threads > 20) {
                issues.add(DslValidationIssue(
                    code = "EXCESSIVE_THREAD_LIMIT",
                    message = "Thread limit of $threads is very high",
                    severity = DslValidationSeverity.WARNING,
                    suggestion = "Consider reducing thread limit to prevent resource contention"
                ))
            }
            if (threads < 1) {
                issues.add(DslValidationIssue(
                    code = "INVALID_THREAD_LIMIT",
                    message = "Thread limit must be at least 1",
                    severity = DslValidationSeverity.ERROR,
                    suggestion = "Set thread limit to at least 1"
                ))
            }
        }
        
        return issues
    }
    
    /**
     * Validates dependencies and imports
     */
    private fun validateDependencies(context: DslCompilationContext): List<DslValidationIssue> {
        val issues = mutableListOf<DslValidationIssue>()
        
        // Check for blocked packages
        context.imports.forEach { import ->
            context.blockedPackages.forEach { blocked ->
                if (import.startsWith(blocked.removeSuffix("*"))) {
                    issues.add(DslValidationIssue(
                        code = "BLOCKED_PACKAGE_IMPORT",
                        message = "Import '$import' is blocked by security policy",
                        severity = DslValidationSeverity.ERROR,
                        suggestion = "Remove the blocked import or update security policy"
                    ))
                }
            }
        }
        
        // Check for circular dependencies (basic check)
        val duplicateImports = context.imports.groupingBy { it }.eachCount().filter { it.value > 1 }
        duplicateImports.forEach { (import, count) ->
            issues.add(DslValidationIssue(
                code = "DUPLICATE_IMPORT",
                message = "Import '$import' appears $count times",
                severity = DslValidationSeverity.WARNING,
                suggestion = "Remove duplicate imports to clean up the script"
            ))
        }
        
        return issues
    }
    
    /**
     * Validates DSL-specific rules and conventions
     */
    private fun validateDslSpecificRules(scriptContent: String, scriptName: String): List<DslValidationIssue> {
        val issues = mutableListOf<DslValidationIssue>()
        
        // Check for pipeline-specific patterns
        if (scriptName.endsWith(".pipeline.kts")) {
            
            // Pipeline scripts should have pipeline blocks
            if (!scriptContent.contains("pipeline") && !scriptContent.contains("Pipeline")) {
                issues.add(DslValidationIssue(
                    code = "MISSING_PIPELINE_BLOCK",
                    message = "Pipeline script should contain a pipeline block",
                    severity = DslValidationSeverity.WARNING,
                    location = DslLocation(1, 1, scriptName),
                    suggestion = "Add a pipeline { ... } block to define your pipeline"
                ))
            }
            
            // Check for common pipeline elements
            val pipelineElements = listOf("stage", "steps", "environment", "agent")
            val foundElements = pipelineElements.filter { scriptContent.contains(it) }
            if (foundElements.isEmpty()) {
                issues.add(DslValidationIssue(
                    code = "EMPTY_PIPELINE_STRUCTURE",
                    message = "Pipeline script appears to be empty or incomplete",
                    severity = DslValidationSeverity.WARNING,
                    location = DslLocation(1, 1, scriptName),
                    suggestion = "Add pipeline elements like stages, steps, or environment configuration"
                ))
            }
        }
        
        // Check for common DSL anti-patterns
        if (scriptContent.contains("Thread.sleep(")) {
            val location = findPatternLocation(scriptContent, "Thread.sleep(")
            issues.add(DslValidationIssue(
                code = "BLOCKING_SLEEP_DETECTED",
                message = "Thread.sleep() can cause performance issues",
                severity = DslValidationSeverity.WARNING,
                location = location,
                suggestion = "Consider using coroutines delay() instead of Thread.sleep()"
            ))
        }
        
        return issues
    }
    
    /**
     * Validates performance characteristics
     */
    private fun validatePerformance(scriptContent: String, context: DslExecutionContext): List<DslValidationWarning> {
        val warnings = mutableListOf<DslValidationWarning>()
        
        // Check script size
        if (scriptContent.length > 50_000) {
            warnings.add(DslValidationWarning(
                code = "LARGE_SCRIPT_SIZE",
                message = "Script is very large (${scriptContent.length} characters)",
                suggestion = "Consider breaking the script into smaller, reusable components"
            ))
        }
        
        // Check for potential performance issues
        val performancePatterns = listOf(
            "while(true)" to "Infinite loops can cause performance issues",
            "for(;;)" to "Infinite loops can cause performance issues",
            "recursive" to "Recursive functions without proper termination can cause stack overflow"
        )
        
        performancePatterns.forEach { (pattern, message) ->
            if (scriptContent.contains(pattern)) {
                val location = findPatternLocation(scriptContent, pattern)
                warnings.add(DslValidationWarning(
                    code = "PERFORMANCE_CONCERN",
                    message = message,
                    location = location,
                    suggestion = "Review loop conditions and ensure proper termination"
                ))
            }
        }
        
        return warnings
    }
    
    /**
     * Generates recommendations based on validation results
     */
    private fun generateRecommendations(
        issues: List<DslValidationIssue>,
        warnings: List<DslValidationWarning>
    ): List<DslValidationRecommendation> {
        val recommendations = mutableListOf<DslValidationRecommendation>()
        
        // Group issues by type for better recommendations
        val errorsByCode = issues.groupBy { it.code }
        
        if (errorsByCode.containsKey("DANGEROUS_API_USAGE")) {
            recommendations.add(DslValidationRecommendation(
                type = DslRecommendationType.SECURITY,
                title = "Secure API Usage",
                description = "Replace dangerous API calls with safer alternatives",
                actionItems = listOf(
                    "Remove direct system calls",
                    "Use provided DSL functions instead",
                    "Consider using the security sandbox"
                ),
                priority = DslRecommendationPriority.HIGH
            ))
        }
        
        if (warnings.any { it.code == "PERFORMANCE_CONCERN" }) {
            recommendations.add(DslValidationRecommendation(
                type = DslRecommendationType.PERFORMANCE,
                title = "Performance Optimization",
                description = "Optimize script performance",
                actionItems = listOf(
                    "Review loop conditions",
                    "Add proper termination conditions",
                    "Consider using coroutines for async operations"
                ),
                priority = DslRecommendationPriority.MEDIUM
            ))
        }
        
        if (errorsByCode.containsKey("NO_RESOURCE_LIMITS")) {
            recommendations.add(DslValidationRecommendation(
                type = DslRecommendationType.CONFIGURATION,
                title = "Resource Management",
                description = "Configure resource limits for better stability",
                actionItems = listOf(
                    "Set memory limits",
                    "Configure CPU time limits",
                    "Limit thread usage"
                ),
                priority = DslRecommendationPriority.MEDIUM
            ))
        }
        
        return recommendations
    }
    
    // Helper methods
    private fun findLastBraceLocation(content: String, fileName: String): DslLocation {
        val lines = content.split('\n')
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            val braceIndex = line.indexOfLast { it == '{' || it == '}' }
            if (braceIndex != -1) {
                return DslLocation(i + 1, braceIndex + 1, fileName)
            }
        }
        return DslLocation(1, 1, fileName)
    }
    
    private fun findLastParenLocation(content: String, fileName: String): DslLocation {
        val lines = content.split('\n')
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            val parenIndex = line.indexOfLast { it == '(' || it == ')' }
            if (parenIndex != -1) {
                return DslLocation(i + 1, parenIndex + 1, fileName)
            }
        }
        return DslLocation(1, 1, fileName)
    }
    
    private fun findPatternLocation(content: String, pattern: String): DslLocation {
        val lines = content.split('\n')
        for (i in lines.indices) {
            val line = lines[i]
            val index = line.indexOf(pattern)
            if (index != -1) {
                return DslLocation(i + 1, index + 1)
            }
        }
        return DslLocation(1, 1)
    }
}

/**
 * Result of security validation
 */
data class SecurityValidationResult(
    val issues: List<DslValidationIssue>,
    val warnings: List<DslValidationWarning>
)

/**
 * Comprehensive validation report
 */
data class DslValidationReport(
    val scriptName: String,
    val isValid: Boolean,
    val issues: List<DslValidationIssue>,
    val warnings: List<DslValidationWarning>,
    val validationTimeMs: Long,
    val recommendations: List<DslValidationRecommendation>
) {
    val errorCount: Int get() = issues.count { it.severity == DslValidationSeverity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == DslValidationSeverity.WARNING } + warnings.size
    val infoCount: Int get() = issues.count { it.severity == DslValidationSeverity.INFO }
    
    fun getFormattedReport(): String {
        val sb = StringBuilder()
        sb.appendLine("🔍 DSL Validation Report for: $scriptName")
        sb.appendLine("├── Valid: ${if (isValid) "✅ Yes" else "❌ No"}")
        sb.appendLine("├── Errors: $errorCount")
        sb.appendLine("├── Warnings: $warningCount")
        sb.appendLine("├── Info: $infoCount")
        sb.appendLine("└── Validation Time: ${validationTimeMs}ms")
        
        if (issues.isNotEmpty()) {
            sb.appendLine("\n📋 Issues Found:")
            issues.forEach { issue ->
                val icon = when (issue.severity) {
                    DslValidationSeverity.ERROR -> "🚨"
                    DslValidationSeverity.WARNING -> "⚠️"
                    DslValidationSeverity.INFO -> "ℹ️"
                }
                sb.appendLine("$icon ${issue.severity.name}: ${issue.message}")
                issue.location?.let { loc ->
                    sb.appendLine("   📍 Location: ${loc.file}:${loc.line}:${loc.column}")
                }
                issue.suggestion?.let { suggestion ->
                    sb.appendLine("   💡 Suggestion: $suggestion")
                }
                sb.appendLine()
            }
        }
        
        if (warnings.isNotEmpty()) {
            sb.appendLine("⚠️ Additional Warnings:")
            warnings.forEach { warning ->
                sb.appendLine("⚠️  ${warning.message}")
                warning.location?.let { loc ->
                    sb.appendLine("   📍 Location: ${loc.file}:${loc.line}:${loc.column}")
                }
                warning.suggestion?.let { suggestion ->
                    sb.appendLine("   💡 Suggestion: $suggestion")
                }
                sb.appendLine()
            }
        }
        
        if (recommendations.isNotEmpty()) {
            sb.appendLine("🎯 Recommendations:")
            recommendations.forEach { rec ->
                val priorityIcon = when (rec.priority) {
                    DslRecommendationPriority.HIGH -> "🔥"
                    DslRecommendationPriority.MEDIUM -> "🟡"
                    DslRecommendationPriority.LOW -> "🟢"
                }
                sb.appendLine("$priorityIcon ${rec.title}")
                sb.appendLine("   ${rec.description}")
                rec.actionItems.forEach { action ->
                    sb.appendLine("   • $action")
                }
                sb.appendLine()
            }
        }
        
        return sb.toString()
    }
}

/**
 * Validation issue with detailed information
 */
data class DslValidationIssue(
    val code: String,
    val message: String,
    val severity: DslValidationSeverity,
    val location: DslLocation? = null,
    val suggestion: String? = null,
    val relatedIssues: List<String> = emptyList()
)

/**
 * Validation warning
 */
data class DslValidationWarning(
    val code: String,
    val message: String,
    val location: DslLocation? = null,
    val suggestion: String? = null
)

/**
 * Validation recommendation
 */
data class DslValidationRecommendation(
    val type: DslRecommendationType,
    val title: String,
    val description: String,
    val actionItems: List<String>,
    val priority: DslRecommendationPriority
)

/**
 * Severity levels for validation issues
 */
enum class DslValidationSeverity {
    ERROR,
    WARNING,
    INFO
}

/**
 * Types of validation recommendations
 */
enum class DslRecommendationType {
    SECURITY,
    PERFORMANCE,
    CONFIGURATION,
    BEST_PRACTICES,
    MAINTENANCE
}

/**
 * Priority levels for recommendations
 */
enum class DslRecommendationPriority {
    HIGH,
    MEDIUM,
    LOW
}