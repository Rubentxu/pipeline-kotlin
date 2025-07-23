package dev.rubentxu.pipeline.logger.behavior

import dev.rubentxu.pipeline.logger.fixtures.LoggingTestUtils
import dev.rubentxu.pipeline.logger.fixtures.MockLoggerManagerFactory
import dev.rubentxu.pipeline.logger.fixtures.TestConsumerFactory
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.logger.interfaces.LogEventConsumer
import dev.rubentxu.pipeline.logger.model.LogEvent
import dev.rubentxu.pipeline.logger.model.LogLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Comprehensive BDD tests for LogEventConsumer contract compliance.
 * 
 * Tests the interface contract and expected behavior of LogEventConsumer implementations:
 * - Event processing requirements and guarantees
 * - Lifecycle callback behavior
 * - Error handling and isolation
 * - Thread-safety requirements
 * - Performance characteristics
 * - Contract compliance verification
 */
class LogEventConsumerContractTest : BehaviorSpec({
    
    given("un LogEventConsumer implementado") {
        val mockManager = MockLoggerManagerFactory.createHighPerformanceMock()
        
        afterEach {
            mockManager.reset()
        }
        
        `when`("se registra en el manager") {
            val consumer = TestConsumerFactory.createHighPerformanceConsumer("ContractTest")
            
            then("debe recibir callback onAdded") {
                consumer.onAdded(mockManager)
                
                val stats = consumer.getPerformanceStats()
                stats.wasAddedToManager shouldBe true
                stats.isActive shouldBe true
            }
            
            then("debe comenzar a recibir eventos push") {
                mockManager.addConsumer(consumer)
                
                val logger = mockManager.getLogger("ContractLogger")
                logger.info("Contract test message")
                
                consumer.waitForEvents(1, 2000L) shouldBe true
                
                val receivedEvents = consumer.getReceivedEvents()
                receivedEvents shouldHaveSize 1
                receivedEvents.first().message shouldBe "Contract test message"
            }
            
            then("debe mantener estado consistente después del registro") {
                mockManager.addConsumer(consumer)
                
                // Verify manager state
                mockManager.getConsumerCount() shouldBe 1
                mockManager.getConsumersAddedCallbacks() shouldHaveSize 1
                
                // Verify consumer state
                val stats = consumer.getPerformanceStats()
                stats.wasAddedToManager shouldBe true
                stats.isActive shouldBe true
            }
        }
        
        `when`("se remueve del manager") {
            val consumer = TestConsumerFactory.createHighPerformanceConsumer("RemovalTest")
            
            then("debe recibir callback onRemoved") {
                // First add the consumer
                mockManager.addConsumer(consumer)
                consumer.getPerformanceStats().isActive shouldBe true
                
                // Then remove it
                val removed = mockManager.removeConsumer(consumer)
                
                removed shouldBe true
                consumer.getPerformanceStats().wasRemovedFromManager shouldBe true
                consumer.getPerformanceStats().isActive shouldBe false
            }
            
            then("debe dejar de recibir eventos después de la remoción") {
                mockManager.addConsumer(consumer)
                
                // Send some events while registered
                repeat(5) { i ->
                    val logger = mockManager.getLogger("RemovalLogger")
                    logger.info("Before removal $i")
                }
                
                consumer.waitForEvents(5, 2000L) shouldBe true
                val eventsBeforeRemoval = consumer.getReceivedEvents().size
                
                // Remove consumer
                mockManager.removeConsumer(consumer)
                
                // Send more events after removal
                repeat(5) { i ->
                    val logger = mockManager.getLogger("RemovalLogger")
                    logger.info("After removal $i")
                }
                
                delay(500.milliseconds) // Give time for any potential event delivery
                
                // Should not have received new events
                val eventsAfterRemoval = consumer.getReceivedEvents().size
                eventsAfterRemoval shouldBe eventsBeforeRemoval
            }
        }
        
        `when`("procesa eventos") {
            val consumer = TestConsumerFactory.createHighPerformanceConsumer("ProcessingTest")
            mockManager.addConsumer(consumer)
            
            then("debe recibir eventos inmutables") {
                val originalEvent = LoggingTestUtils.createSampleLogEvent(
                    level = LogLevel.INFO,
                    message = "Original message",
                    contextData = mapOf("key1" to "value1")
                )
                
                consumer.onEvent(originalEvent)
                
                val receivedEvents = consumer.getReceivedEvents()
                receivedEvents shouldHaveSize 1
                
                val receivedEvent = receivedEvents.first()
                receivedEvent.message shouldBe "Original message"
                receivedEvent.level shouldBe LogLevel.INFO
                receivedEvent.contextData shouldBe mapOf("key1" to "value1")
                
                // Event should be immutable - changing original shouldn't affect received
                receivedEvent shouldNotBe originalEvent // Different instances
            }
            
            then("debe procesar rápidamente (<1ms promedio)") {
                val eventCount = 10000
                val events = LoggingTestUtils.generateLogEventBatch(eventCount)
                
                val duration = measureTime {
                    events.forEach { event ->
                        consumer.onEvent(event)
                    }
                }
                
                val averageProcessingTimeMs = duration.inWholeMilliseconds.toDouble() / eventCount
                averageProcessingTimeMs shouldBeLessThan 1.0 // Less than 1ms average
                
                val stats = consumer.getPerformanceStats()
                stats.eventsReceived shouldBe eventCount.toLong()
                stats.eventsProcessed shouldBe eventCount.toLong()
                stats.isPerformant() shouldBe true
            }
            
            then("debe ser thread-safe") {
                val eventCount = 5000
                val threadCount = 10
                val eventsPerThread = eventCount / threadCount
                
                // Execute concurrent event processing
                val jobs = (1..threadCount).map { threadId ->
                    launch {
                        repeat(eventsPerThread) { i ->
                            val event = LoggingTestUtils.createSampleLogEvent(
                                message = "Thread $threadId message $i"
                            )
                            consumer.onEvent(event)
                        }
                    }
                }
                
                jobs.forEach { it.join() }
                
                // Should have received all events without data corruption
                consumer.waitForEvents(eventCount, 10_000L) shouldBe true
                
                val receivedEvents = consumer.getReceivedEvents()
                receivedEvents shouldHaveSize eventCount
                
                // Verify no data corruption - each thread's messages should be intact
                (1..threadCount).forEach { threadId ->
                    val threadMessages = receivedEvents.filter { 
                        it.message.contains("Thread $threadId message") 
                    }
                    threadMessages shouldHaveSize eventsPerThread
                }
                
                val stats = consumer.getPerformanceStats()
                stats.errorsOccurred shouldBe 0L
            }
            
            then("debe mantener orden de eventos cuando sea posible") {
                val events = (1..100).map { i ->
                    LoggingTestUtils.createSampleLogEvent(
                        message = "Sequential message $i"
                    )
                }
                
                events.forEach { event ->
                    consumer.onEvent(event)
                }
                
                consumer.waitForEvents(100, 5000L) shouldBe true
                
                // Verify event order is maintained
                consumer.verifyEventOrder() shouldBe true
                
                val receivedEvents = consumer.getReceivedEvents()
                receivedEvents.forEachIndexed { index, event ->
                    event.message shouldBe "Sequential message ${index + 1}"
                }
            }
        }
        
        `when`("ocurre un error durante procesamiento") {
            val errorConsumer = TestConsumerFactory.createErrorProneConsumer("ErrorTest", 0.3)
            mockManager.addConsumer(errorConsumer)
            
            then("debe recibir callback onError") {
                // Generate events that will cause errors
                repeat(100) { i ->
                    val event = LoggingTestUtils.createSampleLogEvent(
                        message = "Error prone message $i"
                    )
                    try {
                        errorConsumer.onEvent(event)
                    } catch (e: Exception) {
                        // Expected - errors should be caught by manager
                    }
                }
                
                delay(1000.milliseconds) // Allow processing
                
                val errorEvents = errorConsumer.getErrorEvents()
                errorEvents.size shouldNotBe 0 // Should have recorded some errors
                
                // Each error should have associated event and exception
                errorEvents.forEach { (event, exception) ->
                    event shouldNotBe null
                    exception shouldNotBe null
                    exception.message shouldBe "Simulated consumer error for testing"
                }
            }
            
            then("no debe afectar otros consumers") {
                val goodConsumer = TestConsumerFactory.createHighPerformanceConsumer("GoodConsumer")
                mockManager.addConsumer(errorConsumer)
                mockManager.addConsumer(goodConsumer)
                
                // Send events that will cause errors in errorConsumer
                repeat(50) { i ->
                    val logger = mockManager.getLogger("ErrorIsolationLogger")
                    logger.warn("Isolation test $i")
                }
                
                // Good consumer should receive all events
                goodConsumer.waitForEvents(50, 5000L) shouldBe true
                goodConsumer.getReceivedEvents() shouldHaveSize 50
                
                // Error consumer should have processed some events and had some errors
                val errorStats = errorConsumer.getPerformanceStats()
                errorStats.eventsReceived shouldNotBe 0L
                errorStats.errorsOccurred shouldNotBe 0L
                
                // Both consumers should have been registered successfully
                mockManager.getConsumerCount() shouldBe 2
            }
            
            then("debe continuar procesando eventos posteriores") {
                // This test verifies the consumer can recover from errors
                val events = (1..100).map { i ->
                    LoggingTestUtils.createSampleLogEvent(message = "Recovery test $i")
                }
                
                var processedEvents = 0
                var errors = 0
                
                events.forEach { event ->
                    try {
                        errorConsumer.onEvent(event)
                        processedEvents++
                    } catch (e: Exception) {
                        errors++
                        // Error should not prevent further processing
                    }
                }
                
                val stats = errorConsumer.getPerformanceStats()
                stats.eventsReceived shouldBe 100L
                stats.eventsProcessed shouldBe processedEvents.toLong()
                stats.errorsOccurred shouldBe errors.toLong()
                
                // Should have processed some events successfully despite errors
                processedEvents shouldNotBe 0
                errors shouldNotBe 0
                (processedEvents + errors) shouldBe 100
            }
        }
        
        `when`("se prueba el contrato de performance") {
            val performanceConsumer = TestConsumerFactory.createHighPerformanceConsumer("PerformanceContract")
            
            then("debe procesar al menos 1000 eventos/segundo") {
                val eventCount = 10000
                mockManager.addConsumer(performanceConsumer)
                
                val startTime = System.nanoTime()
                
                repeat(eventCount) { i ->
                    val logger = mockManager.getLogger("PerformanceLogger")
                    logger.info("Performance contract test $i")
                }
                
                performanceConsumer.waitForEvents(eventCount, 15_000L) shouldBe true
                
                val endTime = System.nanoTime()
                val durationSeconds = (endTime - startTime) / 1_000_000_000.0
                val eventsPerSecond = eventCount / durationSeconds
                
                eventsPerSecond shouldBeGreaterThan 1000.0 // At least 1K events/second
                
                val stats = performanceConsumer.getPerformanceStats()
                stats.isPerformant() shouldBe true
            }
            
            then("debe mantener uso de memoria estable") {
                mockManager.addConsumer(performanceConsumer)
                
                val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                
                // Process many events to test memory stability
                repeat(50000) { i ->
                    val event = LoggingTestUtils.createSampleLogEvent(
                        message = "Memory test $i",
                        contextData = mapOf("iteration" to i.toString())
                    )
                    performanceConsumer.onEvent(event)
                }
                
                // Force garbage collection
                System.gc()
                delay(100.milliseconds)
                System.gc()
                
                val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val memoryGrowth = finalMemory - initialMemory
                
                // Memory growth should be reasonable (less than 50MB for this test)
                memoryGrowth shouldBeLessThan (50 * 1024 * 1024) // Less than 50MB growth
                
                val stats = performanceConsumer.getPerformanceStats()
                stats.eventsReceived shouldBe 50000L
                stats.errorsOccurred shouldBe 0L
            }
        }
        
        `when`("se implementan callbacks opcionales") {
            var onAddedCalled = false
            var onRemovedCalled = false
            var onErrorCalled = false
            var errorEvent: LogEvent? = null
            var errorException: Throwable? = null
            
            val customConsumer = object : LogEventConsumer {
                private val receivedEvents = mutableListOf<LogEvent>()
                
                override fun onEvent(event: LogEvent) {
                    receivedEvents.add(event)
                    
                    // Simulate occasional errors
                    if (event.message.contains("error")) {
                        throw RuntimeException("Custom consumer error")
                    }
                }
                
                override fun onAdded(loggerManager: ILoggerManager) {
                    onAddedCalled = true
                }
                
                override fun onRemoved(loggerManager: ILoggerManager) {
                    onRemovedCalled = true
                }
                
                override fun onError(event: LogEvent, exception: Throwable) {
                    onErrorCalled = true
                    errorEvent = event
                    errorException = exception
                }
                
                fun getReceivedCount() = receivedEvents.size
            }
            
            then("debe llamar onAdded cuando se registra") {
                mockManager.addConsumer(customConsumer)
                
                onAddedCalled shouldBe true
                onRemovedCalled shouldBe false
            }
            
            then("debe llamar onRemoved cuando se desregistra") {
                mockManager.addConsumer(customConsumer)
                mockManager.removeConsumer(customConsumer)
                
                onAddedCalled shouldBe true
                onRemovedCalled shouldBe true
            }
            
            then("debe llamar onError cuando ocurre excepción") {
                mockManager.addConsumer(customConsumer)
                
                val logger = mockManager.getLogger("ErrorCallbackLogger")
                logger.error("This should cause an error")
                
                delay(500.milliseconds) // Allow processing
                
                onErrorCalled shouldBe true
                errorEvent shouldNotBe null
                errorException shouldNotBe null
                errorEvent?.message shouldBe "This should cause an error"
                errorException?.message shouldBe "Custom consumer error"
            }
        }
    }
    
    given("múltiples implementaciones de LogEventConsumer") {
        val mockManager = MockLoggerManagerFactory.createHighPerformanceMock()
        
        `when`("se registran diferentes tipos de consumers") {
            val highPerfConsumer = TestConsumerFactory.createHighPerformanceConsumer("HighPerf")
            val slowConsumer = TestConsumerFactory.createSlowConsumer("Slow", 10.milliseconds)
            val errorProneConsumer = TestConsumerFactory.createErrorProneConsumer("ErrorProne", 0.1)
            val realisticConsumer = TestConsumerFactory.createRealisticConsumer("Realistic")
            
            then("todos deben cumplir el contrato básico") {
                val consumers = listOf(highPerfConsumer, slowConsumer, errorProneConsumer, realisticConsumer)
                
                consumers.forEach { consumer ->
                    mockManager.addConsumer(consumer)
                }
                
                mockManager.getConsumerCount() shouldBe 4
                
                // All should receive onAdded callback
                consumers.forEach { consumer ->
                    consumer.getPerformanceStats().wasAddedToManager shouldBe true
                }
                
                // Send test events
                repeat(100) { i ->
                    val logger = mockManager.getLogger("MultiConsumerLogger")
                    logger.info("Multi-consumer test $i")
                }
                
                // All consumers should eventually receive events (allowing for different processing speeds)
                consumers.forEach { consumer ->
                    consumer.waitForEvents(100, 30_000L) shouldBe true
                }
                
                // Verify each consumer received all events
                consumers.forEach { consumer ->
                    val stats = consumer.getPerformanceStats()
                    stats.eventsReceived shouldBe 100L
                    // Note: eventsProcessed may differ for error-prone consumer
                }
            }
            
            then("deben mantener independencia operacional") {
                mockManager.addConsumer(highPerfConsumer)
                mockManager.addConsumer(errorProneConsumer)
                
                // Send events that will cause errors in error-prone consumer
                repeat(200) { i ->
                    val logger = mockManager.getLogger("IndependenceLogger")
                    logger.warn("Independence test $i")
                }
                
                // High-performance consumer should process all events successfully
                highPerfConsumer.waitForEvents(200, 10_000L) shouldBe true
                val highPerfStats = highPerfConsumer.getPerformanceStats()
                highPerfStats.eventsReceived shouldBe 200L
                highPerfStats.eventsProcessed shouldBe 200L
                highPerfStats.errorsOccurred shouldBe 0L
                
                // Error-prone consumer should have some errors but continue processing
                errorProneConsumer.waitForEvents(200, 10_000L) shouldBe true
                val errorProneStats = errorProneConsumer.getPerformanceStats()
                errorProneStats.eventsReceived shouldBe 200L
                errorProneStats.errorsOccurred shouldNotBe 0L
                
                // Errors in one consumer shouldn't affect the other
                highPerfStats.isPerformant() shouldBe true
            }
        }
    }
})