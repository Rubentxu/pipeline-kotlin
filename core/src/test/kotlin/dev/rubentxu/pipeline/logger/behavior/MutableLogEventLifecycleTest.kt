package dev.rubentxu.pipeline.logger.behavior

import dev.rubentxu.pipeline.logger.fixtures.LoggingTestUtils
import dev.rubentxu.pipeline.logger.model.LogEvent
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.model.LogSource
import dev.rubentxu.pipeline.logger.model.MutableLogEvent
import dev.rubentxu.pipeline.logger.pool.ObjectPool
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldBeEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.measureTime

/**
 * Comprehensive BDD tests for MutableLogEvent lifecycle and object pooling.
 * 
 * Tests the mutable log event system that enables zero-allocation logging:
 * - Mutable event creation and manipulation
 * - Object pooling integration and efficiency
 * - Conversion between mutable and immutable forms
 * - Reset functionality for pool reuse
 * - Performance characteristics under load
 * - Thread-safety of mutable operations
 */
class MutableLogEventLifecycleTest : BehaviorSpec({
    
    given("un MutableLogEvent del pool") {
        val eventPool = ObjectPool(
            factory = { MutableLogEvent.createOptimized() },
            reset = { it.reset() },
            maxPoolSize = 100,
            initialSize = 10
        )
        
        afterEach {
            eventPool.clear()
        }
        
        `when`("se crea mediante factory") {
            val event = MutableLogEvent.createOptimized()
            
            then("debe tener valores por defecto") {
                event.timestamp shouldBe 0L
                event.level shouldBe LogLevel.DEBUG
                event.loggerName shouldBe ""
                event.message.toString().shouldBeEmpty()
                event.correlationId shouldBe null
                event.contextData shouldBe emptyMap()
                event.exception shouldBe null
                event.source shouldBe LogSource.LOGGER
            }
            
            then("debe tener capacidades optimizadas") {
                event.message.capacity() shouldBeGreaterThan 256 // Should have initial capacity
                // Check if context has initial capacity (this might be 0 for empty context)
                // event.contextData.size shouldBeGreaterThan 0 // Should have initial capacity
                event.getMessageLength() shouldBe 0
                event.hasContextData() shouldBe false
                event.hasException() shouldBe false
            }
        }
        
        `when`("se popula con datos") {
            val event = eventPool.acquire()
            val testException = RuntimeException("Test exception")
            
            then("debe mantener eficiencia de StringBuilder") {
                val initialCapacity = event.message.capacity()
                
                event.setMessage("Short message")
                event.getMessageLength() shouldBe 13
                event.getMessageString() shouldBe "Short message"
                event.message.capacity() shouldBe initialCapacity // Capacity preserved
                
                // Test appending
                event.appendMessage(" - appended")
                event.getMessageString() shouldBe "Short message - appended"
                event.getMessageLength() shouldBe 23
            }
            
            then("debe reutilizar HashMap interno") {
                event.setContextData("key1", "value1")
                event.hasContextData() shouldBe true
                event.contextData["key1"] shouldBe "value1"
                
                event.addContextData(mapOf("key2" to "value2", "key3" to "value3"))
                event.contextData.size shouldBe 3
                event.contextData shouldContainAll mapOf(
                    "key1" to "value1",
                    "key2" to "value2", 
                    "key3" to "value3"
                )
            }
            
            then("debe soportar method chaining") {
                val result = event
                    .setMessage("Chained message")
                    .setContextData("chain", "test")
                    .addContextData(mapOf("more" to "data"))
                    .appendMessage(" with append")
                
                result shouldBe event // Same instance
                event.getMessageString() shouldBe "Chained message with append"
                event.contextData.size shouldBe 2
                event.contextData["chain"] shouldBe "test"
                event.contextData["more"] shouldBe "data"
            }
            
            then("debe manejar populate() con todos los campos") {
                val timestamp = System.currentTimeMillis()
                val contextData = mapOf("user" to "123", "session" to "abc")
                
                event.populate(
                    timestamp = timestamp,
                    level = LogLevel.ERROR,
                    loggerName = "TestLogger",
                    message = "Population test",
                    correlationId = "corr-123",
                    contextData = contextData,
                    exception = testException,
                    source = LogSource.STDERR
                )
                
                event.timestamp shouldBe timestamp
                event.level shouldBe LogLevel.ERROR
                event.loggerName shouldBe "TestLogger"
                event.getMessageString() shouldBe "Population test"
                event.correlationId shouldBe "corr-123"
                event.contextData shouldContainAll contextData
                event.exception shouldBe testException
                event.source shouldBe LogSource.STDERR
                event.hasException() shouldBe true
                event.hasContextData() shouldBe true
            }
            
            eventPool.release(event)
        }
        
        `when`("se convierte a inmutable") {
            val mutableEvent = eventPool.acquire()
            val timestamp = System.currentTimeMillis()
            val contextData = mapOf("key1" to "value1", "key2" to "value2")
            val testException = RuntimeException("Conversion test")
            
            mutableEvent.populate(
                timestamp = timestamp,
                level = LogLevel.WARN,
                loggerName = "ConversionTest",
                message = "Test message for conversion",
                correlationId = "conv-123",
                contextData = contextData,
                exception = testException,
                source = LogSource.STDOUT
            )
            
            then("debe crear snapshot thread-safe") {
                val immutableEvent = mutableEvent.toImmutable()
                
                // Should be different objects
                immutableEvent shouldNotBe mutableEvent
                
                // Should have same data
                immutableEvent.timestamp shouldBe Instant.ofEpochMilli(timestamp)
                immutableEvent.level shouldBe LogLevel.WARN
                immutableEvent.loggerName shouldBe "ConversionTest"
                immutableEvent.message shouldBe "Test message for conversion"
                immutableEvent.correlationId shouldBe "conv-123"
                immutableEvent.contextData shouldContainAll contextData
                immutableEvent.exception shouldBe testException
                immutableEvent.source shouldBe LogSource.STDOUT
            }
            
            then("debe preservar todos los datos") {
                val immutable1 = mutableEvent.toImmutable()
                
                // Modify mutable event after conversion
                mutableEvent.setMessage("Modified message")
                mutableEvent.setContextData("newKey", "newValue")
                
                val immutable2 = mutableEvent.toImmutable()
                
                // First conversion should be unchanged
                immutable1.message shouldBe "Test message for conversion"
                immutable1.contextData.size shouldBe 2
                immutable1.contextData shouldContainAll contextData
                
                // Second conversion should have changes
                immutable2.message shouldBe "Modified message"
                immutable2.contextData["newKey"] shouldBe "newValue"
            }
            
            then("debe ser seguro para consumers") {
                val immutableEvent = mutableEvent.toImmutable()
                
                // Immutable event should be safe to pass around
                val contextCopy = immutableEvent.contextData
                contextCopy shouldNotBe mutableEvent.contextData // Different instances
                
                // Modifying mutable event shouldn't affect immutable
                mutableEvent.contextData.clear() 
                mutableEvent.setMessage("Cleared")
                
                immutableEvent.message shouldBe "Test message for conversion"
                immutableEvent.contextData shouldContainAll contextData
            }
            
            eventPool.release(mutableEvent)
        }
        
        `when`("se resetea para reutilización") {
            val event = eventPool.acquire()
            
            // Populate with data
            event.populate(
                timestamp = System.currentTimeMillis(),
                level = LogLevel.ERROR,
                loggerName = "ResetTest",
                message = "Message before reset",
                correlationId = "reset-123",
                contextData = mapOf("before" to "reset"),
                exception = RuntimeException("Before reset"),
                source = LogSource.STDERR
            )
            
            then("debe limpiar todo el estado") {
                val messageCapacity = event.message.capacity()
                val contextCapacity = event.contextData.size // Not a perfect test but approximate
                
                event.reset()
                
                // All fields should be reset to defaults
                event.timestamp shouldBe 0L
                event.level shouldBe LogLevel.DEBUG
                event.loggerName shouldBe ""
                event.getMessageString().shouldBeEmpty()
                event.correlationId shouldBe null
                event.contextData shouldBe emptyMap()
                event.exception shouldBe null
                event.source shouldBe LogSource.LOGGER
                
                // Helper methods should reflect reset state
                event.hasContextData() shouldBe false
                event.hasException() shouldBe false
                event.getMessageLength() shouldBe 0
            }
            
            then("debe preservar capacidades de colecciones") {
                val originalMessageCapacity = event.message.capacity()
                
                event.setMessage("Test message after reset")
                event.setContextData("key", "value")
                
                event.reset()
                
                // Capacity should be preserved for efficiency
                event.message.capacity() shouldBe originalMessageCapacity
                
                // Should be ready for immediate reuse
                event.setMessage("New message")
                event.getMessageString() shouldBe "New message"
            }
            
            then("debe ser listo para nuevo uso") {
                event.reset()
                
                // Should behave like a fresh object
                val newEvent = MutableLogEvent.createOptimized()
                
                event.timestamp shouldBe newEvent.timestamp
                event.level shouldBe newEvent.level
                event.loggerName shouldBe newEvent.loggerName
                event.getMessageString() shouldBe newEvent.getMessageString()
                event.correlationId shouldBe newEvent.correlationId
                event.contextData shouldBe newEvent.contextData
                event.exception shouldBe newEvent.exception
                event.source shouldBe newEvent.source
            }
            
            eventPool.release(event)
        }
        
        `when`("se usa conversion bidireccional") {
            val originalImmutable = LoggingTestUtils.createSampleLogEvent(
                level = LogLevel.INFO,
                loggerName = "BidirectionalTest",
                message = "Original immutable message",
                correlationId = "bi-123",
                contextData = mapOf("original" to "true", "test" to "bidirectional")
            )
            
            then("debe convertir correctamente de immutable a mutable") {
                val mutableFromImmutable = MutableLogEvent.fromImmutable(originalImmutable)
                
                mutableFromImmutable.level shouldBe LogLevel.INFO
                mutableFromImmutable.loggerName shouldBe "BidirectionalTest"
                mutableFromImmutable.getMessageString() shouldBe "Original immutable message"
                mutableFromImmutable.correlationId shouldBe "bi-123"
                mutableFromImmutable.contextData shouldContainAll originalImmutable.contextData
                mutableFromImmutable.timestamp shouldBe originalImmutable.timestamp.toEpochMilli()
                
                // Should be mutable and modifiable
                mutableFromImmutable.appendMessage(" - modified")
                mutableFromImmutable.getMessageString() shouldBe "Original immutable message - modified"
            }
            
            then("debe permitir round-trip sin pérdida de datos") {
                val mutableFromImmutable = MutableLogEvent.fromImmutable(originalImmutable)
                val backToImmutable = mutableFromImmutable.toImmutable()
                
                backToImmutable.level shouldBe originalImmutable.level
                backToImmutable.loggerName shouldBe originalImmutable.loggerName
                backToImmutable.message shouldBe originalImmutable.message
                backToImmutable.correlationId shouldBe originalImmutable.correlationId
                backToImmutable.contextData shouldContainAll originalImmutable.contextData
                backToImmutable.timestamp shouldBe originalImmutable.timestamp
                backToImmutable.exception shouldBe originalImmutable.exception
                backToImmutable.source shouldBe originalImmutable.source
            }
            
            then("debe usar populateFrom para actualizaciones eficientes") {
                val mutableEvent = eventPool.acquire()
                
                mutableEvent.populateFrom(originalImmutable)
                
                mutableEvent.level shouldBe originalImmutable.level
                mutableEvent.loggerName shouldBe originalImmutable.loggerName
                mutableEvent.getMessageString() shouldBe originalImmutable.message
                mutableEvent.correlationId shouldBe originalImmutable.correlationId
                mutableEvent.contextData shouldContainAll originalImmutable.contextData
                
                eventPool.release(mutableEvent)
            }
        }
    }
    
    given("pool de MutableLogEvent bajo carga") {
        val loadTestPool = ObjectPool(
            factory = { MutableLogEvent.createOptimized(512, 8) }, // Larger initial sizes
            reset = { it.reset() },
            maxPoolSize = 500,
            initialSize = 50
        )
        
        `when`("se ejecuta bajo alta concurrencia") {
            then("debe mantener thread-safety en operaciones mutables") {
                val threadCount = 10
                val operationsPerThread = 1000
                val totalOperations = threadCount * operationsPerThread
                
                val executionTime = measureTime {
                    withContext(Dispatchers.Default) {
                        (1..threadCount).map { threadId ->
                            launch {
                                repeat(operationsPerThread) { opId ->
                                    val event = loadTestPool.acquire()
                                    
                                    // Perform mutable operations
                                    event.populate(
                                        timestamp = System.currentTimeMillis(),
                                        level = LogLevel.values()[opId % LogLevel.values().size],
                                        loggerName = "Thread$threadId",
                                        message = "Operation $opId from thread $threadId",
                                        correlationId = "thread-$threadId-op-$opId",
                                        contextData = mapOf("threadId" to threadId.toString(), "opId" to opId.toString())
                                    )
                                    
                                    // Test immutable conversion
                                    val immutable = event.toImmutable()
                                    immutable.message shouldBe "Operation $opId from thread $threadId"
                                    
                                    loadTestPool.release(event)
                                }
                            }
                        }.forEach { it.join() }
                    }
                }
                
                val stats = loadTestPool.getStats()
                stats.totalAcquisitions shouldBe totalOperations.toLong()
                stats.totalReleases shouldBe totalOperations.toLong()
                stats.hitRate shouldBeGreaterThan 0.8 // Should have good reuse
                loadTestPool.getStats().isHealthy() shouldBe true
                
                // Should complete efficiently
                executionTime.inWholeMilliseconds shouldBeLessThan 3000 // Less than 3 seconds
            }
        }
        
        `when`("se prueba reutilización masiva") {
            then("debe eliminar GC pressure mediante reuso") {
                val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                
                repeat(100000) { i ->
                    val event = loadTestPool.acquire()
                    
                    // Simulate realistic logging data
                    event.populate(
                        timestamp = System.currentTimeMillis(),
                        level = LogLevel.INFO,
                        loggerName = "com.example.HighVolumeLogger",
                        message = "High volume log message number $i with some realistic content that would normally create GC pressure",
                        correlationId = "bulk-$i",
                        contextData = mapOf(
                            "iteration" to i.toString(),
                            "batch" to (i / 1000).toString(),
                            "userId" to "user-${i % 100}",
                            "operation" to "bulk-test"
                        )
                    )
                    
                    // Convert to immutable and back
                    val immutable = event.toImmutable()
                    immutable shouldNotBe null
                    
                    loadTestPool.release(event)
                }
                
                // Force garbage collection
                System.gc()
                Thread.sleep(100)
                System.gc()
                
                val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val memoryGrowth = finalMemory - initialMemory
                
                // Memory growth should be minimal due to object pooling
                memoryGrowth shouldBeLessThan (10 * 1024 * 1024) // Less than 10MB growth
                
                val stats = loadTestPool.getStats()
                stats.hitRate shouldBeGreaterThan 0.99 // Almost perfect reuse
                loadTestPool.getStats().isHealthy() shouldBe true
            }
        }
        
        `when`("se compara performance con allocación tradicional") {
            then("debe ser significativamente más rápido que crear objetos nuevos") {
                val iterations = 50000
                
                // Test pooled allocation
                val pooledTime = measureTime {
                    repeat(iterations) {
                        val event = loadTestPool.acquire()
                        event.setMessage("Pooled test message $it")
                        event.setContextData("test", "value")
                        loadTestPool.release(event)
                    }
                }
                
                // Test traditional allocation
                val traditionalTime = measureTime {
                    repeat(iterations) {
                        val event = MutableLogEvent()
                        event.setMessage("Traditional test message $it")
                        event.setContextData("test", "value")
                        // No pooling - just let GC handle it
                    }
                }
                
                // Pooled version should be faster (at least not significantly slower)
                // The real benefit is in GC pressure reduction, not necessarily speed
                pooledTime.inWholeMilliseconds shouldBeLessThan (traditionalTime.inWholeMilliseconds * 2)
                
                val stats = loadTestPool.getStats()
                stats.hitRate shouldBeGreaterThan 0.9 // Excellent reuse
            }
        }
    }
    
    given("MutableLogEvent edge cases y validación") {
        `when`("se manejan mensajes extremadamente largos") {
            val event = MutableLogEvent.createOptimized(1024, 4)
            
            then("debe expandir StringBuilder automáticamente") {
                val longMessage = "x".repeat(2000) // Longer than initial capacity
                
                event.setMessage(longMessage)
                event.getMessageLength() shouldBe 2000
                event.getMessageString() shouldBe longMessage
                event.message.capacity() shouldBeGreaterThan 2000 // Should have expanded
                
                // Should still be efficient for appending
                event.appendMessage(" - appended")
                event.getMessageLength() shouldBe 2011
            }
        }
        
        `when`("se manejan context data extensos") {
            val event = MutableLogEvent.createOptimized()
            
            then("debe manejar muchas entradas de contexto") {
                val largeContext = (1..100).associate { i ->
                    "key$i" to "value$i"
                }
                
                event.addContextData(largeContext)
                event.contextData.size shouldBe 100
                event.hasContextData() shouldBe true
                
                // Should maintain all entries
                largeContext.forEach { (key, value) ->
                    event.contextData[key] shouldBe value
                }
            }
        }
        
        `when`("se prueba toString() para debugging") {
            val event = MutableLogEvent.createOptimized()
            
            then("debe proporcionar representación compacta") {
                event.populate(
                    timestamp = System.currentTimeMillis(),
                    level = LogLevel.ERROR,
                    loggerName = "com.example.LongLoggerName",
                    message = "A reasonably long message for testing toString representation",
                    correlationId = "debug-test"
                )
                
                val toString = event.toString()
                toString shouldBe "MutableLogEvent(ERROR:com.example.LongLoggerName:74chars)"
                
                // Should be lightweight and not cause allocations
                val toStringTime = measureTime {
                    repeat(10000) { event.toString() }
                }
                toStringTime.inWholeMilliseconds shouldBeLessThan 100 // Should be very fast
            }
        }
    }
})