package dev.rubentxu.pipeline.logger.behavior

import dev.rubentxu.pipeline.logger.DefaultLoggerManager
import dev.rubentxu.pipeline.logger.fixtures.LoggingTestUtils
import dev.rubentxu.pipeline.logger.fixtures.TestLogEventConsumer
import dev.rubentxu.pipeline.logger.interfaces.LogEventConsumer
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.logger.model.LogEvent
import dev.rubentxu.pipeline.logger.model.LogLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Comprehensive BDD tests for error handling and recovery mechanisms.
 * 
 * Tests error handling and recovery scenarios:
 * - Consumer error isolation
 * - Manager recovery from consumer failures
 * - Circuit breaker patterns
 * - Graceful degradation
 * - Error propagation and reporting
 * - System stability under error conditions
 * - Performance impact of error handling
 */
class ErrorHandlingRecoveryTest : BehaviorSpec({
    
    // Consumer that fails after a certain number of events
    class FailAfterNConsumer(
        private val failAfter: Int,
        private val failureMessage: String = "Consumer intentionally failed"
    ) : LogEventConsumer {
        private val eventCount = AtomicInteger(0)
        private val receivedEvents = mutableListOf<LogEvent>()
        private val errors = mutableListOf<Pair<LogEvent, Throwable>>()
        private var isActive = false
        
        override fun onEvent(event: LogEvent) {
            val count = eventCount.incrementAndGet()
            
            if (count > failAfter) {
                throw RuntimeException(failureMessage)
            }
            
            synchronized(receivedEvents) {
                receivedEvents.add(event)
            }
        }
        
        override fun onAdded(loggerManager: ILoggerManager) {
            isActive = true
        }
        
        override fun onRemoved(loggerManager: ILoggerManager) {
            isActive = false
        }
        
        override fun onError(event: LogEvent, exception: Throwable) {
            synchronized(errors) {
                errors.add(event to exception)
            }
        }
        
        fun getReceivedEvents(): List<LogEvent> = synchronized(receivedEvents) { receivedEvents.toList() }
        fun getErrors(): List<Pair<LogEvent, Throwable>> = synchronized(errors) { errors.toList() }
        fun getEventCount(): Int = eventCount.get()
        fun isActive(): Boolean = isActive
    }
    
    // Consumer that fails intermittently based on probability
    class IntermittentFailureConsumer(
        private val failureProbability: Double,
        private val recoverAfterFailures: Int = 3
    ) : LogEventConsumer {
        private val receivedEvents = mutableListOf<LogEvent>()
        private val errors = mutableListOf<Pair<LogEvent, Throwable>>()
        private val consecutiveFailures = AtomicInteger(0)
        private var isActive = false
        
        override fun onEvent(event: LogEvent) {
            val shouldFail = Math.random() < failureProbability && 
                            consecutiveFailures.get() < recoverAfterFailures
            
            if (shouldFail) {
                consecutiveFailures.incrementAndGet()
                throw RuntimeException("Intermittent failure #${consecutiveFailures.get()}")
            }
            
            // Success - reset failure counter
            consecutiveFailures.set(0)
            synchronized(receivedEvents) {
                receivedEvents.add(event)
            }
        }
        
        override fun onAdded(loggerManager: ILoggerManager) {
            isActive = true
        }
        
        override fun onRemoved(loggerManager: ILoggerManager) {
            isActive = false
        }
        
        override fun onError(event: LogEvent, exception: Throwable) {
            synchronized(errors) {
                errors.add(event to exception)
            }
        }
        
        fun getReceivedEvents(): List<LogEvent> = synchronized(receivedEvents) { receivedEvents.toList() }
        fun getErrors(): List<Pair<LogEvent, Throwable>> = synchronized(errors) { errors.toList() }
        fun getConsecutiveFailures(): Int = consecutiveFailures.get()
        fun isActive(): Boolean = isActive
    }
    
    // Consumer that becomes temporarily unresponsive
    class SlowAndUnresponsiveConsumer(
        private val slowDownAfter: Int,
        private val processingDelayMs: Long = 100L
    ) : LogEventConsumer {
        private val eventCount = AtomicInteger(0)
        private val receivedEvents = mutableListOf<LogEvent>()
        private var isActive = false
        
        override fun onEvent(event: LogEvent) {
            val count = eventCount.incrementAndGet()
            
            if (count > slowDownAfter) {
                // Simulate slow processing
                Thread.sleep(processingDelayMs)
            }
            
            synchronized(receivedEvents) {
                receivedEvents.add(event)
            }
        }
        
        override fun onAdded(loggerManager: ILoggerManager) {
            isActive = true
        }
        
        override fun onRemoved(loggerManager: ILoggerManager) {
            isActive = false  
        }
        
        fun getReceivedEvents(): List<LogEvent> = synchronized(receivedEvents) { receivedEvents.toList() }
        fun getEventCount(): Int = eventCount.get()
        fun isActive(): Boolean = isActive
    }
    
    given("sistema de logging con manejo de errores") {
        val loggerManager = DefaultLoggerManager()
        
        afterEach {
            loggerManager.shutdown()
        }
        
        `when`("un consumer falla después de ciertos eventos") {
            val normalConsumer = TestLogEventConsumer("NormalConsumer")
            val failingConsumer = FailAfterNConsumer(
                failAfter = 5,
                failureMessage = "Consumer failed after 5 events"
            )
            val backupConsumer = TestLogEventConsumer("BackupConsumer")
            
            then("debe aislar errores y mantener otros consumers funcionando") {
                loggerManager.addConsumer(normalConsumer)
                loggerManager.addConsumer(failingConsumer)
                loggerManager.addConsumer(backupConsumer)
                
                val logger = loggerManager.getLogger("ErrorIsolationTest")
                
                // Send events that will cause failure in failingConsumer
                repeat(10) { i ->
                    logger.info("Error isolation test message $i")
                }
                
                // Wait for processing
                delay(2.seconds)
                
                // Normal and backup consumers should receive all events
                normalConsumer.waitForEvents(10, 5000L) shouldBe true
                backupConsumer.waitForEvents(10, 5000L) shouldBe true
                
                normalConsumer.getReceivedEvents() shouldHaveSize 10
                backupConsumer.getReceivedEvents() shouldHaveSize 10
                
                // Failing consumer should have received exactly 5 events before failing
                failingConsumer.getReceivedEvents() shouldHaveSize 5
                failingConsumer.getErrors().size shouldBeGreaterThan 0
                
                // Manager should remain healthy despite consumer failure
                val managerStats = loggerManager.getStatistics()
                loggerManager.isHealthy() shouldBe true
                managerStats.totalEventsProcessed shouldBe 10L
                managerStats.activeConsumers shouldBe 3 // All consumers still registered
            }
            
            then("debe reportar errores correctamente via onError callback") {
                loggerManager.addConsumer(failingConsumer)
                
                val logger = loggerManager.getLogger("ErrorCallbackTest")
                
                repeat(8) { i ->
                    logger.warn("Error callback test $i")
                }
                
                delay(1.seconds)
                
                // Should have received first 5 events successfully
                failingConsumer.getReceivedEvents() shouldHaveSize 5
                
                // Should have error events for the failed attempts
                val errors = failingConsumer.getErrors()
                errors.size shouldBeGreaterThan 0
                
                errors.forEach { (event, exception) ->
                    event shouldNotBe null
                    exception.message shouldBe "Consumer failed after 5 events"
                }
            }
        }
        
        `when`("consumer tiene fallos intermitentes") {
            val stableConsumer = TestLogEventConsumer("StableConsumer")
            val intermittentConsumer = IntermittentFailureConsumer(
                failureProbability = 0.3, // 30% failure rate
                recoverAfterFailures = 2
            )
            
            then("debe recuperarse automáticamente después de fallos") {
                loggerManager.addConsumer(stableConsumer)
                loggerManager.addConsumer(intermittentConsumer)
                
                val logger = loggerManager.getLogger("IntermittentFailureTest")
                
                // Send many events to trigger intermittent failures and recovery
                repeat(100) { i ->
                    logger.error("Intermittent failure test $i")
                }
                
                delay(3.seconds)
                
                // Stable consumer should receive all events
                stableConsumer.waitForEvents(100, 10_000L) shouldBe true
                stableConsumer.getReceivedEvents() shouldHaveSize 100
                
                // Intermittent consumer should have received some events and had some errors
                val intermittentEvents = intermittentConsumer.getReceivedEvents()
                val intermittentErrors = intermittentConsumer.getErrors()
                
                intermittentEvents.size shouldBeGreaterThan 50 // Should have recovered and processed most
                intermittentErrors.size shouldBeGreaterThan 0 // Should have had some failures
                intermittentErrors.size shouldBeLessThan intermittentEvents.size // More successes than failures
                
                // Should have recovered (consecutive failures reset)
                intermittentConsumer.getConsecutiveFailures() shouldBe 0
                
                val managerStats = loggerManager.getStatistics()
                loggerManager.isHealthy() shouldBe true
            }
            
            then("debe mantener rendimiento aceptable bajo fallos intermitentes") {
                loggerManager.addConsumer(intermittentConsumer)
                
                val eventCount = 1000
                val logger = loggerManager.getLogger("PerformanceUnderErrorsTest")
                
                val duration = measureTime {
                    repeat(eventCount) { i ->
                        logger.debug("Performance under errors test $i")
                    }
                    
                    delay(5.seconds) // Allow processing with failures
                }
                
                // Should complete in reasonable time despite errors
                duration shouldBeLessThan 10.seconds
                
                val processedEvents = intermittentConsumer.getReceivedEvents().size
                val errors = intermittentConsumer.getErrors().size
                
                // Should have processed majority of events
                processedEvents shouldBeGreaterThan (eventCount * 0.6).toInt() // At least 60%
                
                // Error rate should be reasonable
                val errorRate = errors.toDouble() / eventCount
                errorRate shouldBeLessThan 0.4 // Less than 40% error rate
            }
        }
        
        `when`("consumer se vuelve lento y no responsivo") {
            val fastConsumer = TestLogEventConsumer("FastConsumer")
            val slowConsumer = SlowAndUnresponsiveConsumer(
                slowDownAfter = 10,
                processingDelayMs = 50L
            )
            
            then("no debe afectar rendimiento de otros consumers") {
                loggerManager.addConsumer(fastConsumer)
                loggerManager.addConsumer(slowConsumer)
                
                val logger = loggerManager.getLogger("SlowConsumerTest")
                val eventCount = 50
                
                val startTime = System.nanoTime()
                
                repeat(eventCount) { i ->
                    logger.info("Slow consumer impact test $i")
                }
                
                // Fast consumer should process quickly
                fastConsumer.waitForEvents(eventCount, 5000L) shouldBe true
                val fastProcessingTime = System.nanoTime() - startTime
                
                // Fast consumer should not be significantly impacted
                val fastProcessingMs = fastProcessingTime / 1_000_000.0
                fastProcessingMs shouldBeLessThan 1000.0 // Less than 1 second
                
                // Verify fast consumer got all events
                fastConsumer.getReceivedEvents() shouldHaveSize eventCount
                
                // Slow consumer might still be processing, but shouldn't block others
                val slowEventCount = slowConsumer.getEventCount()
                slowEventCount shouldBeGreaterThan 10 // Should have processed at least the fast ones
                
                val managerStats = loggerManager.getStatistics()
                managerStats.totalEventsProcessed shouldBe eventCount.toLong()
            }
            
            then("debe permitir timeout y recovery de consumers lentos") {
                loggerManager.addConsumer(slowConsumer)
                
                val logger = loggerManager.getLogger("TimeoutRecoveryTest")
                
                // Send events that will trigger slow behavior
                repeat(30) { i ->
                    logger.warn("Timeout recovery test $i")
                }
                
                // Give time for processing including slow operations
                delay(8.seconds)
                
                // Should eventually process all events despite slowness
                val processedCount = slowConsumer.getEventCount()
                processedCount shouldBe 30
                
                // Consumer should still be active (didn't crash)
                slowConsumer.isActive() shouldBe true
            }
        }
        
        `when`("múltiples tipos de errores ocurren simultáneamente") {
            val normalConsumer = TestLogEventConsumer("NormalConsumer")
            val failAfterConsumer = FailAfterNConsumer(15, "Batch failure")
            val intermittentConsumer = IntermittentFailureConsumer(0.2) // 20% failure rate
            val slowConsumer = SlowAndUnresponsiveConsumer(5, 30L)
            
            then("debe manejar múltiples tipos de fallos sin degradación del sistema") {
                loggerManager.addConsumer(normalConsumer)
                loggerManager.addConsumer(failAfterConsumer)
                loggerManager.addConsumer(intermittentConsumer)
                loggerManager.addConsumer(slowConsumer)
                
                val logger = loggerManager.getLogger("MultipleErrorTypesTest")
                val eventCount = 100
                
                val duration = measureTime {
                    // Generate high load with multiple error scenarios
                    repeat(eventCount) { i ->
                        launch {
                            logger.error("Multiple error types test $i", 
                                null, mapOf("iteration" to i.toString())
                            )
                        }
                    }
                    
                    delay(15.seconds) // Allow processing with various errors
                }
                
                // System should remain functional despite multiple error types
                duration shouldBeLessThan 20.seconds
                
                // Normal consumer should always work
                normalConsumer.waitForEvents(eventCount, 20_000L) shouldBe true
                normalConsumer.getReceivedEvents() shouldHaveSize eventCount
                
                // Failing consumer should fail after 15 events
                val failAfterEvents = failAfterConsumer.getReceivedEvents()
                failAfterEvents.size shouldBe 15
                failAfterConsumer.getErrors().size shouldBeGreaterThan 0
                
                // Intermittent consumer should have mixed results
                val intermittentEvents = intermittentConsumer.getReceivedEvents()
                val intermittentErrors = intermittentConsumer.getErrors()
                intermittentEvents.size shouldBeGreaterThan (eventCount * 0.5).toInt() // At least 50%
                intermittentErrors.size shouldBeGreaterThan 0
                
                // Slow consumer should process all but slowly
                slowConsumer.getEventCount() shouldBe eventCount
                
                // Manager should maintain health overall
                val managerStats = loggerManager.getStatistics()
                loggerManager.isHealthy() shouldBe true
                managerStats.totalEventsProcessed shouldBe eventCount.toLong()
                managerStats.activeConsumers shouldBe 4
            }
            
            then("debe proporcionar estadísticas detalladas de errores") {
                loggerManager.addConsumer(failAfterConsumer)
                loggerManager.addConsumer(intermittentConsumer)
                
                val logger = loggerManager.getLogger("ErrorStatisticsTest")
                
                repeat(50) { i ->
                    logger.info("Error statistics test $i")
                }
                
                delay(5.seconds)
                
                val managerStats = loggerManager.getStatistics()
                
                // Should track events and maintain system health metrics
                managerStats.totalEventsProcessed shouldBe 50L
                managerStats.averageProcessingTimeMs shouldBeGreaterThan 0.0
                loggerManager.isHealthy() shouldBe true
                
                // Individual consumer error tracking
                val failAfterErrors = failAfterConsumer.getErrors()
                val intermittentErrors = intermittentConsumer.getErrors()
                
                (failAfterErrors.size + intermittentErrors.size) shouldBeGreaterThan 0
                
                // Error events should contain proper context
                (failAfterErrors + intermittentErrors).forEach { (event, exception) ->
                    event.message.shouldContain("Error statistics test")
                    exception.message shouldNotBe null
                }
            }
        }
        
        `when`("sistema se recupera de errores críticos") {
            then("debe permitir reinicio de consumers fallidos") {
                val restartableConsumer = FailAfterNConsumer(10, "Critical failure")
                loggerManager.addConsumer(restartableConsumer)
                
                val logger = loggerManager.getLogger("RestartTest")
                
                // Cause initial failure
                repeat(15) { i ->
                    logger.critical("Restart test initial $i")
                }
                
                delay(2.seconds)
                
                // Should have failed after 10 events
                restartableConsumer.getReceivedEvents() shouldHaveSize 10
                restartableConsumer.getErrors().size shouldBeGreaterThan 0
                
                // Remove and re-add consumer (simulating restart)
                loggerManager.removeConsumer(restartableConsumer)
                
                // Create new instance (restart simulation)
                val restartedConsumer = FailAfterNConsumer(20, "Post-restart failure")
                loggerManager.addConsumer(restartedConsumer)
                
                // Should work again after restart
                repeat(15) { i ->
                    logger.info("Restart test post-restart $i")
                }
                
                delay(2.seconds)
                
                // Restarted consumer should process events successfully
                restartedConsumer.getReceivedEvents() shouldHaveSize 15
                restartedConsumer.getErrors() shouldHaveSize 0
                
                val managerStats = loggerManager.getStatistics()
                loggerManager.isHealthy() shouldBe true
            }
            
            then("debe mantener funcionamiento durante recovery masivo") {
                val backupConsumer = TestLogEventConsumer("BackupConsumer")
                val failingConsumers = (1..5).map { i ->
                    FailAfterNConsumer(i * 3, "Mass failure $i")
                }
                
                loggerManager.addConsumer(backupConsumer)
                failingConsumers.forEach { loggerManager.addConsumer(it) }
                
                val logger = loggerManager.getLogger("MassRecoveryTest")
                val eventCount = 50
                
                // Generate load that will cause all failing consumers to fail at different points
                repeat(eventCount) { i ->
                    logger.warn("Mass recovery test $i")
                }
                
                delay(3.seconds)
                
                // Backup consumer should receive all events despite mass failures
                backupConsumer.waitForEvents(eventCount, 10_000L) shouldBe true
                backupConsumer.getReceivedEvents() shouldHaveSize eventCount
                
                // Failing consumers should have failed at their respective points
                failingConsumers.forEachIndexed { index, consumer ->
                    val expectedEvents = (index + 1) * 3
                    consumer.getReceivedEvents().size shouldBe expectedEvents
                    consumer.getErrors().size shouldBeGreaterThan 0
                }
                
                // System should remain operational
                val managerStats = loggerManager.getStatistics()
                managerStats.totalEventsProcessed shouldBe eventCount.toLong()
                loggerManager.isHealthy() shouldBe true
                managerStats.activeConsumers shouldBe 6 // 1 backup + 5 failing consumers
            }
        }
    }
})