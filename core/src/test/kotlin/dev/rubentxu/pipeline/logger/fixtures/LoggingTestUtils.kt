package dev.rubentxu.pipeline.logger.fixtures

import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.logger.model.LogEvent
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.model.LogSource
import dev.rubentxu.pipeline.logger.model.LoggingContext
import dev.rubentxu.pipeline.logger.model.MutableLogEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Comprehensive testing utilities for the high-performance logging system.
 * 
 * This object provides factory methods, performance testing utilities,
 * and verification helpers for testing all aspects of the logging system.
 */
object LoggingTestUtils {
    
    private val eventIdCounter = AtomicLong(0L)
    
    // Sample data for realistic test events
    private val sampleLoggerNames = listOf(
        "com.example.UserService",
        "com.example.OrderProcessor", 
        "com.example.PaymentGateway",
        "com.example.AuthenticationService",
        "com.example.DatabaseConnection",
        "com.example.CacheManager",
        "com.example.ApiController",
        "com.example.BackgroundWorker"
    )
    
    private val sampleMessages = listOf(
        "Processing user request",
        "Database connection established",
        "Cache hit for key",
        "API request completed successfully",
        "Background task started",
        "Authentication successful",
        "Order validation failed",
        "Payment processing initiated",
        "Connection pool exhausted",
        "Retry attempt scheduled"
    )
    
    private val sampleContextKeys = listOf(
        "userId", "sessionId", "requestId", "operationId", 
        "clientIp", "userAgent", "apiVersion", "region"
    )
    
    /**
     * Creates a sample LogEvent with realistic data.
     */
    fun createSampleLogEvent(
        level: LogLevel = LogLevel.INFO,
        loggerName: String = sampleLoggerNames.random(),
        message: String = sampleMessages.random(),
        correlationId: String? = generateCorrelationId(),
        contextData: Map<String, String> = generateRandomContext(),
        exception: Throwable? = null,
        source: LogSource = LogSource.LOGGER
    ): LogEvent {
        return LogEvent(
            timestamp = Instant.now(),
            level = level,
            loggerName = loggerName,
            message = message,
            correlationId = correlationId,
            contextData = contextData,
            exception = exception,
            source = source
        )
    }
    
    /**
     * Creates a MutableLogEvent with realistic data for pool testing.
     */
    fun createSampleMutableLogEvent(
        level: LogLevel = LogLevel.INFO,
        loggerName: String = sampleLoggerNames.random(),
        message: String = sampleMessages.random(),
        correlationId: String? = generateCorrelationId(),
        contextData: Map<String, String> = generateRandomContext(),
        exception: Throwable? = null,
        source: LogSource = LogSource.LOGGER
    ): MutableLogEvent {
        val event = MutableLogEvent.createOptimized()
        event.populate(
            timestamp = System.currentTimeMillis(),
            level = level,
            loggerName = loggerName,
            message = message,
            correlationId = correlationId,
            contextData = contextData,
            exception = exception,
            source = source
        )
        return event
    }
    
    /**
     * Generates a batch of log events for performance testing.
     */
    fun generateLogEventBatch(
        count: Int,
        levelDistribution: Map<LogLevel, Double> = defaultLevelDistribution()
    ): List<LogEvent> {
        return (1..count).map {
            val level = selectLevelByDistribution(levelDistribution)
            createSampleLogEvent(level = level)
        }
    }
    
    /**
     * Generates a batch of MutableLogEvents for pool testing.
     */
    fun generateMutableLogEventBatch(
        count: Int,
        levelDistribution: Map<LogLevel, Double> = defaultLevelDistribution()
    ): List<MutableLogEvent> {
        return (1..count).map {
            val level = selectLevelByDistribution(levelDistribution)
            createSampleMutableLogEvent(level = level)
        }
    }
    
    /**
     * Creates a LoggingContext with random correlation ID and context data.
     */
    fun createTestLoggingContext(
        correlationId: String = generateCorrelationId(),
        contextData: Map<String, String> = generateRandomContext()
    ): LoggingContext {
        return LoggingContext(correlationId = correlationId, customData = contextData)
    }
    
    /**
     * Generates a unique correlation ID for testing.
     */
    fun generateCorrelationId(): String {
        val id = eventIdCounter.incrementAndGet()
        return "test-correlation-${id}-${System.currentTimeMillis()}"
    }
    
    /**
     * Generates random context data for realistic testing.
     */
    fun generateRandomContext(size: Int = Random.nextInt(0, 5)): Map<String, String> {
        return (1..size).associate {
            val key = sampleContextKeys.random()
            val value = "value-${Random.nextInt(1000, 9999)}"
            key to value
        }
    }
    
    /**
     * Default log level distribution mimicking real-world usage.
     */
    private fun defaultLevelDistribution(): Map<LogLevel, Double> {
        return mapOf(
            LogLevel.DEBUG to 0.4,   // 40% debug
            LogLevel.INFO to 0.35,   // 35% info  
            LogLevel.WARN to 0.15,   // 15% warn
            LogLevel.ERROR to 0.1    // 10% error
        )
    }
    
    /**
     * Selects a log level based on probability distribution.
     */
    private fun selectLevelByDistribution(distribution: Map<LogLevel, Double>): LogLevel {
        val random = Random.nextDouble()
        var cumulative = 0.0
        
        for ((level, probability) in distribution) {
            cumulative += probability
            if (random <= cumulative) {
                return level
            }
        }
        
        return LogLevel.INFO // Fallback
    }
    
    /**
     * Performance testing utilities
     */
    object Performance {
        
        /**
         * Measures throughput of logging operations.
         */
        suspend fun measureLoggingThroughput(
            logger: ILogger,
            eventCount: Int,
            concurrent: Boolean = false
        ): ThroughputResult {
            val events = generateLogEventBatch(eventCount)
            
            val duration = measureTime {
                if (concurrent) {
                    // Test concurrent logging
                    coroutineScope {
                        events.chunked(eventCount / 4).map { chunk ->
                            async(Dispatchers.Default) {
                                chunk.forEach { event ->
                                    logger.info(event.message, event.contextData)
                                }
                            }
                        }.awaitAll()
                    }
                } else {
                    // Test sequential logging
                    events.forEach { event ->
                        logger.info(event.message, event.contextData)
                    }
                }
            }
            
            return ThroughputResult(
                eventCount = eventCount,
                duration = duration,
                eventsPerSecond = eventCount / duration.inWholeSeconds.toDouble(),
                averageLatencyNanos = duration.inWholeNanoseconds / eventCount
            )
        }
        
        /**
         * Measures LoggerManager performance under load.
         */
        suspend fun measureManagerPerformance(
            loggerManager: ILoggerManager,
            loggerCount: Int,
            eventsPerLogger: Int,
            concurrency: Int = 4
        ): ManagerPerformanceResult {
            val loggers = (1..loggerCount).map { 
                loggerManager.getLogger("TestLogger$it") 
            }
            
            val totalEvents = loggerCount * eventsPerLogger
            val startTime = System.nanoTime()
            
            // Execute concurrent logging
            coroutineScope {
                (1..concurrency).map { 
                    async(Dispatchers.Default) {
                        val loggersPerCoroutine = loggers.chunked(loggers.size / concurrency)
                        loggersPerCoroutine[it - 1].forEach { logger ->
                            repeat(eventsPerLogger) {
                                logger.info("Performance test message $it")
                            }
                        }
                    }
                }.awaitAll()
            }
            
            val endTime = System.nanoTime()
            val duration = (endTime - startTime).nanoseconds
            
            return ManagerPerformanceResult(
                loggerCount = loggerCount,
                eventsPerLogger = eventsPerLogger,
                totalEvents = totalEvents,
                concurrency = concurrency,
                duration = duration,
                eventsPerSecond = totalEvents / duration.inWholeSeconds.toDouble(),
                isHealthy = loggerManager.isHealthy()
            )
        }
        
        /**
         * Benchmarks consumer performance with different loads.
         */
        suspend fun benchmarkConsumer(
            consumer: TestLogEventConsumer,
            eventBatches: List<Int> = listOf(1000, 5000, 10000, 50000)
        ): List<ConsumerBenchmarkResult> {
            return eventBatches.map { batchSize ->
                consumer.reset()
                val events = generateLogEventBatch(batchSize)
                
                val duration = measureTime {
                    events.forEach { event ->
                        consumer.onEvent(event)
                    }
                }
                
                val stats = consumer.getPerformanceStats()
                
                ConsumerBenchmarkResult(
                    batchSize = batchSize,
                    duration = duration,
                    eventsPerSecond = stats.eventsPerSecond,
                    averageProcessingTimeNanos = stats.averageProcessingTimeNanos,
                    errorRate = stats.errorRate
                )
            }
        }
    }
    
    /**
     * Verification utilities for testing assertions
     */
    object Verification {
        
        /**
         * Verifies that events maintain chronological order.
         */
        fun verifyEventOrder(events: List<LogEvent>): Boolean {
            if (events.size < 2) return true
            
            return events.zipWithNext { prev, current ->
                !prev.timestamp.isAfter(current.timestamp)
            }.all { it }
        }
        
        /**
         * Verifies that all events have the expected correlation ID.
         */
        fun verifyCorrelationId(events: List<LogEvent>, expectedId: String): Boolean {
            return events.all { it.correlationId == expectedId }
        }
        
        /**
         * Verifies log level distribution matches expected ratios.
         */
        fun verifyLevelDistribution(
            events: List<LogEvent>,
            expectedDistribution: Map<LogLevel, Double>,
            tolerance: Double = 0.1
        ): Boolean {
            val actualCounts = events.groupingBy { it.level }.eachCount()
            val totalEvents = events.size
            
            return expectedDistribution.all { (level, expectedRatio) ->
                val actualCount = actualCounts[level] ?: 0
                val actualRatio = actualCount.toDouble() / totalEvents
                kotlin.math.abs(actualRatio - expectedRatio) <= tolerance
            }
        }
        
        /**
         * Verifies that no events were lost during processing.
         */
        fun verifyEventCompleteness(
            sentEvents: List<LogEvent>,
            receivedEvents: List<LogEvent>
        ): Boolean {
            if (sentEvents.size != receivedEvents.size) return false
            
            val sentSet = sentEvents.map { "${it.timestamp}-${it.message}-${it.loggerName}" }.toSet()
            val receivedSet = receivedEvents.map { "${it.timestamp}-${it.message}-${it.loggerName}" }.toSet()
            
            return sentSet == receivedSet
        }
    }
    
    /**
     * Result classes for performance measurements
     */
    data class ThroughputResult(
        val eventCount: Int,
        val duration: Duration,
        val eventsPerSecond: Double,
        val averageLatencyNanos: Long
    ) {
        fun summary(): String = "Throughput: ${String.format("%.0f", eventsPerSecond)} events/sec, " +
                "avg latency: ${averageLatencyNanos}ns"
        
        fun isHighPerformance(): Boolean = eventsPerSecond > 100_000.0 && averageLatencyNanos < 1_000_000
    }
    
    data class ManagerPerformanceResult(
        val loggerCount: Int,
        val eventsPerLogger: Int,
        val totalEvents: Int,
        val concurrency: Int,
        val duration: Duration,
        val eventsPerSecond: Double,
        val isHealthy: Boolean
    ) {
        fun summary(): String = "Manager Performance: ${String.format("%.0f", eventsPerSecond)} events/sec " +
                "with $loggerCount loggers, ${if (isHealthy) "healthy" else "unhealthy"}"
    }
    
    data class ConsumerBenchmarkResult(
        val batchSize: Int,
        val duration: Duration,
        val eventsPerSecond: Double,
        val averageProcessingTimeNanos: Long,
        val errorRate: Double
    ) {
        fun summary(): String = "Consumer Benchmark: $batchSize events, " +
                "${String.format("%.0f", eventsPerSecond)} events/sec, " +
                "${String.format("%.2f", errorRate * 100)}% errors"
    }
    
    /**
     * Utility for creating exception objects for testing error scenarios.
     */
    object Exceptions {
        
        fun createSampleException(message: String = "Test exception"): Exception {
            return RuntimeException(message).apply {
                fillInStackTrace()
            }
        }
        
        fun createDatabaseException(): Exception {
            return RuntimeException("Database connection failed", 
                java.sql.SQLException("Connection timeout"))
        }
        
        fun createNetworkException(): Exception {
            return RuntimeException("Network error", 
                java.net.ConnectException("Connection refused"))
        }
        
        fun createValidationException(field: String = "testField"): Exception {
            return IllegalArgumentException("Validation failed for field: $field")
        }
    }
    
    /**
     * Concurrent testing utilities
     */
    object Concurrency {
        
        /**
         * Executes multiple logging operations concurrently and measures results.
         */
        suspend fun executeConcurrentLogging(
            loggerManager: ILoggerManager,
            threadCount: Int,
            eventsPerThread: Int,
            testDuration: Duration = 30.seconds
        ): ConcurrentTestResult {
            val results = ConcurrentHashMap<Int, ThreadResult>()
            val startTime = System.nanoTime()
            
            coroutineScope {
                (1..threadCount).map { threadId ->
                    async(Dispatchers.Default) {
                        val logger = loggerManager.getLogger("ConcurrentLogger$threadId")
                        var eventsLogged = 0
                        var errors = 0
                        val threadStartTime = System.nanoTime()
                        
                        try {
                            repeat(eventsPerThread) { eventId ->
                                try {
                                    logger.info("Concurrent test message $eventId from thread $threadId")
                                    eventsLogged++
                                } catch (e: Exception) {
                                    errors++
                                }
                            }
                        } finally {
                            val threadEndTime = System.nanoTime()
                            results[threadId] = ThreadResult(
                                threadId = threadId,
                                eventsLogged = eventsLogged,
                                errors = errors,
                                durationNanos = threadEndTime - threadStartTime
                            )
                        }
                    }
                }.awaitAll()
            }
            
            val endTime = System.nanoTime()
            val totalDuration = (endTime - startTime).nanoseconds
            
            return ConcurrentTestResult(
                threadCount = threadCount,
                eventsPerThread = eventsPerThread,
                totalDuration = totalDuration,
                threadResults = results.values.toList(),
                isManagerHealthy = loggerManager.isHealthy()
            )
        }
        
        data class ThreadResult(
            val threadId: Int,
            val eventsLogged: Int,
            val errors: Int,
            val durationNanos: Long
        )
        
        data class ConcurrentTestResult(
            val threadCount: Int,
            val eventsPerThread: Int,
            val totalDuration: Duration,
            val threadResults: List<ThreadResult>,
            val isManagerHealthy: Boolean
        ) {
            val totalEventsLogged = threadResults.sumOf { it.eventsLogged }
            val totalErrors = threadResults.sumOf { it.errors }
            val eventsPerSecond = totalEventsLogged / totalDuration.inWholeSeconds.toDouble()
            val errorRate = if (totalEventsLogged > 0) totalErrors.toDouble() / totalEventsLogged else 0.0
            
            fun summary(): String = "Concurrent Test: $threadCount threads, " +
                    "${String.format("%.0f", eventsPerSecond)} events/sec, " +
                    "${String.format("%.2f", errorRate * 100)}% errors, " +
                    "${if (isManagerHealthy) "healthy" else "unhealthy"}"
        }
    }
}