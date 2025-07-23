package dev.rubentxu.pipeline.logger.integration

import dev.rubentxu.pipeline.logger.DefaultLoggerManager
import dev.rubentxu.pipeline.logger.BatchingConsoleConsumer
import dev.rubentxu.pipeline.logger.fixtures.LoggingTestUtils
import dev.rubentxu.pipeline.logger.fixtures.TestLogEventConsumer
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.model.LoggingContext
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Comprehensive integration tests for the complete high-performance logging system.
 * 
 * Tests the entire logging pipeline working together:
 * - DefaultLoggerManager with real consumers
 * - BatchingConsoleConsumer integration
 * - ObjectPool integration with MutableLogEvent
 * - Performance under realistic load scenarios
 * - Error isolation between components
 * - Memory efficiency and GC pressure reduction
 * - Concurrent multi-logger scenarios
 */
class HighPerformanceLoggingIntegrationTest : BehaviorSpec({
    
    // Helper function to capture console output
    fun captureConsoleOutput(block: () -> Unit): String {
        val originalOut = System.out
        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)
        
        try {
            System.setOut(printStream)
            block()
            printStream.flush()
            return outputStream.toString()
        } finally {
            System.setOut(originalOut)
        }
    }
    
    given("sistema completo de logging de alto rendimiento") {
        `when`("se configura con BatchingConsoleConsumer") {
            val loggerManager = DefaultLoggerManager()
            val consoleConsumer = BatchingConsoleConsumer(
                batchSize = 50,
                flushTimeoutMs = 100L,
                queueCapacity = 1000,
                enableColors = false
            )
            
            then("debe integrar correctamente todos los componentes") {
                loggerManager.addConsumer(consoleConsumer)
                
                // Verify integration
                loggerManager.getActiveConsumerCount() shouldBe 1
                consoleConsumer.isHealthy() shouldBe true
                
                val output = captureConsoleOutput {
                    runBlocking {
                        val logger = loggerManager.getLogger("IntegrationTest")
                        logger.info("Integration test message")
                        logger.warn("Warning message")
                        logger.error("Error message")
                        
                        Thread.sleep(200) // Allow batch processing
                    }
                }
                
                // Verify output contains all messages
                val lines = output.lines().filter { it.isNotBlank() }
                lines.any { it.contains("Integration test message") } shouldBe true
                lines.any { it.contains("Warning message") } shouldBe true
                lines.any { it.contains("Error message") } shouldBe true
                
                val consumerStats = consoleConsumer.getStats()
                consumerStats.eventsReceived shouldBe 3L
                consumerStats.isPerformant() shouldBe true
            }
            
            then("debe manejar carga alta con múltiples loggers") {
                loggerManager.addConsumer(consoleConsumer)
                
                val loggerCount = 10
                val messagesPerLogger = 1000
                val totalMessages = loggerCount * messagesPerLogger
                
                val executionTime = measureTime {
                    val output = captureConsoleOutput {
                        runBlocking {
                            val jobs = (1..loggerCount).map { loggerId ->
                                launch {
                                    val logger = loggerManager.getLogger("Logger$loggerId")
                                    repeat(messagesPerLogger) { messageId ->
                                        when (messageId % 4) {
                                            0 -> logger.debug("Debug message $messageId from logger $loggerId")
                                            1 -> logger.info("Info message $messageId from logger $loggerId")
                                            2 -> logger.warn("Warning message $messageId from logger $loggerId")
                                            3 -> logger.error("Error message $messageId from logger $loggerId")
                                        }
                                    }
                                }
                            }
                            
                            jobs.forEach { it.join() }
                            Thread.sleep(1000) // Allow final batch processing
                        }
                    }
                }
                
                // Performance verification
                val messagesPerSecond = totalMessages / executionTime.inWholeSeconds.coerceAtLeast(1)
                messagesPerSecond shouldBeGreaterThan 10000 // At least 10K msgs/sec
                
                val consumerStats = consoleConsumer.getStats()
                consumerStats.eventsReceived shouldBe totalMessages.toLong()
                consumerStats.isPerformant() shouldBe true
                consumerStats.averageBatchSize shouldBeGreaterThan 10.0 // Good batching
                
                // Manager performance
                val managerStats = loggerManager.getStatistics()
                managerStats.totalEventsProcessed shouldBe totalMessages.toLong()
                loggerManager.isHealthy() shouldBe true
            }
        }
        
        `when`("se configura con múltiples consumers") {
            val loggerManager = DefaultLoggerManager()
            val consoleConsumer = BatchingConsoleConsumer(
                batchSize = 25,
                flushTimeoutMs = 50L,
                queueCapacity = 500,
                enableColors = false
            )
            val testConsumer = TestLogEventConsumer("IntegrationTest")
            
            then("debe distribuir eventos a todos los consumers correctamente") {
                loggerManager.addConsumer(consoleConsumer)
                loggerManager.addConsumer(testConsumer)
                
                loggerManager.getActiveConsumerCount() shouldBe 2
                
                val eventCount = 500
                val output = captureConsoleOutput {
                    runBlocking {
                        val logger = loggerManager.getLogger("MultiConsumerTest")
                        
                        repeat(eventCount) { i ->
                            logger.info("Multi-consumer test message $i")
                        }
                        
                        Thread.sleep(500) // Allow processing
                    }
                }
                
                // Console consumer verification
                val consoleStats = consoleConsumer.getStats()
                consoleStats.eventsReceived shouldBe eventCount.toLong()
                
                // Test consumer verification
                runBlocking {
                    testConsumer.waitForEvents(eventCount, 10_000L) shouldBe true
                }
                val testEvents = testConsumer.getReceivedEvents()
                testEvents shouldHaveSize eventCount
                
                // Both consumers should have received the same events
                testEvents.forEachIndexed { index, event ->
                    event.message shouldBe "Multi-consumer test message $index"
                    event.level shouldBe LogLevel.INFO
                    event.loggerName shouldBe "MultiConsumerTest"
                }
                
                // Manager should track all events correctly
                val managerStats = loggerManager.getStatistics()
                managerStats.totalEventsProcessed shouldBe eventCount.toLong()
                managerStats.activeConsumers shouldBe 2
            }
            
            then("debe mantener independencia entre consumers") {
                // Add error-prone consumer
                val errorConsumer = TestLogEventConsumer("ErrorConsumer", 
                    processingDelay = 0.milliseconds,
                    errorProbability = 0.2 // 20% error rate
                )
                
                loggerManager.addConsumer(consoleConsumer)
                loggerManager.addConsumer(testConsumer)
                loggerManager.addConsumer(errorConsumer)
                
                val eventCount = 1000
                val output = captureConsoleOutput {
                    runBlocking {
                        val logger = loggerManager.getLogger("IndependenceTest")
                        
                        repeat(eventCount) { i ->
                            logger.warn("Independence test message $i")
                        }
                        
                        Thread.sleep(2000) // Allow processing with errors
                    }
                }
                
                // Console consumer should work normally
                val consoleStats = consoleConsumer.getStats()
                consoleStats.eventsReceived shouldBe eventCount.toLong()
                consoleStats.isPerformant() shouldBe true
                
                // Test consumer should receive all events
                runBlocking {
                    testConsumer.waitForEvents(eventCount, 15_000L) shouldBe true
                    testConsumer.getReceivedEvents() shouldHaveSize eventCount
                    val testStats = testConsumer.getPerformanceStats()
                    testStats.errorsOccurred shouldBe 0L
                    
                    // Error consumer should have errors but continue processing
                    errorConsumer.waitForEvents(eventCount, 15_000L) shouldBe true
                }
                val errorStats = errorConsumer.getPerformanceStats()
                errorStats.eventsReceived shouldBe eventCount.toLong()
                errorStats.errorsOccurred shouldBeGreaterThan 0L
                
                // Manager should isolate errors
                val managerStats = loggerManager.getStatistics()
                managerStats.totalEventsProcessed shouldBe eventCount.toLong()
                loggerManager.isHealthy() shouldBe true // Should remain healthy despite consumer errors
            }
        }
        
        `when`("se prueba bajo carga extrema") {
            val loggerManager = DefaultLoggerManager()
            val fastConsoleConsumer = BatchingConsoleConsumer(
                batchSize = 100,
                flushTimeoutMs = 25L,
                queueCapacity = 5000,
                enableColors = false
            )
            
            then("debe mantener performance con 100K+ eventos") {
                loggerManager.addConsumer(fastConsoleConsumer)
                
                val totalEvents = 100_000
                val threadCount = 20
                val eventsPerThread = totalEvents / threadCount
                
                val executionTime = measureTime {
                    val output = captureConsoleOutput {
                        runBlocking {
                            withContext(Dispatchers.Default) {
                                (1..threadCount).map { threadId ->
                                    launch {
                                        val logger = loggerManager.getLogger("StressTest$threadId")
                                        repeat(eventsPerThread) { eventId ->
                                            logger.info("Stress test event $eventId from thread $threadId")
                                        }
                                    }
                                }.forEach { it.join() }
                            }
                            
                            Thread.sleep(3000) // Allow final processing
                        }
                    }
                }
                
                // Performance requirements
                val eventsPerSecond = totalEvents / executionTime.inWholeSeconds.coerceAtLeast(1)
                eventsPerSecond shouldBeGreaterThan 25000 // At least 25K events/sec
                
                val consumerStats = fastConsoleConsumer.getStats()
                consumerStats.eventsReceived shouldBe totalEvents.toLong()
                consumerStats.isPerformant() shouldBe true
                consumerStats.averageBatchSize shouldBeGreaterThan 50.0 // Excellent batching
                consumerStats.dropRate shouldBeLessThan 0.01 // Less than 1% drop rate
                
                val managerStats = loggerManager.getStatistics()
                managerStats.totalEventsProcessed shouldBe totalEvents.toLong()
                loggerManager.isHealthy() shouldBe true
                managerStats.averageProcessingTimeMs shouldBeLessThan 1.0 // Fast processing
            }
            
            then("debe mantener uso de memoria estable") {
                loggerManager.addConsumer(fastConsoleConsumer)
                
                val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                
                val output = captureConsoleOutput {
                    runBlocking {
                        // Generate massive load to test memory stability
                        repeat(200_000) { i ->
                            val logger = loggerManager.getLogger("MemoryTest")
                            logger.info("Memory stability test $i with context data", 
                                mapOf(
                                    "iteration" to i.toString(),
                                    "batch" to (i / 1000).toString(),
                                    "testId" to "memory-stability-${i % 100}"
                                )
                            )
                        }
                        
                        Thread.sleep(5000) // Allow full processing
                    }
                }
                
                // Force garbage collection
                System.gc()
                runBlocking {
                    delay(500.milliseconds)
                }
                System.gc()
                
                val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val memoryGrowth = finalMemory - initialMemory
                
                // Memory should remain stable due to object pooling
                memoryGrowth shouldBeLessThan (50 * 1024 * 1024) // Less than 50MB growth
                
                val consumerStats = fastConsoleConsumer.getStats()
                consumerStats.eventsReceived shouldBe 200_000L
                consumerStats.isPerformant() shouldBe true
                
                val managerStats = loggerManager.getStatistics()
                loggerManager.isHealthy() shouldBe true
            }
        }
        
        `when`("se prueba integración con LoggingContext") {
            val loggerManager = DefaultLoggerManager()
            val contextAwareConsumer = TestLogEventConsumer("ContextTest")
            
            then("debe propagar contexto correctamente") {
                loggerManager.addConsumer(contextAwareConsumer)
                
                runBlocking {
                    val logger = loggerManager.getLogger("ContextLogger")
                    val testContext = LoggingContext(
                        correlationId = "ctx-123",
                        userId = "user-456",
                        sessionId = "session-789",
                        customData = mapOf("operation" to "integration-test")
                    )
                    
                    // Set context and log messages
                    LoggingContext.withContext(testContext) {
                        logger.info("Message with context")
                        logger.warn("Warning with context")
                        logger.error("Error with context")
                    }
                    
                    contextAwareConsumer.waitForEvents(3, 5000L) shouldBe true
                }
                val events = contextAwareConsumer.getReceivedEvents()
                events shouldHaveSize 3
                
                // All events should have context data
                events.forEach { event ->
                    event.correlationId shouldBe "ctx-123"
                    event.contextData["userId"] shouldBe "user-456"
                    event.contextData["sessionId"] shouldBe "session-789"
                    event.contextData["operation"] shouldBe "integration-test"
                }
                
                val managerStats = loggerManager.getStatistics()
                managerStats.totalEventsProcessed shouldBe 3L
            }
            
            then("debe manejar contexto anidado correctamente") {
                loggerManager.addConsumer(contextAwareConsumer)
                contextAwareConsumer.clearEvents()
                
                runBlocking {
                    val logger = loggerManager.getLogger("NestedContextLogger")
                    
                    val outerContext = LoggingContext(correlationId = "outer-123")
                    val innerContext = LoggingContext(
                        correlationId = "inner-456", 
                        userId = "nested-user"
                    )
                    
                    LoggingContext.withContext(outerContext) {
                        logger.info("Outer context message")
                        
                        LoggingContext.withContext(innerContext) {
                            logger.info("Inner context message")
                        }
                        
                        logger.info("Back to outer context")
                    }
                    
                    contextAwareConsumer.waitForEvents(3, 5000L) shouldBe true
                }
                val events = contextAwareConsumer.getReceivedEvents()
                
                // First message: outer context
                events[0].correlationId shouldBe "outer-123"
                events[0].contextData["userId"] shouldBe null
                
                // Second message: inner context (overrides)
                events[1].correlationId shouldBe "inner-456"
                events[1].contextData["userId"] shouldBe "nested-user"
                
                // Third message: back to outer context
                events[2].correlationId shouldBe "outer-123"
                events[2].contextData["userId"] shouldBe null
            }
        }
        
        `when`("se prueba shutdown y cleanup") {
            then("debe realizar shutdown graceful de todos los componentes") {
                val loggerManager = DefaultLoggerManager()
                val consoleConsumer = BatchingConsoleConsumer(
                    batchSize = 20,
                    flushTimeoutMs = 100L,
                    queueCapacity = 200,
                    enableColors = false
                )
                val testConsumer = TestLogEventConsumer("ShutdownTest")
                
                loggerManager.addConsumer(consoleConsumer)
                loggerManager.addConsumer(testConsumer)
                
                // Generate events before shutdown
                val logger = runBlocking {
                    val logger = loggerManager.getLogger("ShutdownLogger")
                    repeat(100) { i ->
                        logger.info("Pre-shutdown message $i")
                    }
                    
                    // Wait for processing
                    testConsumer.waitForEvents(100, 5000L) shouldBe true
                    logger // Return logger for use outside the block
                }
                
                val preShutdownEvents = testConsumer.getReceivedEvents().size
                preShutdownEvents shouldBe 100
                
                // Shutdown manager
                val shutdownTime = measureTime {
                    loggerManager.shutdown()
                }
                
                // Should shutdown within reasonable time
                shutdownTime shouldBeLessThan 10.seconds
                
                // Verify components are shut down
                val managerStats = loggerManager.getStatistics()
                managerStats.isActive shouldBe false
                
                val consumerStats = consoleConsumer.getStats()
                consumerStats.isActive shouldBe false
                
                // Try to log after shutdown - should not cause errors
                logger.info("Post-shutdown message")
                
                runBlocking {
                    delay(1000.milliseconds)
                }
                
                // Should not have received new events
                val postShutdownEvents = testConsumer.getReceivedEvents().size
                postShutdownEvents shouldBe preShutdownEvents
            }
            
            then("debe limpiar recursos y evitar memory leaks") {
                val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                
                // Create and use logger manager
                run {
                    val loggerManager = DefaultLoggerManager()
                    val consumers = (1..10).map { i ->
                        TestLogEventConsumer("CleanupTest$i")
                    }
                    
                    consumers.forEach { loggerManager.addConsumer(it) }
                    
                    // Generate load
                    runBlocking {
                        repeat(10000) { i ->
                            val logger = loggerManager.getLogger("CleanupLogger$i")
                            logger.info("Cleanup test message $i", 
                                mapOf("data" to "cleanup-$i")
                            )
                        }
                        
                        // Wait for processing
                        consumers.forEach { it.waitForEvents(10000, 30_000L) }
                    }
                    
                    // Shutdown
                    loggerManager.shutdown()
                    
                    // Verify all consumers processed events
                    consumers.forEach { consumer ->
                        consumer.getReceivedEvents() shouldHaveSize 10000
                    }
                }
                
                // Force cleanup
                System.gc()
                runBlocking {
                    delay(500.milliseconds)
                }
                System.gc()
                
                val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val memoryGrowth = finalMemory - initialMemory
                
                // Should not have significant memory growth after cleanup
                memoryGrowth shouldBeLessThan (20 * 1024 * 1024) // Less than 20MB growth
            }
        }
        
        `when`("se prueba recovery y resilencia") {
            val loggerManager = DefaultLoggerManager()
            
            then("debe recuperarse de errores de consumer") {
                val normalConsumer = TestLogEventConsumer("NormalConsumer")
                val errorConsumer = TestLogEventConsumer("ErrorConsumer", 
                    errorProbability = 0.5 // 50% error rate
                )
                val recoveryConsumer = TestLogEventConsumer("RecoveryConsumer")
                
                loggerManager.addConsumer(normalConsumer)
                loggerManager.addConsumer(errorConsumer)
                loggerManager.addConsumer(recoveryConsumer)
                
                runBlocking {
                    val logger = loggerManager.getLogger("ResilienceLogger")
                    
                    // Send events that will cause errors
                    repeat(1000) { i ->
                        logger.error("Resilience test message $i")
                    }
                    
                    // All consumers should eventually receive events
                    normalConsumer.waitForEvents(1000, 15_000L) shouldBe true
                    errorConsumer.waitForEvents(1000, 15_000L) shouldBe true
                    recoveryConsumer.waitForEvents(1000, 15_000L) shouldBe true
                }
                
                // Normal and recovery consumers should have no errors
                normalConsumer.getPerformanceStats().errorsOccurred shouldBe 0L
                recoveryConsumer.getPerformanceStats().errorsOccurred shouldBe 0L
                
                // Error consumer should have errors but continue processing
                val errorStats = errorConsumer.getPerformanceStats()
                errorStats.eventsReceived shouldBe 1000L
                errorStats.errorsOccurred shouldBeGreaterThan 0L
                
                // Manager should remain healthy despite consumer errors
                val managerStats = loggerManager.getStatistics()
                loggerManager.isHealthy() shouldBe true
                managerStats.totalEventsProcessed shouldBe 1000L
            }
        }
    }
})