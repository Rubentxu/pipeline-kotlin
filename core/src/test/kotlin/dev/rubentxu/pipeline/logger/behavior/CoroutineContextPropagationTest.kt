package dev.rubentxu.pipeline.logger.behavior

import dev.rubentxu.pipeline.logger.DefaultLoggerManager
import dev.rubentxu.pipeline.logger.fixtures.TestLogEventConsumer
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.model.LoggingContext
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Custom coroutine context element for testing
data class TestContextElement(val testId: String) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TestContextElement>

    override val key: CoroutineContext.Key<*> = Key
}

/**
 * Comprehensive BDD tests for coroutine context propagation in the logging system.
 *
 * Tests coroutine context propagation scenarios:
 * - LoggingContext propagation across coroutine boundaries
 * - Context inheritance in child coroutines
 * - Context isolation between different coroutine scopes
 * - Performance impact of context propagation
 * - Integration with structured concurrency
 * - Context preservation during async operations
 * - Error handling with context propagation
 */
class CoroutineContextPropagationTest : BehaviorSpec({

    // Test service that performs async operations with logging
    class AsyncTestService(private val loggerManager: DefaultLoggerManager) {
        suspend fun getLogger() = loggerManager.getLogger("AsyncService")

        suspend fun performAsyncOperation(operationId: String): String {
            val logger = getLogger()
            logger.info("Starting async operation: $operationId")

            val result = withContext(Dispatchers.IO) {
                logger.debug("Performing I/O operation for: $operationId")
                delay(50.milliseconds) // Simulate I/O
                "Result-$operationId"
            }

            logger.info(
                "Completed async operation: $operationId",
                mapOf("result" to result)
            )

            return result
        }

        suspend fun performNestedAsyncOperations(baseId: String): List<String> {
            val logger = getLogger()
            logger.info("Starting nested async operations: $baseId")

            val results = coroutineScope {
                (1..3).map { i ->
                    async {
                        val subResult = performAsyncOperation("$baseId-sub-$i")
                        logger.debug("Sub-operation $i completed: $subResult")
                        subResult
                    }
                }.awaitAll()
            }

            logger.info(
                "All nested operations completed",
                mapOf("baseId" to baseId, "resultCount" to results.size.toString())
            )

            return results
        }
    }

    given("sistema de logging con propagación de contexto de corrutinas") {
        lateinit var loggerManager: DefaultLoggerManager
        lateinit var testConsumer: TestLogEventConsumer

        beforeEach {
            loggerManager = DefaultLoggerManager()
            testConsumer = TestLogEventConsumer("ContextPropagationTest")
            loggerManager.addConsumer(testConsumer)
        }

        afterEach {
            loggerManager.shutdown()
        }

        `when`("se usa LoggingContext en corrutinas") {
            then("debe propagar contexto a través de suspend functions") {
                runTest {
                    val testContext = LoggingContext(
                        correlationId = "suspend-test-123",
                        userId = "user-456",
                        sessionId = "session-789"
                    )

                    LoggingContext.withContext(testContext) {
                        val logger = loggerManager.getLogger("SuspendTest")

                        // Test direct context propagation without runBlocking
                        logger.info("Direct context test")
                        
                        suspend fun innerSuspendFunction() {
                            logger.info("Inside suspend function")
                            delay(10.milliseconds)
                            logger.debug("After delay in suspend function")
                        }

                        // Call suspend function directly within the context
                        innerSuspendFunction()
                        logger.info("After suspend call")
                    }

                    testConsumer.waitForEvents(4, 5000L) shouldBe true
                    val events = testConsumer.getReceivedEvents()
                    events shouldHaveSize 4

                    // Debug: Print actual values
                    events.forEach { event ->
                        println("Event: ${event.message}, correlationId: ${event.correlationId}, contextData: ${event.contextData}")
                    }

                    // All events should have the same context
                    events.forEach { event ->
                        event.correlationId shouldBe "suspend-test-123"
                        event.contextData["userId"] shouldBe "user-456"
                        event.contextData["sessionId"] shouldBe "session-789"
                    }
                }
            }

            then("debe mantener contexto en child coroutines") {
                val parentContext = LoggingContext(
                    correlationId = "parent-123",
                    customData = mapOf("scope" to "parent")
                )

                LoggingContext.withContext(parentContext) {
                    val logger = loggerManager.getLogger("ChildCoroutineTest")

                    runBlocking {
                        logger.info("Parent coroutine")

                        val job1 = launch {
                            logger.info("Child coroutine 1")
                            delay(20.milliseconds)
                            logger.debug("Child 1 after delay")
                        }

                        val job2 = launch {
                            logger.info("Child coroutine 2")
                            delay(30.milliseconds)
                            logger.debug("Child 2 after delay")
                        }

                        job1.join()
                        job2.join()

                        logger.info("All child coroutines completed")
                    }
                }

                testConsumer.waitForEvents(6, 5000L) shouldBe true
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 6

                // All events should inherit parent context
                events.forEach { event ->
                    event.correlationId shouldBe "parent-123"
                    event.contextData["scope"] shouldBe "parent"
                }
            }

            then("debe permitir contexto anidado con override") {
                val outerContext = LoggingContext(
                    correlationId = "outer-123",
                    userId = "outer-user"
                )

                LoggingContext.withContext(outerContext) {
                    val logger = loggerManager.getLogger("NestedContextTest")

                    runBlocking {
                        logger.info("Outer context message")

                        val innerContext = LoggingContext(
                            correlationId = "inner-456",
                            sessionId = "inner-session"
                        )

                        LoggingContext.withContext(innerContext) {
                            logger.info("Inner context message")

                            launch {
                                logger.debug("Child of inner context")
                            }
                        }

                        logger.info("Back to outer context")
                    }
                }

                testConsumer.waitForEvents(4, 5000L) shouldBe true
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 4

                // First event: outer context
                events[0].correlationId shouldBe "outer-123"
                events[0].contextData["userId"] shouldBe "outer-user"
                events[0].contextData["sessionId"] shouldBe null

                // Second event: inner context (overrides)
                events[1].correlationId shouldBe "inner-456"
                events[1].contextData["userId"] shouldBe null // Not inherited
                events[1].contextData["sessionId"] shouldBe "inner-session"

                // Third event: child of inner context
                events[2].correlationId shouldBe "inner-456"
                events[2].contextData["sessionId"] shouldBe "inner-session"

                // Fourth event: back to outer context
                events[3].correlationId shouldBe "outer-123"
                events[3].contextData["userId"] shouldBe "outer-user"
                events[3].contextData["sessionId"] shouldBe null
            }
        }

        `when`("se combina con custom CoroutineContext elements") {
            then("debe preservar tanto LoggingContext como custom context") {
                val loggingContext = LoggingContext(
                    correlationId = "custom-context-123",
                    customData = mapOf("feature" to "custom-integration")
                )

                LoggingContext.withContext(loggingContext) {
                    val logger = loggerManager.getLogger("CustomContextTest")

                    runBlocking {
                        logger.info("Before custom context")

                        withContext(TestContextElement("test-element-456")) {
                            logger.info("Inside custom context element")

                            val currentTestElement = coroutineContext[TestContextElement.Key]
                            currentTestElement shouldNotBe null
                            currentTestElement!!.testId shouldBe "test-element-456"

                            logger.debug(
                                "Custom context verified",
                                mapOf("testElementId" to currentTestElement.testId)
                            )
                        }

                        logger.info("After custom context")
                    }
                }

                testConsumer.waitForEvents(4, 5000L) shouldBe true
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 4

                // All events should maintain LoggingContext
                events.forEach { event ->
                    event.correlationId shouldBe "custom-context-123"
                    event.contextData["feature"] shouldBe "custom-integration"
                }

                // Debug event should have additional custom context info
                val debugEvent = events.find { it.level == LogLevel.DEBUG }
                debugEvent shouldNotBe null
                debugEvent!!.contextData["testElementId"] shouldBe "test-element-456"
            }

            then("debe manejar context switching correctamente") {
                val context1 = LoggingContext(correlationId = "ctx-1", userId = "user1")
                val context2 = LoggingContext(correlationId = "ctx-2", userId = "user2")

                val logger = loggerManager.getLogger("ContextSwitchTest")

                runBlocking {
                    // Context 1
                    LoggingContext.withContext(context1) {
                        logger.info("Context 1 message")

                        launch {
                            logger.debug("Context 1 child")
                        }
                    }

                    // Context 2
                    LoggingContext.withContext(context2) {
                        logger.info("Context 2 message")

                        launch {
                            logger.debug("Context 2 child")
                        }
                    }

                    // No context
                    logger.info("No context message")
                }

                testConsumer.waitForEvents(5, 5000L) shouldBe true
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 5

                // Context 1 events
                val ctx1Events = events.filter { it.correlationId == "ctx-1" }
                ctx1Events shouldHaveSize 2
                ctx1Events.forEach { it.contextData["userId"] shouldBe "user1" }

                // Context 2 events
                val ctx2Events = events.filter { it.correlationId == "ctx-2" }
                ctx2Events shouldHaveSize 2
                ctx2Events.forEach { it.contextData["userId"] shouldBe "user2" }

                // No context event
                val noContextEvents = events.filter { it.correlationId == null }
                noContextEvents shouldHaveSize 1
            }
        }

        `when`("se usa con async service operations") {
            val asyncService = AsyncTestService(loggerManager)

            then("debe propagar contexto a través de async operations") {
                val serviceContext = LoggingContext(
                    correlationId = "async-service-123",
                    customData = mapOf("service" to "AsyncTestService", "operation" to "single")
                )

                LoggingContext.withContext(serviceContext) {
                    runBlocking {
                        val result = asyncService.performAsyncOperation("test-op-1")
                        result shouldBe "Result-test-op-1"
                    }
                }

                testConsumer.waitForEvents(3, 5000L) shouldBe true
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 3

                // All service events should have the context
                events.forEach { event ->
                    event.correlationId shouldBe "async-service-123"
                    event.contextData["service"] shouldBe "AsyncTestService"
                    event.contextData["operation"] shouldBe "single"
                }
            }

            then("debe mantener contexto en nested async operations") {
                val nestedContext = LoggingContext(
                    correlationId = "nested-async-456",
                    customData = mapOf("operation" to "nested", "complexity" to "high")
                )

                LoggingContext.withContext(nestedContext) {
                    runBlocking {
                        val results = asyncService.performNestedAsyncOperations("batch-1")
                        results shouldHaveSize 3
                        results shouldBe listOf(
                            "Result-batch-1-sub-1",
                            "Result-batch-1-sub-2",
                            "Result-batch-1-sub-3"
                        )
                    }
                }

                // Should have logged: 1 start + 3*(start+debug+complete) + 3*sub-debug + 1 final
                testConsumer.waitForEvents(13, 10_000L) shouldBe true
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 13

                // All events should maintain context
                events.forEach { event ->
                    event.correlationId shouldBe "nested-async-456"
                    event.contextData["operation"] shouldBe "nested"
                    event.contextData["complexity"] shouldBe "high"
                }

                // Verify specific event types
                val startEvents = events.filter { it.message.contains("Starting") }
                startEvents shouldHaveSize 4 // 1 nested + 3 individual

                val completedEvents =
                    events.filter { it.message.contains("Completed") || it.message.contains("completed") }
                completedEvents shouldHaveSize 4 // 3 individual + 1 all nested
            }

            then("debe manejar errores con context preservation") {
                val errorContext = LoggingContext(
                    correlationId = "error-context-789",
                    customData = mapOf("errorHandling" to "test")
                )

                class ErrorProneService(private val loggerManager: DefaultLoggerManager) {
                    suspend fun getLogger() = loggerManager.getLogger("ErrorProneService")

                    suspend fun failingOperation(shouldFail: Boolean): String {
                        val logger = getLogger()
                        logger.info("Starting operation that might fail")

                        return try {
                            withContext(Dispatchers.IO) {
                                delay(10.milliseconds)
                                if (shouldFail) {
                                    throw RuntimeException("Intentional failure")
                                }
                                "Success"
                            }
                        } catch (e: Exception) {
                            logger.error("Operation failed", e)
                            throw e
                        }
                    }
                }

                val errorService = ErrorProneService(loggerManager)

                LoggingContext.withContext(errorContext) {
                    runBlocking {
                        // Successful operation
                        val result1 = errorService.failingOperation(false)
                        result1 shouldBe "Success"

                        // Failing operation
                        var caughtException: Exception? = null
                        try {
                            errorService.failingOperation(true)
                        } catch (e: Exception) {
                            caughtException = e
                        }

                        caughtException shouldNotBe null
                        caughtException!!.message shouldBe "Intentional failure"
                    }
                }

                testConsumer.waitForEvents(3, 5000L) shouldBe true // 2 starts + 1 error
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 3

                // All events should maintain context even during errors
                events.forEach { event ->
                    event.correlationId shouldBe "error-context-789"
                    event.contextData["errorHandling"] shouldBe "test"
                }

                val errorEvent = events.find { it.level == LogLevel.ERROR }
                errorEvent shouldNotBe null
                errorEvent!!.message shouldBe "Operation failed"
                errorEvent.exception shouldNotBe null
                errorEvent.exception!!.message shouldBe "Intentional failure"
            }
        }

        /*
         * TODO: Los tests de performance están comentados temporalmente
         * Razones:
         * - Muy lentos: 1000+ operaciones con delays = >1 minuto de ejecución
         * - No críticos: Prueban performance, no funcionalidad core
         * - Inestables: Dependen del hardware donde corran
         * 
         * Si necesitas tests de concurrencia, considera versiones más ligeras:
         * - Menos operaciones (5-10 en lugar de 1000)
         * - Sin delays o delays mínimos
         * - Timeouts más realistas
         */

        `when`("se integra con structured concurrency") {
            then("debe respetar cancellation con context preservation") {
                val cancellationContext = LoggingContext(
                    correlationId = "cancellation-test-789",
                    customData = mapOf("cancellation" to "test")
                )

                val logger = loggerManager.getLogger("CancellationTest")

                LoggingContext.withContext(cancellationContext) {
                    runBlocking {
                        val job = launch {
                            try {
                                logger.info("Starting long-running operation")
                                delay(5.seconds) // Long operation
                                logger.info("Long operation completed")
                            } catch (e: CancellationException) {
                                logger.warn(
                                    "Operation was cancelled",
                                    mapOf("reason" to "cancellation")
                                )
                                throw e
                            }
                        }

                        delay(100.milliseconds) // Let it start
                        job.cancelAndJoin()

                        logger.info("Job cancelled successfully")
                    }
                }

                testConsumer.waitForEvents(3, 5000L) shouldBe true
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 3

                // All events should maintain context
                events.forEach { event ->
                    event.correlationId shouldBe "cancellation-test-789"
                    event.contextData["cancellation"] shouldBe "test"
                }

                val startEvent = events.find { it.message.contains("Starting long-running") }
                val cancelEvent = events.find { it.message.contains("was cancelled") }
                val completedEvent = events.find { it.message.contains("cancelled successfully") }

                startEvent shouldNotBe null
                cancelEvent shouldNotBe null
                completedEvent shouldNotBe null

                cancelEvent!!.level shouldBe LogLevel.WARN
                cancelEvent.contextData["reason"] shouldBe "cancellation"
            }
        }
    }
})