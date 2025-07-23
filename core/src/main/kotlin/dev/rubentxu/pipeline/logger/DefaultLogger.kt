package dev.rubentxu.pipeline.logger

import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.model.LogSource
import dev.rubentxu.pipeline.logger.model.MutableLogEvent
import dev.rubentxu.pipeline.logger.pool.ObjectPool
import org.jctools.queues.MpscUnboundedArrayQueue

/**
 * High-performance logger implementation optimized for push-based architecture.
 */
internal class DefaultLogger(
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

            // Step 2: Extract correlation ID from coroutine context if available
            val correlationId = try {
                // TODO: Extract from coroutineContext[LoggingContext]
                null
            } catch (e: Exception) {
                null
            }

            // Step 3: Populate event with new data (efficient mutable operations)
            event.populate(
                timestamp = System.currentTimeMillis(),
                level = level,
                loggerName = name,
                message = message,
                correlationId = correlationId,
                contextData = data,
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