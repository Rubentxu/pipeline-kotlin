package dev.rubentxu.pipeline.steps.enhanced

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel
import dev.rubentxu.pipeline.context.steps.LocalUnifiedStepContext
import dev.rubentxu.pipeline.logger.model.LogLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Phase 4: Enhanced Built-in Steps using UnifiedStepContext
 * 
 * These @Step functions demonstrate the new architecture:
 * - Automatic context injection via LocalUnifiedStepContext.current
 * - Enhanced logging and error handling
 * - Structured parameter handling
 * - Improved connascence patterns
 */

/**
 * Enhanced echo step with structured logging
 */
@Step(
    name = "enhancedEcho",
    description = "Enhanced echo with structured logging and context awareness",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun enhancedEcho(message: String) {
    val context = LocalUnifiedStepContext.current
    
    context.logger.logStructured(
        LogLevel.INFO,
        message,
        mapOf(
            "stepName" to "enhancedEcho",
            "messageLength" to message.length,
            "timestamp" to System.currentTimeMillis()
        )
    )
}

/**
 * Enhanced shell execution with improved error handling
 */
@Step(
    name = "enhancedSh",
    description = "Enhanced shell execution with comprehensive logging and error handling",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun enhancedSh(command: String, returnStdout: Boolean = false): String {
    val context = LocalUnifiedStepContext.current
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Executing shell command",
        mapOf(
            "command" to command,
            "returnStdout" to returnStdout,
            "workingDirectory" to context.workingDirectory.getCurrentDirectory().toString()
        )
    )
    
    return try {
        val result = context.sh(command, returnStdout)
        
        context.logger.logStructured(
            LogLevel.DEBUG,
            "Shell command completed successfully",
            mapOf(
                "command" to command,
                "resultLength" to result.length
            )
        )
        
        result
    } catch (exception: Exception) {
        context.logger.logStructured(
            LogLevel.ERROR,
            "Shell command failed",
            mapOf(
                "command" to command,
                "error" to (exception.message ?: "Unknown error"),
                "exceptionType" to (exception::class.simpleName ?: "Unknown")
            )
        )
        throw exception
    }
}

/**
 * Enhanced sleep with progress logging
 */
@Step(
    name = "enhancedSleep",
    description = "Enhanced sleep with progress logging and cancellation support",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun enhancedSleep(timeMillis: Long) {
    val context = LocalUnifiedStepContext.current
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Starting sleep",
        mapOf(
            "duration" to timeMillis,
            "unit" to "milliseconds"
        )
    )
    
    val startTime = System.currentTimeMillis()
    
    try {
        delay(timeMillis)
        
        val actualDuration = System.currentTimeMillis() - startTime
        context.logger.logStructured(
            LogLevel.DEBUG,
            "Sleep completed",
            mapOf(
                "requestedDuration" to timeMillis,
                "actualDuration" to actualDuration,
                "accuracy" to (actualDuration - timeMillis).toString()
            )
        )
        
    } catch (exception: Exception) {
        val interruptedDuration = System.currentTimeMillis() - startTime
        context.logger.logStructured(
            LogLevel.WARN,
            "Sleep interrupted",
            mapOf(
                "requestedDuration" to timeMillis,
                "interruptedAt" to interruptedDuration,
                "reason" to (exception.message ?: "Unknown")
            )
        )
        throw exception
    }
}

/**
 * Enhanced file operations with comprehensive validation
 */
@Step(
    name = "enhancedWriteFile",
    description = "Enhanced file writing with validation and comprehensive logging",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun enhancedWriteFile(filePath: String, content: String) {
    val context = LocalUnifiedStepContext.current
    
    // Validate input parameters
    require(filePath.isNotBlank()) { "File path cannot be blank" }
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Writing file",
        mapOf(
            "filePath" to filePath,
            "contentSize" to content.length,
            "contentType" to detectContentType(content)
        )
    )
    
    try {
        context.writeFile(filePath, content)
        
        context.logger.logStructured(
            LogLevel.INFO,
            "File written successfully",
            mapOf(
                "filePath" to filePath,
                "contentSize" to content.length
            )
        )
        
    } catch (exception: Exception) {
        context.logger.logStructured(
            LogLevel.ERROR,
            "Failed to write file",
            mapOf(
                "filePath" to filePath,
                "error" to (exception.message ?: "Unknown error"),
                "exceptionType" to (exception::class.simpleName ?: "Unknown")
            )
        )
        throw exception
    }
}

@Step(
    name = "enhancedReadFile",
    description = "Enhanced file reading with validation and comprehensive logging",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun enhancedReadFile(filePath: String): String {
    val context = LocalUnifiedStepContext.current
    
    // Validate input parameters
    require(filePath.isNotBlank()) { "File path cannot be blank" }
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Reading file",
        mapOf("filePath" to filePath)
    )
    
    return try {
        val content = context.readFile(filePath)
        
        context.logger.logStructured(
            LogLevel.DEBUG,
            "File read successfully",
            mapOf(
                "filePath" to filePath,
                "contentSize" to content.length,
                "contentType" to detectContentType(content)
            )
        )
        
        content
        
    } catch (exception: Exception) {
        context.logger.logStructured(
            LogLevel.ERROR,
            "Failed to read file",
            mapOf(
                "filePath" to filePath,
                "error" to (exception.message ?: "Unknown error"),
                "exceptionType" to (exception::class.simpleName ?: "Unknown")
            )
        )
        throw exception
    }
}

/**
 * Enhanced parameter management with type safety
 */
@Step(
    name = "enhancedSetParam",
    description = "Enhanced parameter setting with validation and type information",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun enhancedSetParam(key: String, value: Any) {
    val context = LocalUnifiedStepContext.current
    
    require(key.isNotBlank()) { "Parameter key cannot be blank" }
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Setting parameter",
        mapOf(
            "key" to key,
            "valueType" to (value::class.simpleName ?: "Unknown"),
            "valueString" to value.toString().take(100) // Truncate for logging
        )
    )
    
    context.setStepParam(key, value)
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Parameter set successfully",
        mapOf("key" to key)
    )
}

@Step(
    name = "enhancedGetParam",
    description = "Enhanced parameter retrieval with type safety and default values",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun enhancedGetParam(key: String, defaultValue: String = ""): String {
    val context = LocalUnifiedStepContext.current
    
    require(key.isNotBlank()) { "Parameter key cannot be blank" }
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Getting parameter",
        mapOf(
            "key" to key,
            "hasDefault" to (defaultValue.isNotEmpty())
        )
    )
    
    val value = context.getStepParam<String>(key) ?: defaultValue
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Parameter retrieved",
        mapOf(
            "key" to key,
            "found" to (context.getStepParam<String>(key) != null),
            "usedDefault" to (context.getStepParam<String>(key) == null && defaultValue.isNotEmpty())
        )
    )
    
    return value
}

/**
 * Enhanced environment management
 */
@Step(
    name = "enhancedSetEnv",
    description = "Enhanced environment variable setting with validation",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun enhancedSetEnv(name: String, value: String) {
    val context = LocalUnifiedStepContext.current
    
    require(name.isNotBlank()) { "Environment variable name cannot be blank" }
    require(name.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "Environment variable name must match pattern [A-Z_][A-Z0-9_]*" }
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Setting environment variable",
        mapOf(
            "name" to name,
            "valueLength" to value.length,
            "containsSensitive" to detectSensitiveContent(name, value)
        )
    )
    
    context.setStepEnvVar(name, value)
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Environment variable set successfully",
        mapOf("name" to name)
    )
}

@Step(
    name = "enhancedGetEnv",
    description = "Enhanced environment variable retrieval with default values",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun enhancedGetEnv(name: String, defaultValue: String = ""): String {
    val context = LocalUnifiedStepContext.current
    
    require(name.isNotBlank()) { "Environment variable name cannot be blank" }
    
    val value = context.getStepEnvVar(name, defaultValue)
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Environment variable retrieved",
        mapOf(
            "name" to name,
            "found" to (context.getStepEnvVar(name) != null),
            "usedDefault" to (context.getStepEnvVar(name) == null && defaultValue.isNotEmpty())
        )
    )
    
    return value
}

/**
 * Enhanced timeout execution
 */
@Step(
    name = "enhancedTimeout",
    description = "Enhanced timeout execution with progress tracking",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun <T> enhancedTimeout(timeoutMillis: Long, block: suspend () -> T): T {
    val context = LocalUnifiedStepContext.current
    
    require(timeoutMillis > 0) { "Timeout must be positive" }
    
    context.logger.logStructured(
        LogLevel.DEBUG,
        "Starting timeout execution",
        mapOf("timeoutMs" to timeoutMillis)
    )
    
    val startTime = System.currentTimeMillis()
    
    return try {
        withTimeout(timeoutMillis) {
            val result = block()
            
            val executionTime = System.currentTimeMillis() - startTime
            context.logger.logStructured(
                LogLevel.DEBUG,
                "Timeout execution completed",
                mapOf(
                    "timeoutMs" to timeoutMillis,
                    "executionTime" to executionTime,
                    "efficiency" to ((executionTime.toDouble() / timeoutMillis) * 100).toInt()
                )
            )
            
            result
        }
    } catch (exception: TimeoutCancellationException) {
        context.logger.logStructured(
            LogLevel.ERROR,
            "Timeout execution exceeded time limit",
            mapOf(
                "timeoutMs" to timeoutMillis,
                "executionTime" to (System.currentTimeMillis() - startTime)
            )
        )
        throw exception
    }
}

// === Helper Functions ===

private fun detectContentType(content: String): String {
    return when {
        content.startsWith("<?xml") -> "xml"
        content.startsWith("{") && content.endsWith("}") -> "json"
        content.startsWith("---") -> "yaml"
        content.contains("function") || content.contains("class") -> "code"
        content.lines().size > 10 -> "multiline-text"
        else -> "text"
    }
}

private fun detectSensitiveContent(name: String, value: String): Boolean {
    val sensitiveNames = listOf("PASSWORD", "SECRET", "KEY", "TOKEN", "API_KEY", "CREDENTIAL")
    val sensitiveLower = name.uppercase()
    return sensitiveNames.any { sensitiveLower.contains(it) } || 
           value.length > 50 && value.all { it.isLetterOrDigit() || it in "+=/" }
}