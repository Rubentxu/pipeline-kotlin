package dev.rubentxu.pipeline.logger.behavior

import dev.rubentxu.pipeline.logger.pool.ObjectPool
import dev.rubentxu.pipeline.logger.pool.ObjectPools
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.measureTime

/**
 * Comprehensive BDD tests for ObjectPool behavior and performance.
 * 
 * Tests the object pooling system that eliminates GC pressure:
 * - Object reuse and lifecycle management
 * - Thread-safety under concurrent access
 * - Performance characteristics and metrics
 * - Pool size management and cleanup
 * - Factory and reset function behavior
 * - Specialized pool implementations
 */
class ObjectPoolBehaviorTest : BehaviorSpec({
    
    given("un ObjectPool configurado") {
        val factory = AtomicInteger(0)
        val resetCount = AtomicInteger(0)
        
        val pool = ObjectPool<StringBuilder>(
            factory = { 
                factory.incrementAndGet()
                StringBuilder("initial-${factory.get()}")
            },
            reset = { stringBuilder ->
                resetCount.incrementAndGet()
                stringBuilder.clear()
                stringBuilder.append("reset")
            },
            maxPoolSize = 10,
            initialSize = 3
        )
        
        afterEach {
            pool.clear()
            factory.set(0)
            resetCount.set(0)
        }
        
        `when`("se inicializa") {
            then("debe pre-crear objetos según initialSize") {
                pool.availableCount() shouldBe 3
                pool.maxCapacity() shouldBe 10
                pool.isEmpty() shouldBe false
                
                // Factory should have been called for initial objects
                factory.get() shouldBe 3
                resetCount.get() shouldBe 3 // Objects are reset during initialization
            }
            
            then("debe reportar estadísticas iniciales correctas") {
                val stats = pool.getStats()
                
                stats.currentSize shouldBe 3
                stats.maxSize shouldBe 10
                stats.totalAcquisitions shouldBe 0L
                stats.totalReleases shouldBe 0L
                stats.hitRate shouldBe 0.0
                pool.getStats().isHealthy() shouldBe true
            }
        }
        
        `when`("se adquieren objetos") {
            then("debe reutilizar objetos cuando estén disponibles") {
                val obj1 = pool.acquire()
                val obj2 = pool.acquire()
                val obj3 = pool.acquire()
                
                // Should have acquired from pool
                pool.availableCount() shouldBe 0
                
                // Objects should be properly reset
                obj1.toString() shouldBe "reset"
                obj2.toString() shouldBe "reset"
                obj3.toString() shouldBe "reset"
                
                val stats = pool.getStats()
                stats.totalAcquisitions shouldBe 3L
                stats.poolHits shouldBe 3L
                stats.factoryCreations shouldBe 3L // Initial creation
                stats.hitRate shouldBe 1.0
            }
            
            then("debe crear nuevos cuando el pool esté vacío") {
                val initialFactoryCount = factory.get()
                
                // Exhaust the pool
                repeat(3) { pool.acquire() }
                pool.availableCount() shouldBe 0
                
                // This should create a new object
                val newObj = pool.acquire()
                newObj shouldNotBe null
                factory.get() shouldBe initialFactoryCount + 1
                
                val stats = pool.getStats()
                stats.factoryCreations shouldBe 4L // 3 initial + 1 new
                stats.poolHits shouldBe 3L
                stats.hitRate shouldBe 0.75 // 3 hits out of 4 acquisitions
            }
            
            then("debe mantener thread-safety") {
                val acquiredObjects = ConcurrentHashMap<StringBuilder, Int>()
                val acquisitionCount = 1000
                val threadCount = 10
                
                withContext(Dispatchers.Default) {
                    (1..threadCount).map { threadId ->
                        launch {
                            repeat(acquisitionCount / threadCount) {
                                val obj = pool.acquire()
                                acquiredObjects[obj] = threadId
                                
                                // Simulate some work
                                obj.append("-thread-$threadId")
                                
                                // Return to pool
                                pool.release(obj)
                            }
                        }
                    }.forEach { it.join() }
                }
                
                val stats = pool.getStats()
                stats.totalAcquisitions shouldBe acquisitionCount.toLong()
                stats.totalReleases shouldBe acquisitionCount.toLong()
                
                // Pool should be healthy after concurrent access
                pool.getStats().isHealthy() shouldBe true
                pool.availableCount() shouldBe pool.maxCapacity() // Should be full after returns
            }
        }
        
        `when`("se liberan objetos") {
            then("debe resetear estado antes de reutilizar") {
                val obj = pool.acquire()
                obj.append("-modified")
                obj.toString() shouldBe "reset-modified"
                
                val initialResetCount = resetCount.get()
                pool.release(obj)
                resetCount.get() shouldBe initialResetCount + 1
                
                // Acquire again - should be reset
                val reusedObj = pool.acquire()
                reusedObj.toString() shouldBe "reset"
                reusedObj shouldBe obj // Same object, reset state
            }
            
            then("debe respetar tamaño máximo del pool") {
                // Fill the pool to capacity
                val objects = (1..pool.maxCapacity()).map { pool.acquire() }
                objects.forEach { pool.release(it) }
                
                pool.availableCount() shouldBe pool.maxCapacity()
                
                // Try to release one more - should be dropped
                val extraObj = StringBuilder("extra")
                pool.release(extraObj)
                
                // Pool size shouldn't exceed max capacity
                pool.availableCount() shouldBe pool.maxCapacity()
                
                val stats = pool.getStats()
                stats.droppedReleases shouldBe 1L
            }
            
            then("debe manejar errores en reset gracefully") {
                val errorPool = ObjectPool<StringBuilder>(
                    factory = { StringBuilder("test") },
                    reset = { obj ->
                        if (obj.toString().contains("error")) {
                            throw RuntimeException("Reset error")
                        }
                        obj.clear()
                    },
                    maxPoolSize = 5,
                    initialSize = 0
                )
                
                val normalObj = errorPool.acquire()
                val errorObj = errorPool.acquire()
                errorObj.append("error")
                
                // Normal release should work
                errorPool.release(normalObj)
                errorPool.availableCount() shouldBe 1
                
                // Error release should be handled gracefully
                errorPool.release(errorObj)
                errorPool.availableCount() shouldBe 1 // Shouldn't increase due to error
                
                val stats = errorPool.getStats()
                stats.droppedReleases shouldBe 1L // Error object was dropped
            }
        }
        
        `when`("se monitorea performance") {
            then("debe reportar hit rate >80% en uso normal") {
                // Create some load that should result in good hit rate
                repeat(100) {
                    val obj = pool.acquire()
                    obj.append("test-$it")
                    pool.release(obj)
                }
                
                val stats = pool.getStats()
                stats.hitRate shouldBeGreaterThan 0.8 // Should be high due to reuse
                pool.getStats().isHealthy() shouldBe true
                stats.totalAcquisitions shouldBe 100L
                stats.totalReleases shouldBe 100L
            }
            
            then("debe trackear métricas correctamente") {
                val obj1 = pool.acquire()
                val obj2 = pool.acquire()
                
                pool.release(obj1)
                // Don't release obj2 to test partial releases
                
                val stats = pool.getStats()
                stats.totalAcquisitions shouldBe 2L
                stats.totalReleases shouldBe 1L
                stats.poolHits shouldBe 2L // Both from initial pool
                stats.factoryCreations shouldBe 3L // Initial creation
                stats.utilizationRate shouldBeLessThan 1.0 // Not fully utilized
                
                val summary = stats.summary()
                summary shouldBe "Pool Stats: 1/10 objects, 100.0% hit rate, 10.0% utilization, 2 acquisitions, 3 creations"
            }
            
            then("debe identificar pool saludable") {
                // Healthy scenario - good hit rate, reasonable utilization
                repeat(50) {
                    val obj = pool.acquire()
                    pool.release(obj)
                }
                
                val stats = pool.getStats()
                pool.getStats().isHealthy() shouldBe true
                stats.hitRate shouldBeGreaterThan 0.8
                stats.utilizationRate shouldBeLessThan 0.9
                stats.droppedReleases shouldBeLessThan (stats.totalReleases * 0.1).toLong()
            }
        }
        
        `when`("se realizan operaciones de mantenimiento") {
            then("debe permitir pre-warming del pool") {
                pool.clear()
                pool.availableCount() shouldBe 0
                
                pool.preWarm(7)
                pool.availableCount() shouldBe 7
                
                // Shouldn't exceed max capacity
                pool.preWarm(10)
                pool.availableCount() shouldBe pool.maxCapacity()
            }
            
            then("debe limpiar completamente con clear()") {
                // Use the pool
                repeat(5) {
                    val obj = pool.acquire()
                    pool.release(obj)
                }
                
                val statsBeforeClear = pool.getStats()
                statsBeforeClear.totalAcquisitions shouldNotBe 0L
                
                pool.clear()
                
                pool.availableCount() shouldBe 0
                pool.isEmpty() shouldBe true
                
                val statsAfterClear = pool.getStats()
                statsAfterClear.totalAcquisitions shouldBe 0L
                statsAfterClear.totalReleases shouldBe 0L
                statsAfterClear.poolHits shouldBe 0L
            }
        }
    }
    
    given("diferentes configuraciones de ObjectPool") {
        `when`("se configura con pool pequeño") {
            val smallPool = ObjectPool<String>(
                factory = { "small-${System.nanoTime()}" },
                reset = { /* No-op for strings */ },
                maxPoolSize = 2,
                initialSize = 1
            )
            
            then("debe manejar overflow correctamente") {
                val obj1 = smallPool.acquire()
                val obj2 = smallPool.acquire()
                val obj3 = smallPool.acquire() // Should create new since pool empty
                
                smallPool.release(obj1)
                smallPool.release(obj2)
                smallPool.release(obj3) // Should be dropped due to size limit
                
                smallPool.availableCount() shouldBe 2 // Max capacity
                
                val stats = smallPool.getStats()
                stats.droppedReleases shouldBe 1L
                smallPool.getStats().isHealthy() shouldBe true // Should still be healthy
            }
        }
        
        `when`("se configura con pool grande") {
            val largePool = ObjectPool<MutableList<String>>(
                factory = { mutableListOf() },
                reset = { it.clear() },
                maxPoolSize = 1000,
                initialSize = 100
            )
            
            then("debe mantener performance bajo carga") {
                val acquisitionTime = measureTime {
                    repeat(5000) {
                        val list = largePool.acquire()
                        list.add("item-$it")
                        largePool.release(list)
                    }
                }
                
                val stats = largePool.getStats()
                stats.hitRate shouldBeGreaterThan 0.9 // Should have excellent hit rate
                largePool.getStats().isHealthy() shouldBe true
                
                // Should complete quickly due to pooling
                acquisitionTime.inWholeMilliseconds shouldBeLessThan 1000 // Less than 1 second
            }
        }
    }
    
    given("ObjectPools factory methods") {
        `when`("se crea StringBuilder pool") {
            val sbPool = ObjectPools.createStringBuilderPool(
                initialCapacity = 128,
                maxPoolSize = 50
            )
            
            then("debe crear StringBuilders con capacidad inicial") {
                val sb = sbPool.acquire()
                sb.capacity() shouldBeGreaterThan 128 // Should have at least initial capacity
                sb.length shouldBe 0 // Should be empty after reset
                
                sb.append("test content")
                sbPool.release(sb)
                
                val reusedSb = sbPool.acquire()
                reusedSb shouldBe sb // Same object
                reusedSb.length shouldBe 0 // Should be cleared
                reusedSb.capacity() shouldBeGreaterThan 128 // Capacity preserved
            }
        }
        
        `when`("se crea HashMap pool") {
            val mapPool = ObjectPools.createHashMapPool(
                initialCapacity = 32,
                maxPoolSize = 25
            )
            
            then("debe crear HashMaps con capacidad inicial") {
                val map = mapPool.acquire()
                map.size shouldBe 0
                
                map["key1"] = "value1"
                map["key2"] = "value2"
                map.size shouldBe 2
                
                mapPool.release(map)
                
                val reusedMap = mapPool.acquire()
                reusedMap shouldBe map // Same object
                reusedMap.size shouldBe 0 // Should be cleared
                reusedMap.isEmpty() shouldBe true
            }
        }
    }
    
    given("escenarios de carga extrema") {
        `when`("se ejecuta bajo alta concurrencia") {
            val concurrentPool = ObjectPool<StringBuilder>(
                factory = { StringBuilder() },
                reset = { it.clear() },
                maxPoolSize = 100,
                initialSize = 10
            )
            
            then("debe mantener consistencia y performance") {
                val threadCount = 20
                val operationsPerThread = 1000
                val totalOperations = threadCount * operationsPerThread
                
                val executionTime = measureTime {
                    withContext(Dispatchers.Default) {
                        (1..threadCount).map { threadId ->
                            launch {
                                repeat(operationsPerThread) { opId ->
                                    val obj = concurrentPool.acquire()
                                    obj.append("thread-$threadId-op-$opId")
                                    
                                    // Simulate some work
                                    if (obj.length > 100) {
                                        obj.setLength(50)
                                    }
                                    
                                    concurrentPool.release(obj)
                                }
                            }
                        }.forEach { it.join() }
                    }
                }
                
                val stats = concurrentPool.getStats()
                stats.totalAcquisitions shouldBe totalOperations.toLong()
                stats.totalReleases shouldBe totalOperations.toLong()
                concurrentPool.getStats().isHealthy() shouldBe true
                stats.hitRate shouldBeGreaterThan 0.5 // Should have reasonable hit rate
                
                // Should complete in reasonable time
                executionTime.inWholeMilliseconds shouldBeLessThan 5000 // Less than 5 seconds
            }
        }
        
        `when`("se prueba resistencia a memory leaks") {
            val memoryTestPool = ObjectPool<ByteArray>(
                factory = { ByteArray(1024) }, // 1KB objects
                reset = { it.fill(0) },
                maxPoolSize = 50,
                initialSize = 5
            )
            
            then("debe mantener uso de memoria estable") {
                val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                
                // Create lots of temporary objects that should be pooled
                repeat(10000) {
                    val array = memoryTestPool.acquire()
                    array[0] = it.toByte()
                    memoryTestPool.release(array)
                }
                
                // Force garbage collection
                System.gc()
                Thread.sleep(100)
                System.gc()
                
                val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val memoryGrowth = finalMemory - initialMemory
                
                // Should not have significant memory growth
                memoryGrowth shouldBeLessThan (5 * 1024 * 1024) // Less than 5MB growth
                
                val stats = memoryTestPool.getStats()
                stats.hitRate shouldBeGreaterThan 0.99 // Almost all acquisitions should be hits
                memoryTestPool.getStats().isHealthy() shouldBe true
            }
        }
    }
})