package dev.rubentxu.pipeline.logger.fixtures

import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.logger.interfaces.LogEventConsumer
import dev.rubentxu.pipeline.logger.model.LogEvent
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.model.LoggingContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Factory for creating mock ILoggerManager implementations for testing.
 */
object MockLoggerManagerFactory {
    
    /**
     * Creates a high-performance mock logger manager for testing.
     */
    fun createHighPerformanceMock(): MockLoggerManager {
        return MockLoggerManager(
            simulateLatency = false,
            simulateErrors = false
        )
    }
    
    /**
     * Creates a mock logger manager that simulates realistic latency.
     */
    fun createRealisticMock(latencyMs: Long = 10L): MockLoggerManager {
        return MockLoggerManager(
            simulateLatency = true,
            latencyMs = latencyMs,
            simulateErrors = false
        )
    }
    
    /**
     * Creates a mock logger manager that simulates errors.
     */
    fun createErrorProneMock(errorProbability: Double = 0.1): MockLoggerManager {
        return MockLoggerManager(
            simulateLatency = false,
            simulateErrors = true,
            errorProbability = errorProbability
        )
    }
}

/**
 * Mock implementation of ILoggerManager for testing purposes.
 */
class MockLoggerManager(
    private val simulateLatency: Boolean = false,
    private val latencyMs: Long = 10L,
    private val simulateErrors: Boolean = false,
    private val errorProbability: Double = 0.1
) : ILoggerManager {
    
    private val consumers = mutableListOf<LogEventConsumer>()
    private val loggers = ConcurrentHashMap<String, MockLogger>()
    private val consumersAddedCallbacks = mutableListOf<LogEventConsumer>()
    private val consumersRemovedCallbacks = mutableListOf<LogEventConsumer>()
    private val isActive = AtomicBoolean(true)
    private val totalEventsProcessed = AtomicLong(0L)
    
    override fun addConsumer(consumer: LogEventConsumer) {
        synchronized(consumers) {
            consumers.add(consumer)
            consumersAddedCallbacks.add(consumer)
        }
        consumer.onAdded(this)
    }
    
    override fun removeConsumer(consumer: LogEventConsumer): Boolean {
        val removed = synchronized(consumers) {
            val wasRemoved = consumers.remove(consumer)
            if (wasRemoved) {
                consumersRemovedCallbacks.add(consumer)
            }
            wasRemoved
        }
        
        if (removed) {
            consumer.onRemoved(this)
        }
        
        return removed
    }
    
    override suspend fun getLogger(name: String): ILogger {
        return loggers.computeIfAbsent(name) { MockLogger(it, this) }
    }
    
    override suspend fun <T> withContext(
        context: LoggingContext,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> T
    ): T {
        return LoggingContext.withContext(context) {
            kotlinx.coroutines.coroutineScope {
                block()
            }
        }
    }
    
    override fun isHealthy(): Boolean {
        return isActive.get()
    }
    
    override fun shutdown(timeoutMs: Long) {
        isActive.set(false)
        synchronized(consumers) {
            consumers.forEach { consumer ->
                try {
                    consumer.onRemoved(this)
                } catch (e: Exception) {
                    // Ignore errors during shutdown
                }
            }
            consumers.clear()
        }
    }
    
    // Test helper methods
    override fun getConsumerCount(): Int = synchronized(consumers) { consumers.size }
    fun getActiveConsumerCount(): Int = synchronized(consumers) { consumers.size }
    fun getConsumersAddedCallbacks(): List<LogEventConsumer> = consumersAddedCallbacks.toList()
    fun getConsumersRemovedCallbacks(): List<LogEventConsumer> = consumersRemovedCallbacks.toList()
    
    fun getStatistics(): LoggerManagerStatistics {
        return LoggerManagerStatistics(
            totalEventsProcessed = totalEventsProcessed.get(),
            activeConsumers = consumers.size,
            isActive = isActive.get(),
            averageProcessingTimeMs = if (simulateLatency) latencyMs.toDouble() else 0.1,
            isHealthy = isActive.get()
        )
    }
    
    fun reset() {
        synchronized(consumers) {
            consumers.clear()
        }
        consumersAddedCallbacks.clear()
        consumersRemovedCallbacks.clear()
        loggers.clear()
        isActive.set(true)
        totalEventsProcessed.set(0L)
    }
    
    /**
     * Statistics about the logger manager's performance and state for tests.
     */
    data class LoggerManagerStatistics(
        val totalEventsProcessed: Long,
        val activeConsumers: Int,
        val isActive: Boolean,
        val averageProcessingTimeMs: Double,
        val isHealthy: Boolean
    )
    
    /**
     * Distributes an event to all registered consumers.
     */
    internal suspend fun distributeEvent(event: LogEvent) {
        if (!isActive.get()) return
        
        totalEventsProcessed.incrementAndGet()
        
        if (simulateLatency) {
            delay(latencyMs)
        }
        
        synchronized(consumers) {
            consumers.forEach { consumer ->
                try {
                    if (simulateErrors && Math.random() < errorProbability) {
                        throw RuntimeException("Simulated manager error")
                    }
                    
                    consumer.onEvent(event)
                } catch (e: Exception) {
                    consumer.onError(event, e)
                }
            }
        }
    }
}

/**
 * Mock implementation of ILogger for testing.
 */
class MockLogger(
    override val name: String,
    private val manager: MockLoggerManager
) : ILogger {
    
    override fun info(message: String, data: Map<String, String>) {
        log(LogLevel.INFO, message, data)
    }
    
    override fun warn(message: String, data: Map<String, String>) {
        log(LogLevel.WARN, message, data)
    }
    
    override fun debug(message: String, data: Map<String, String>) {
        log(LogLevel.DEBUG, message, data)
    }
    
    override fun error(message: String, throwable: Throwable?, data: Map<String, String>) {
        log(LogLevel.ERROR, message, data, throwable)
    }
    
    override fun trace(message: String, data: Map<String, String>) {
        log(LogLevel.TRACE, message, data)
    }
    
    override fun critical(message: String, data: Map<String, String>) {
        log(LogLevel.ERROR, message, data)
    }
    
    override fun system(message: String) {
        log(LogLevel.INFO, "[SYSTEM] $message")
    }
    
    override fun log(level: LogLevel, message: String, exception: Throwable?) {
        kotlinx.coroutines.runBlocking {
            val event = createLogEvent(level, message, exception)
            manager.distributeEvent(event)
        }
    }
    
    override fun log(level: LogLevel, message: String, contextData: Map<String, String>, exception: Throwable?) {
        kotlinx.coroutines.runBlocking {
            val event = createLogEvent(level, message, exception, contextData)
            manager.distributeEvent(event)
        }
    }
    
    private suspend fun createLogEvent(
        level: LogLevel,
        message: String,
        exception: Throwable? = null,
        contextData: Map<String, String> = emptyMap()
    ): LogEvent {
        val context = LoggingContext.current()
        val allContextData = mutableMapOf<String, String>()
        
        // Add context data from LoggingContext
        context?.let { ctx ->
            ctx.userId?.let { allContextData["userId"] = it }
            ctx.sessionId?.let { allContextData["sessionId"] = it }
            allContextData.putAll(ctx.customData)
        }
        
        // Add provided context data
        allContextData.putAll(contextData)
        
        return LogEvent(
            timestamp = Instant.now(),
            level = level,
            loggerName = name,
            message = message,
            correlationId = context?.correlationId,
            contextData = allContextData,
            exception = exception
        )
    }
}