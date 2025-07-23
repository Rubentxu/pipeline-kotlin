package dev.rubentxu.pipeline.logger.fixtures

import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.logger.interfaces.LogEventConsumer
import dev.rubentxu.pipeline.logger.model.LogEvent
import dev.rubentxu.pipeline.logger.model.LogLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Test consumer for verifying LogEventConsumer behavior and performance.
 * 
 * This consumer provides detailed metrics and verification capabilities
 * for testing the push-based logging system:
 * 
 * - Event collection and ordering verification
 * - Performance metrics (latency, throughput)
 * - Error simulation and handling
 * - Lifecycle callback verification
 * - Thread-safety validation
 * 
 * Features:
 * - Configurable processing delay for load testing
 * - Error injection for testing error isolation
 * - Event ordering verification
 * - Memory-efficient event storage
 * - Comprehensive metrics collection
 */
class TestLogEventConsumer(
    private val name: String = "TestConsumer",
    private val processingDelay: Duration = Duration.ZERO,
    private val errorProbability: Double = 0.0,
    private val maxStoredEvents: Int = 10000
) : LogEventConsumer {
    
    // Event storage with thread-safe collections
    private val receivedEvents = CopyOnWriteArrayList<LogEvent>()
    private val errorEvents = CopyOnWriteArrayList<Pair<LogEvent, Throwable>>()
    
    // Lifecycle state tracking
    private val isActive = AtomicBoolean(false)
    private val addedToManager = AtomicBoolean(false)
    private val removedFromManager = AtomicBoolean(false)
    
    // Performance metrics
    private val totalEventsReceived = AtomicLong(0L)
    private val totalEventsProcessed = AtomicLong(0L)
    private val totalErrorsOccurred = AtomicLong(0L)
    private val totalProcessingTimeNanos = AtomicLong(0L)
    private val startTime = System.nanoTime()
    
    // Channels for testing async behavior
    private val eventNotificationChannel = Channel<LogEvent>(Channel.UNLIMITED)
    private val errorNotificationChannel = Channel<Pair<LogEvent, Throwable>>(Channel.UNLIMITED)
    
    // Public getter for name
    fun getName(): String = name
    
    override fun onEvent(event: LogEvent) {
        val processingStartTime = System.nanoTime()
        totalEventsReceived.incrementAndGet()
        
        try {
            // Simulate processing delay if configured
            if (processingDelay > Duration.ZERO) {
                Thread.sleep(processingDelay.inWholeMilliseconds)
            }
            
            // Simulate errors if configured
            if (errorProbability > 0.0 && Math.random() < errorProbability) {
                throw RuntimeException("Simulated consumer error for testing")
            }
            
            // Store event if within limits
            if (receivedEvents.size < maxStoredEvents) {
                receivedEvents.add(event)
            }
            
            // Notify async channels
            eventNotificationChannel.trySend(event)
            
            totalEventsProcessed.incrementAndGet()
            
        } catch (e: Exception) {
            totalErrorsOccurred.incrementAndGet()
            errorEvents.add(event to e)
            errorNotificationChannel.trySend(event to e)
            throw e // Re-throw to test error handling
        } finally {
            val processingTime = System.nanoTime() - processingStartTime
            totalProcessingTimeNanos.addAndGet(processingTime)
        }
    }
    
    override fun onAdded(loggerManager: ILoggerManager) {
        addedToManager.set(true)
        isActive.set(true)
    }
    
    override fun onRemoved(loggerManager: ILoggerManager) {
        removedFromManager.set(true)
        isActive.set(false)
    }
    
    override fun onError(event: LogEvent, exception: Throwable) {
        // Custom error handling for testing
        errorEvents.add(event to exception)
        errorNotificationChannel.trySend(event to exception)
    }
    
    // Test verification methods
    
    /**
     * Returns all received events. Thread-safe.
     */
    fun getReceivedEvents(): List<LogEvent> = receivedEvents.toList()
    
    /**
     * Returns events that caused errors during processing.
     */
    fun getErrorEvents(): List<Pair<LogEvent, Throwable>> = errorEvents.toList()
    
    /**
     * Waits for a specific number of events to be received.
     * 
     * @param expectedCount Number of events to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if expected events were received, false if timeout
     */
    suspend fun waitForEvents(expectedCount: Int, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        
        while (receivedEvents.size < expectedCount && System.currentTimeMillis() < deadline) {
            delay(10)
        }
        
        return receivedEvents.size >= expectedCount
    }
    
    /**
     * Waits for a specific number of errors to occur.
     */
    suspend fun waitForErrors(expectedCount: Int, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        
        while (errorEvents.size < expectedCount && System.currentTimeMillis() < deadline) {
            delay(10)
        }
        
        return errorEvents.size >= expectedCount
    }
    
    /**
     * Verifies that events were received in chronological order.
     */
    fun verifyEventOrder(): Boolean {
        if (receivedEvents.size < 2) return true
        
        for (i in 1 until receivedEvents.size) {
            val prev = receivedEvents[i - 1]
            val current = receivedEvents[i]
            
            if (prev.timestamp.isAfter(current.timestamp)) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Verifies that all events with the specified correlation ID were received.
     */
    fun verifyCorrelationId(correlationId: String): List<LogEvent> {
        return receivedEvents.filter { it.correlationId == correlationId }
    }
    
    /**
     * Verifies that events of all specified levels were received.
     */
    fun verifyLogLevels(expectedLevels: Set<LogLevel>): Boolean {
        val receivedLevels = receivedEvents.map { it.level }.toSet()
        return receivedLevels.containsAll(expectedLevels)
    }
    
    /**
     * Returns comprehensive performance statistics.
     */
    fun getPerformanceStats(): TestConsumerStats {
        val currentTime = System.nanoTime()
        val uptimeNanos = currentTime - startTime
        val eventsReceived = totalEventsReceived.get()
        val eventsProcessed = totalEventsProcessed.get()
        val errorsOccurred = totalErrorsOccurred.get()
        val totalProcessingTime = totalProcessingTimeNanos.get()
        
        return TestConsumerStats(
            name = name,
            eventsReceived = eventsReceived,
            eventsProcessed = eventsProcessed,
            errorsOccurred = errorsOccurred,
            uptimeSeconds = uptimeNanos / 1_000_000_000.0,
            averageProcessingTimeNanos = if (eventsProcessed > 0) totalProcessingTime / eventsProcessed else 0,
            eventsPerSecond = if (uptimeNanos > 0) eventsReceived * 1_000_000_000.0 / uptimeNanos else 0.0,
            errorRate = if (eventsReceived > 0) errorsOccurred.toDouble() / eventsReceived else 0.0,
            isActive = isActive.get(),
            wasAddedToManager = addedToManager.get(),
            wasRemovedFromManager = removedFromManager.get()
        )
    }
    
    /**
     * Clears only stored events without resetting metrics.
     */
    fun clearEvents() {
        receivedEvents.clear()
        errorEvents.clear()
    }
    
    /**
     * Resets all metrics and stored events for reuse.
     */
    fun reset() {
        receivedEvents.clear()
        errorEvents.clear()
        totalEventsReceived.set(0)
        totalEventsProcessed.set(0)
        totalErrorsOccurred.set(0)
        totalProcessingTimeNanos.set(0)
        isActive.set(false)
        addedToManager.set(false)
        removedFromManager.set(false)
    }
    
    /**
     * Returns the async channel for event notifications.
     * Useful for testing concurrent scenarios.
     */
    fun getEventChannel(): Channel<LogEvent> = eventNotificationChannel
    
    /**
     * Returns the async channel for error notifications.
     */
    fun getErrorChannel(): Channel<Pair<LogEvent, Throwable>> = errorNotificationChannel
    
    /**
     * Performance and behavior statistics for the test consumer.
     */
    data class TestConsumerStats(
        val name: String,
        val eventsReceived: Long,
        val eventsProcessed: Long,
        val errorsOccurred: Long,
        val uptimeSeconds: Double,
        val averageProcessingTimeNanos: Long,
        val eventsPerSecond: Double,
        val errorRate: Double,
        val isActive: Boolean,
        val wasAddedToManager: Boolean,
        val wasRemovedFromManager: Boolean
    ) {
        fun summary(): String {
            return "TestConsumer($name): ${String.format("%.0f", eventsPerSecond)} events/sec, " +
                    "$eventsProcessed processed, ${String.format("%.2f", errorRate * 100)}% errors, " +
                    "${if (isActive) "active" else "inactive"}"
        }
        
        fun isPerformant(): Boolean {
            return eventsPerSecond > 1000.0 && // At least 1K events/sec
                    errorRate < 0.05 && // Less than 5% error rate
                    averageProcessingTimeNanos < 1_000_000 // Less than 1ms average processing
        }
    }
}

/**
 * Factory methods for creating specialized test consumers.
 */
object TestConsumerFactory {
    
    /**
     * Creates a high-performance consumer for load testing.
     */
    fun createHighPerformanceConsumer(name: String = "HighPerf"): TestLogEventConsumer {
        return TestLogEventConsumer(
            name = name,
            processingDelay = Duration.ZERO,
            errorProbability = 0.0,
            maxStoredEvents = 50000
        )
    }
    
    /**
     * Creates a slow consumer for testing backpressure and batching.
     */
    fun createSlowConsumer(name: String = "Slow", delay: Duration = 100.milliseconds): TestLogEventConsumer {
        return TestLogEventConsumer(
            name = name,
            processingDelay = delay,
            errorProbability = 0.0,
            maxStoredEvents = 1000
        )
    }
    
    /**
     * Creates an error-prone consumer for testing error isolation.
     */
    fun createErrorProneConsumer(
        name: String = "ErrorProne", 
        errorRate: Double = 0.1
    ): TestLogEventConsumer {
        return TestLogEventConsumer(
            name = name,
            processingDelay = Duration.ZERO,
            errorProbability = errorRate,
            maxStoredEvents = 1000
        )
    }
    
    /**
     * Creates a consumer that simulates real-world processing patterns.
     */
    fun createRealisticConsumer(name: String = "Realistic"): TestLogEventConsumer {
        return TestLogEventConsumer(
            name = name,
            processingDelay = 5.milliseconds, // Realistic I/O delay
            errorProbability = 0.001, // 0.1% error rate
            maxStoredEvents = 10000
        )
    }
}