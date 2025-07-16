package dev.rubentxu.pipeline.steps.plugin.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.time.Instant
import java.util.regex.Pattern

/**
 * Structured logger for the Pipeline Steps Compiler Plugin.
 * 
 * Provides:
 * - JSON structured logging with contextual information
 * - PII filtering for sensitive information
 * - Performance monitoring capabilities
 * - Conditional logging based on debug flags
 * - Zero-overhead when logging is disabled
 */
object StructuredLogger {
    
    private val logger = KotlinLogging.logger {}
    
    // PII patterns for sanitization
    private val piiPatterns = listOf(
        Pattern.compile("password\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("token\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("secret\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("key\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"), // Email
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b") // SSN pattern
    )
    
    /**
     * Current debug state - can be configured via compiler configuration
     */
    @Volatile
    var isDebugEnabled: Boolean = false
        private set
    
    /**
     * Configure logging based on compiler configuration
     */
    fun configure(configuration: CompilerConfiguration) {
        // Check for debug flags in compiler configuration
        isDebugEnabled = configuration.get(
            org.jetbrains.kotlin.config.CompilerConfigurationKey.create<Boolean>("debug"), 
            false
        ) || System.getProperty("dev.rubentxu.pipeline.steps.debug", "false").toBoolean()
    }
    
    /**
     * Log plugin lifecycle events
     */
    fun logPluginEvent(event: PluginEvent, details: Map<String, Any> = emptyMap()) {
        if (isDebugEnabled) {
            logger.info {
                formatStructuredEvent("plugin_lifecycle", mapOf(
                    "event" to event.name,
                    "timestamp" to Instant.now().toString(),
                    "plugin_version" to "2.0-SNAPSHOT"
                ) + details)
            }
        }
    }
    
    /**
     * Log step transformation events
     */
    fun logStepTransformation(
        stepName: String, 
        phase: TransformationPhase,
        success: Boolean,
        details: Map<String, Any> = emptyMap()
    ) {
        if (isDebugEnabled) {
            logger.info {
                formatStructuredEvent("step_transformation", mapOf(
                    "step_name" to stepName,
                    "phase" to phase.name,
                    "success" to success,
                    "timestamp" to Instant.now().toString()
                ) + details)
            }
        }
    }
    
    /**
     * Log performance metrics
     */
    fun logPerformanceMetric(
        operation: String,
        durationMs: Long,
        metadata: Map<String, Any> = emptyMap()
    ) {
        if (isDebugEnabled) {
            logger.info {
                formatStructuredEvent("performance_metric", mapOf(
                    "operation" to operation,
                    "duration_ms" to durationMs,
                    "timestamp" to Instant.now().toString()
                ) + metadata)
            }
        }
    }
    
    /**
     * Log validation events
     */
    fun logValidation(
        functionName: String,
        validationType: ValidationType,
        success: Boolean,
        message: String? = null
    ) {
        if (isDebugEnabled) {
            if (success) {
                logger.info {
                    formatStructuredEvent("validation", mapOf(
                        "function_name" to functionName,
                        "validation_type" to validationType.name,
                        "success" to success,
                        "message" to (message?.let { sanitizeMessage(it) } ?: ""),
                        "timestamp" to Instant.now().toString()
                    ))
                }
            } else {
                logger.warn {
                    formatStructuredEvent("validation", mapOf(
                        "function_name" to functionName,
                        "validation_type" to validationType.name,
                        "success" to success,
                        "message" to (message?.let { sanitizeMessage(it) } ?: ""),
                        "timestamp" to Instant.now().toString()
                    ))
                }
            }
        }
    }
    
    /**
     * Log errors with full context
     */
    fun logError(
        operation: String,
        error: Throwable,
        context: Map<String, Any> = emptyMap()
    ) {
        logger.error(error) {
            formatStructuredEvent("error", mapOf(
                "operation" to operation,
                "error_type" to (error::class.simpleName ?: "Unknown"),
                "error_message" to sanitizeMessage(error.message ?: "Unknown error"),
                "timestamp" to Instant.now().toString()
            ) + context)
        }
    }
    
    /**
     * Log warnings with context
     */
    fun logWarning(
        operation: String,
        message: String,
        context: Map<String, Any> = emptyMap()
    ) {
        if (isDebugEnabled) {
            logger.warn {
                formatStructuredEvent("warning", mapOf(
                    "operation" to operation,
                    "message" to sanitizeMessage(message),
                    "timestamp" to Instant.now().toString()
                ) + context)
            }
        }
    }
    
    /**
     * Format a structured log event for better readability
     */
    private fun formatStructuredEvent(type: String, data: Map<String, Any>): String {
        val baseEvent = mapOf(
            "event_type" to type,
            "plugin" to "pipeline-steps-compiler",
            "kotlin_version" to System.getProperty("kotlin.version", "unknown")
        ) + data
        
        // Simple structured format for readability
        return baseEvent.entries.joinToString(", ") { (key, value) ->
            "$key=$value"
        }
    }
    
    /**
     * Sanitize message content to remove PII
     */
    private fun sanitizeMessage(message: String): String {
        return piiPatterns.fold(message) { msg, pattern ->
            pattern.matcher(msg).replaceAll { matchResult ->
                val fullMatch = matchResult.group(0)
                val beforeEquals = fullMatch.substringBefore("=")
                if (beforeEquals.length < fullMatch.length) {
                    "$beforeEquals=[REDACTED]"
                } else {
                    "[REDACTED]"
                }
            }
        }
    }
    
    /**
     * Measure execution time and log performance
     */
    inline fun <T> measureAndLog(operation: String, block: () -> T): T {
        val startTime = System.currentTimeMillis()
        return try {
            val result = block()
            val duration = System.currentTimeMillis() - startTime
            logPerformanceMetric(operation, duration, mapOf("success" to true))
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logPerformanceMetric(operation, duration, mapOf("success" to false))
            logError(operation, e)
            throw e
        }
    }
}

/**
 * Plugin lifecycle events
 */
enum class PluginEvent {
    PLUGIN_REGISTERED,
    PLUGIN_CONFIGURED,
    FIR_EXTENSION_REGISTERED,
    IR_TRANSFORMATION_STARTED,
    IR_TRANSFORMATION_COMPLETED,
    DSL_GENERATION_STARTED,
    DSL_GENERATION_COMPLETED
}

/**
 * Transformation phases
 */
enum class TransformationPhase {
    DETECTION,
    PARAMETER_ANALYSIS,
    CONTEXT_INJECTION,
    SIGNATURE_MODIFICATION,
    CALL_SITE_TRANSFORMATION,
    VALIDATION
}

/**
 * Validation types
 */
enum class ValidationType {
    STEP_ANNOTATION,
    SUSPEND_FUNCTION,
    PARAMETER_TYPES,
    NAMING_CONVENTION,
    CONTEXT_PARAMETER,
    SECURITY_LEVEL
}