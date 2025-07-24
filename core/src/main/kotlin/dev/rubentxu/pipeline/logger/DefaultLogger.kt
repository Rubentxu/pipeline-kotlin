package dev.rubentxu.pipeline.logger

import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.model.LogSource
import dev.rubentxu.pipeline.logger.model.LoggingContext
import dev.rubentxu.pipeline.logger.model.MutableLogEvent
import dev.rubentxu.pipeline.logger.pool.ObjectPool
import org.jctools.queues.MpscUnboundedArrayQueue

/**
 * High-performance logger implementation optimized for push-based architecture.
 */
class DefaultLogger(
    override val name: String,
    private val manager: DefaultLoggerManager,
    private val eventQueue: MpscUnboundedArrayQueue<MutableLogEvent>,
    private val eventPool: ObjectPool<MutableLogEvent>
) : ILogger {

    override fun info(message: String, data: Map<String, String>) {
        logWithLevel(LogLevel.INFO, message, null, data)
    }

    override fun warn(message: String, data: Map<String, String>) {
        logWithLevel(LogLevel.WARN, message, null, data)
    }

    override fun debug(message: String, data: Map<String, String>) {
        logWithLevel(LogLevel.DEBUG, message, null, data)
    }

    override fun error(message: String, throwable: Throwable?, data: Map<String, String>) {
        logWithLevel(LogLevel.ERROR, message, throwable, data)
    }

    override fun trace(message: String, data: Map<String, String>) {
        logWithLevel(LogLevel.TRACE, message, null, data)
    }

    override fun critical(message: String, data: Map<String, String>) {
        logWithLevel(LogLevel.ERROR, message, null, data)
    }

    override fun system(message: String) {
        logWithLevel(
            LogLevel.INFO,
            message,
            null,
            mapOf("system" to "true")
        )
    }

    override fun log(
        level: LogLevel,
        message: String,
        exception: Throwable?
    ) {
        logWithLevel(level, message, exception, emptyMap())
    }

    override fun log(
        level: LogLevel,
        message: String,
        contextData: Map<String, String>,
        exception: Throwable?
    ) {
        logWithLevel(level, message, exception, contextData)
    }

    /**
     * Core logging method with optimized object pooling and zero-copy queuing.
     * Now works in both suspend and non-suspend contexts by safely capturing correlation ID when available.
     */
    private fun logWithLevel(
        level: LogLevel,
        message: String,
        exception: Throwable?,
        data: Map<String, String>
    ) {
        try {
            // Step 1: Acquire from pool (potentially zero allocation)
            val event = eventPool.acquire()

            // Step 2: Extract correlation ID and context data from LoggingContext (best effort)
            val context = getCurrentLoggingContext()
            val correlationId = context?.correlationId

            // Merge context data from LoggingContext with provided data
            val allContextData = mutableMapOf<String, String>()
            context?.let { ctx ->
                ctx.userId?.let { allContextData["userId"] = it }
                ctx.sessionId?.let { allContextData["sessionId"] = it }
                allContextData.putAll(ctx.customData)
            }
            allContextData.putAll(data)

            // Step 3: Populate event with new data (efficient mutable operations)
            event.populate(
                timestamp = System.currentTimeMillis(),
                level = level,
                loggerName = name,
                message = message,
                correlationId = correlationId,
                contextData = allContextData,
                exception = exception,
                source = LogSource.LOGGER
            )

            // Step 4: Queue event (lock-free, handled by manager)
            if (!manager.queueLogEvent(event)) {
                // Queue full - event already returned to pool by manager
                // Fallback to console to ensure message is not lost
                fallbackToConsole(level, message, exception, data)
            }
        } catch (e: Exception) {
            // Error in logging path - fall back to simple console output
            fallbackToConsole(level, message, exception, data)
        }
    }

    /**
     * Safely attempts to get the current LoggingContext without requiring suspend context.
     * Returns null if not in a coroutine context or if context is not available.
     */
    private fun getCurrentLoggingContext(): LoggingContext? {
        return try {
            // Try to get the context if we're in a coroutine environment
            // This uses runBlocking which should only be called briefly for context access
            kotlinx.coroutines.runBlocking {
                LoggingContext.current()
            }
        } catch (e: Exception) {
            // Not in coroutine context or context unavailable - return null
            // This is expected behavior when logging from non-suspend contexts
            null
        }
    }

    override fun <T> prettyPrint(level: LogLevel, obj: T) {
        val formattedMessage = prettyPrintObject(obj)
        log(level, formattedMessage)
    }

    override fun errorBanner(messages: List<String>) {
        val bannerMessage = createBanner(messages)
        error(bannerMessage)
    }

    /**
     * Pretty-prints an object with structured formatting.
     */
    private fun <T> prettyPrintObject(obj: T, level: Int = 0, sb: StringBuilder = StringBuilder()): String {
        fun indent(lev: Int) = sb.append("  ".repeat(lev))

        when (obj) {
            is Map<*, *> -> {
                sb.append("{\n")
                obj.forEach { (name, value) ->
                    indent(level + 1).append(name)
                    if (value is Map<*, *>) {
                        sb.append(" ")
                    } else {
                        sb.append(" = ")
                    }
                    sb.append(prettyPrintObject(value, level + 1)).append("\n")
                }
                indent(level).append("}")
            }

            is List<*> -> {
                sb.append("[\n")
                obj.forEachIndexed { index, value ->
                    indent(level + 1)
                    sb.append(prettyPrintObject(value, level + 1))
                    if (index != obj.size - 1) {
                        sb.append(",")
                    }
                    sb.append("\n")
                }
                indent(level).append("]")
            }

            is String -> sb.append('"').append(obj).append('"')
            else -> sb.append(obj)
        }
        return sb.toString()
    }

    /**
     * Creates a formatted banner with the specified messages.
     */
    private fun createBanner(messages: List<String>): String {
        val messageLines = msgFlatten(mutableListOf(), messages.filter { it.isNotEmpty() })
        val maxLength = messageLines.maxOf { it.length }
        val separator = "=".repeat(maxLength + 7)
        return """
        |
        |   $separator
        |       ${messageLines.joinToString("\n       ")} 
        |   $separator
        |   
        """.trimMargin()
    }

    /**
     * Flattens nested message structures into a single list.
     */
    private fun msgFlatten(list: MutableList<String>, msgs: Any): List<String> {
        when (msgs) {
            is String -> list.add(msgs)
            is List<*> -> msgs.forEach { msg -> msgFlatten(list, msg ?: "") }
        }
        return list
    }

    /**
     * Fallback console output when the high-performance path fails.
     */
    private fun fallbackToConsole(
        level: LogLevel,
        message: String,
        exception: Throwable?,
        data: Map<String, String>
    ) {
        val timestamp = System.currentTimeMillis()
        val contextPart = if (data.isNotEmpty()) " $data" else ""
        val exceptionPart = exception?.let { "\n${it.stackTraceToString()}" } ?: ""

        System.err.println("[FALLBACK] $timestamp ${level.name.padEnd(5)} $name$contextPart - $message$exceptionPart")
    }
}