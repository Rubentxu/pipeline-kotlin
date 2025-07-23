package dev.rubentxu.pipeline.logger.integration

import dev.rubentxu.pipeline.logger.DefaultLoggerManager
import dev.rubentxu.pipeline.logger.BatchingConsoleConsumer
import dev.rubentxu.pipeline.logger.fixtures.LoggingTestUtils
import dev.rubentxu.pipeline.logger.fixtures.TestLogEventConsumer
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.logger.model.LogLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import kotlin.time.Duration.Companion.milliseconds

/**
 * Comprehensive BDD tests for Koin dependency injection integration with the logging system.
 * 
 * Tests Koin DI integration scenarios:
 * - Logger manager dependency injection
 * - Consumer registration via DI
 * - Named logger instances
 * - Scoped logging configurations
 * - Integration with application lifecycle
 * - Performance under DI container overhead
 */
class KoinIntegrationBehaviorTest : BehaviorSpec({
    
    // Test service that uses logging through DI
    class TestService : KoinComponent {
        private val loggerManager: ILoggerManager by inject()
        private val logger: ILogger by lazy { runBlocking { loggerManager.getLogger("TestService") } }
        
        fun performOperation(operationId: String): String {
            logger.info("Starting operation: $operationId")
            
            try {
                // Simulate some work
                Thread.sleep(10)
                logger.debug("Operation $operationId in progress")
                
                val result = "Result for $operationId"
                logger.info("Operation $operationId completed successfully", 
                    mapOf("result" to result)
                )
                return result
                
            } catch (e: Exception) {
                logger.error("Operation $operationId failed", e)
                throw e
            }
        }
        
        fun getLoggerName(): String = logger.name
    }
    
    // Test application component that manages multiple services
    class TestApplication : KoinComponent {
        private val loggerManager: ILoggerManager by inject()
        private val testService: TestService by inject()
        
        fun startup() {
            val logger = runBlocking { loggerManager.getLogger("TestApplication") }
            logger.info("Application starting up")
            
            // Initialize components
            logger.debug("Initializing services")
            testService.performOperation("init")
            
            logger.info("Application startup completed")
        }
        
        fun shutdown() {
            val logger = runBlocking { loggerManager.getLogger("TestApplication") }
            logger.info("Application shutting down")
            loggerManager.shutdown()
        }
    }
    
    given("sistema de logging integrado con Koin DI") {
        val loggingModule = module {
            singleOf(::DefaultLoggerManager) { bind<ILoggerManager>() }
            singleOf(::TestService)
            singleOf(::TestApplication)
            
            // Named consumers for different purposes
            single<BatchingConsoleConsumer>(qualifier = org.koin.core.qualifier.named("console")) {
                BatchingConsoleConsumer(
                    batchSize = 25,
                    flushTimeoutMs = 100L,
                    queueCapacity = 500,
                    enableColors = false
                )
            }
            
            single<TestLogEventConsumer>(qualifier = org.koin.core.qualifier.named("test")) {
                TestLogEventConsumer("KoinTest")
            }
        }
        
        beforeEach {
            startKoin {
                modules(loggingModule)
            }
        }
        
        afterEach {
            stopKoin()
        }
        
        `when`("se inyecta ILoggerManager") {
            then("debe proporcionar instancia singleton correcta") {
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager1 = koin.get<ILoggerManager>()
                val loggerManager2 = koin.get<ILoggerManager>()
                
                // Should be same instance (singleton)
                loggerManager1 shouldBe loggerManager2
                loggerManager1.shouldBeInstanceOf<DefaultLoggerManager>()
                
                // Should be functional
                val logger = runBlocking { loggerManager1.getLogger("DITest") }
                logger shouldNotBe null
                logger.name shouldBe "DITest"
            }
            
            then("debe permitir configuración de consumers via DI") {
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager = koin.get<ILoggerManager>()
                val consoleConsumer = koin.get<BatchingConsoleConsumer>(qualifier = org.koin.core.qualifier.named("console"))
                val testConsumer = koin.get<TestLogEventConsumer>(qualifier = org.koin.core.qualifier.named("test"))
                
                // Configure consumers
                loggerManager.addConsumer(consoleConsumer)
                loggerManager.addConsumer(testConsumer)
                
                loggerManager.getConsumerCount() shouldBe 2
                
                // Test logging works
                val logger = runBlocking { loggerManager.getLogger("DIConfigTest") }
                logger.info("DI configuration test message")
                
                runBlocking { testConsumer.waitForEvents(1, 5000L) } shouldBe true
                testConsumer.getReceivedEvents() shouldHaveSize 1
                testConsumer.getReceivedEvents().first().message shouldBe "DI configuration test message"
            }
        }
        
        `when`("se inyecta en servicios de aplicación") {
            then("debe funcionar correctamente en TestService") {
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager = koin.get<ILoggerManager>()
                val testConsumer = koin.get<TestLogEventConsumer>(qualifier = org.koin.core.qualifier.named("test"))
                val testService = koin.get<TestService>()
                
                loggerManager.addConsumer(testConsumer)
                
                // Test service operation
                val result = testService.performOperation("koin-test-1")
                result shouldBe "Result for koin-test-1"
                
                // Verify logger name
                testService.getLoggerName() shouldBe "TestService"
                
                // Verify events were logged
                runBlocking { testConsumer.waitForEvents(3, 5000L) } shouldBe true // info, debug, info
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 3
                
                events[0].message shouldBe "Starting operation: koin-test-1"
                events[0].loggerName shouldBe "TestService"
                events[0].level shouldBe LogLevel.INFO
                
                events[1].message shouldBe "Operation koin-test-1 in progress"
                events[1].level shouldBe LogLevel.DEBUG
                
                events[2].message shouldBe "Operation koin-test-1 completed successfully"
                events[2].contextData["result"] shouldBe "Result for koin-test-1"
            }
            
            then("debe manejar múltiples servicios con loggers independientes") {
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager = koin.get<ILoggerManager>()
                val testConsumer = koin.get<TestLogEventConsumer>(qualifier = org.koin.core.qualifier.named("test"))
                
                loggerManager.addConsumer(testConsumer)
                
                // Create multiple service instances (simulating different beans)
                val service1 = TestService()
                val service2 = TestService()
                val service3 = TestService()
                
                // Execute operations concurrently
                val results = listOf(
                    service1.performOperation("service1-op"),
                    service2.performOperation("service2-op"), 
                    service3.performOperation("service3-op")
                )
                
                results shouldBe listOf(
                    "Result for service1-op",
                    "Result for service2-op", 
                    "Result for service3-op"
                )
                
                // All services should use same logger name but different instances
                service1.getLoggerName() shouldBe "TestService"
                service2.getLoggerName() shouldBe "TestService"
                service3.getLoggerName() shouldBe "TestService"
                
                // Should have received all log events
                runBlocking { testConsumer.waitForEvents(9, 10_000L) } shouldBe true // 3 events per service
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 9
                
                // Verify events from all services
                val service1Events = events.filter { it.message.contains("service1-op") }
                val service2Events = events.filter { it.message.contains("service2-op") }
                val service3Events = events.filter { it.message.contains("service3-op") }
                
                service1Events shouldHaveSize 3
                service2Events shouldHaveSize 3
                service3Events shouldHaveSize 3
            }
        }
        
        `when`("se integra con ciclo de vida de aplicación") {
            then("debe manejar startup y shutdown completo") {
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager = koin.get<ILoggerManager>()
                val testConsumer = koin.get<TestLogEventConsumer>(qualifier = org.koin.core.qualifier.named("test"))
                val application = koin.get<TestApplication>()
                
                loggerManager.addConsumer(testConsumer)
                
                // Application startup
                application.startup()
                
                // Should have logged startup events
                runBlocking { testConsumer.waitForEvents(5, 10_000L) } shouldBe true
                val startupEvents = testConsumer.getReceivedEvents()
                startupEvents.size shouldBe 5 // app startup + service operation events
                
                // Verify application events
                val appEvents = startupEvents.filter { it.loggerName == "TestApplication" }
                appEvents shouldHaveSize 3
                appEvents[0].message shouldBe "Application starting up"
                appEvents[1].message shouldBe "Initializing services"
                appEvents[2].message shouldBe "Application startup completed"
                
                // Verify service events during startup
                val serviceEvents = startupEvents.filter { it.loggerName == "TestService" }
                serviceEvents shouldHaveSize 2 // info start + info complete (debug filtered in this test)
                
                // Application shutdown
                testConsumer.clearEvents()
                application.shutdown()
                
                delay(500.milliseconds) // Allow shutdown processing
                
                // Should have logged shutdown event
                val shutdownEvents = testConsumer.getReceivedEvents()
                shutdownEvents.any { it.message == "Application shutting down" } shouldBe true
                
                // Logger manager should be shut down
                loggerManager.isHealthy() shouldBe false
            }
            
            then("debe mantener performance con overhead de DI") {
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager = koin.get<ILoggerManager>()
                val testConsumer = koin.get<TestLogEventConsumer>(qualifier = org.koin.core.qualifier.named("test"))
                
                loggerManager.addConsumer(testConsumer)
                
                val operationCount = 1000
                val startTime = System.nanoTime()
                
                // Perform many operations through DI
                repeat(operationCount) { i ->
                    val service = koin.get<TestService>()
                    service.performOperation("perf-test-$i")
                }
                
                val endTime = System.nanoTime()
                val durationMs = (endTime - startTime) / 1_000_000.0
                val operationsPerSecond = operationCount / (durationMs / 1000.0)
                
                // Should maintain reasonable performance despite DI overhead
                operationsPerSecond shouldBeGreaterThan 100.0 // At least 100 ops/sec
                
                // Should have logged all operations
                val expectedEvents = operationCount * 3 // 3 events per operation
                runBlocking { testConsumer.waitForEvents(expectedEvents, 30_000L) } shouldBe true
                
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize expectedEvents
                
                // Verify manager performance
                loggerManager.isHealthy() shouldBe true
            }
        }
        
        `when`("se configuran loggers con nombres específicos") {
            then("debe permitir configuración granular por logger") {
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager = koin.get<ILoggerManager>()
                val testConsumer = koin.get<TestLogEventConsumer>(qualifier = org.koin.core.qualifier.named("test"))
                
                loggerManager.addConsumer(testConsumer)
                
                // Create specialized loggers for different components
                val authLogger = runBlocking { loggerManager.getLogger("Authentication") }
                val dbLogger = runBlocking { loggerManager.getLogger("Database") }
                val apiLogger = runBlocking { loggerManager.getLogger("API") }
                val securityLogger = runBlocking { loggerManager.getLogger("Security") }
                
                // Log events with different loggers
                authLogger.info("User login attempt", mapOf("userId" to "user123"))
                dbLogger.debug("Database query executed", mapOf("query" to "SELECT * FROM users"))
                apiLogger.warn("API rate limit approaching", mapOf("endpoint" to "/api/users"))
                securityLogger.error("Security violation detected", data = mapOf("ip" to "192.168.1.100"))
                
                runBlocking { testConsumer.waitForEvents(4, 5000L) } shouldBe true
                val events = testConsumer.getReceivedEvents()
                events shouldHaveSize 4
                
                // Verify logger names and contexts
                val authEvent = events.find { it.loggerName == "Authentication" }
                authEvent shouldNotBe null
                authEvent!!.message shouldBe "User login attempt"
                authEvent.contextData["userId"] shouldBe "user123"
                
                val dbEvent = events.find { it.loggerName == "Database" }
                dbEvent shouldNotBe null
                dbEvent!!.level shouldBe LogLevel.DEBUG
                
                val apiEvent = events.find { it.loggerName == "API" }
                apiEvent shouldNotBe null
                apiEvent!!.level shouldBe LogLevel.WARN
                
                val securityEvent = events.find { it.loggerName == "Security" }
                securityEvent shouldNotBe null
                securityEvent!!.level shouldBe LogLevel.ERROR
                securityEvent.contextData["ip"] shouldBe "192.168.1.100"
            }
            
            then("debe mantener logger cache eficiente") {
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager = koin.get<ILoggerManager>()
                
                // Request same logger multiple times
                val logger1 = runBlocking { loggerManager.getLogger("CacheTest") }
                val logger2 = runBlocking { loggerManager.getLogger("CacheTest") }
                val logger3 = runBlocking { loggerManager.getLogger("CacheTest") }
                
                // Should be same instance (cached)
                logger1 shouldBe logger2
                logger2 shouldBe logger3
                
                // Different names should be different instances
                val differentLogger = runBlocking { loggerManager.getLogger("DifferentLogger") }
                differentLogger shouldNotBe logger1
                
                // Verify cache efficiency with many requests
                val startTime = System.nanoTime()
                repeat(10000) {
                    runBlocking { loggerManager.getLogger("PerformanceTest") }
                }
                val endTime = System.nanoTime()
                val durationMs = (endTime - startTime) / 1_000_000.0
                
                // Should be very fast due to caching
                durationMs shouldBeLessThan 100.0 // Less than 100ms for 10K requests
            }
        }
        
        `when`("se manejan errores en configuración DI") {
            then("debe manejar dependencias faltantes gracefully") {
                // This test verifies behavior when DI configuration is incomplete
                val incompleteModule = module {
                    // Missing ILoggerManager binding
                    singleOf(::TestService)
                }
                
                stopKoin()
                startKoin {
                    modules(incompleteModule)
                }
                
                val koin = org.koin.core.context.GlobalContext.get()
                
                try {
                    val service = koin.get<TestService>()
                    // This should fail due to missing ILoggerManager
                    service.performOperation("should-fail")
                } catch (e: Exception) {
                    // Expected - missing dependency
                    e::class.simpleName shouldBe "NoBeanDefFoundException"
                }
            }
            
            then("debe permitir configuración condicional") {
                val conditionalModule = module {
                    singleOf(::DefaultLoggerManager) { bind<ILoggerManager>() }
                    
                    // Conditional consumer based on environment
                    single<TestLogEventConsumer> {
                        val isDevelopment = System.getProperty("environment", "development") == "development"
                        if (isDevelopment) {
                            TestLogEventConsumer("Development")
                        } else {
                            TestLogEventConsumer("Production", maxStoredEvents = 1000)
                        }
                    }
                }
                
                stopKoin()
                startKoin {
                    modules(conditionalModule)
                }
                
                val koin = org.koin.core.context.GlobalContext.get()
                val loggerManager = koin.get<ILoggerManager>()
                val consumer = koin.get<TestLogEventConsumer>()
                
                // Should create development consumer (default environment)
                consumer.getName() shouldBe "Development"
                
                loggerManager.addConsumer(consumer)
                
                val logger = runBlocking { loggerManager.getLogger("ConditionalTest") }
                logger.info("Conditional configuration test")
                
                runBlocking { consumer.waitForEvents(1, 5000L) } shouldBe true
                consumer.getReceivedEvents() shouldHaveSize 1
            }
        }
    }
})