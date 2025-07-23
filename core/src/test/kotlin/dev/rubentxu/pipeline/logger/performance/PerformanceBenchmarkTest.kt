package dev.rubentxu.pipeline.logger.performance

import dev.rubentxu.pipeline.logger.DefaultLoggerManager
import dev.rubentxu.pipeline.logger.BatchingConsoleConsumer
import dev.rubentxu.pipeline.logger.fixtures.LoggingTestUtils
import dev.rubentxu.pipeline.logger.fixtures.TestLogEventConsumer
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.model.LoggingContext
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Comprehensive performance benchmarks and comparison tests for the logging system.
 * 
 * Performance benchmarks covering:
 * - Throughput measurements under various loads
 * - Latency analysis for different operations
 * - Memory usage and GC pressure analysis
 * - Comparison with traditional logging approaches
 * - Scalability tests with increasing concurrency
 * - Performance regression detection
 * - Real-world scenario simulations
 */
class PerformanceBenchmarkTest : BehaviorSpec({
    
    data class BenchmarkResult(
        val name: String,
        val throughputEventsPerSecond: Double,
        val averageLatencyMs: Double,
        val p95LatencyMs: Double,
        val p99LatencyMs: Double,
        val memoryUsageMB: Double,
        val gcPressureMB: Double,
        val executionTimeMs: Long
    ) {
        fun summary(): String {
            return """
                Benchmark: $name
                Throughput: ${String.format("%.0f", throughputEventsPerSecond)} events/sec
                Avg Latency: ${String.format("%.2f", averageLatencyMs)} ms
                P95 Latency: ${String.format("%.2f", p95LatencyMs)} ms
                P99 Latency: ${String.format("%.2f", p99LatencyMs)} ms
                Memory Usage: ${String.format("%.1f", memoryUsageMB)} MB
                GC Pressure: ${String.format("%.1f", gcPressureMB)} MB
                Execution Time: ${executionTimeMs} ms
            """.trimIndent()
        }
    }
    
    class BenchmarkRunner {
        fun runBenchmark(
            name: String,
            eventCount: Int,
            setup: () -> Unit,
            operation: () -> Unit,
            teardown: () -> Unit = {}
        ): BenchmarkResult {
            val latencies = mutableListOf<Double>()
            
            setup()
            
            // Warmup
            repeat(eventCount / 10) {
                operation()
            }
            
            // Force GC before measurement
            System.gc()
            Thread.sleep(100)
            
            val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val startTime = System.nanoTime()
            
            // Actual benchmark
            repeat(eventCount) {
                val opStartTime = System.nanoTime()
                operation()
                val opEndTime = System.nanoTime()
                latencies.add((opEndTime - opStartTime) / 1_000_000.0) // Convert to ms
            }
            
            val endTime = System.nanoTime()
            val totalTimeMs = (endTime - startTime) / 1_000_000
            
            // Force GC to measure pressure
            System.gc()
            Thread.sleep(100)
            System.gc()
            
            val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val memoryUsageMB = finalMemory / (1024.0 * 1024.0)
            val gcPressureMB = (finalMemory - initialMemory) / (1024.0 * 1024.0)
            
            teardown()
            
            latencies.sort()
            val throughputEventsPerSecond = eventCount / (totalTimeMs / 1000.0)
            val averageLatencyMs = latencies.average()
            val p95LatencyMs = latencies[(latencies.size * 0.95).toInt()]
            val p99LatencyMs = latencies[(latencies.size * 0.99).toInt()]
            
            return BenchmarkResult(
                name = name,
                throughputEventsPerSecond = throughputEventsPerSecond,
                averageLatencyMs = averageLatencyMs,
                p95LatencyMs = p95LatencyMs,
                p99LatencyMs = p99LatencyMs,
                memoryUsageMB = memoryUsageMB,
                gcPressureMB = gcPressureMB,
                executionTimeMs = totalTimeMs
            )
        }
    }
    
    // Traditional logging implementation for comparison
    class TraditionalLogger {
        private val output = ByteArrayOutputStream()
        private val printStream = PrintStream(output)
        
        fun log(level: String, logger: String, message: String, context: Map<String, String> = emptyMap()) {
            val timestamp = System.currentTimeMillis()
            val contextStr = if (context.isNotEmpty()) {
                context.entries.joinToString(", ") { "${it.key}=${it.value}" }
            } else ""
            
            printStream.println("[$timestamp] $level $logger: $message $contextStr")
        }
        
        fun getOutputSize(): Int = output.size()
        fun clear() = output.reset()
    }
    
    given("benchmarks de rendimiento del sistema de logging") {
        val benchmarkRunner = BenchmarkRunner()
        
        `when`("se mide throughput básico") {
            then("debe superar 50K eventos/segundo con consumer simple") {
                val loggerManager = DefaultLoggerManager()
                val testConsumer = TestLogEventConsumer("ThroughputTest")
                val eventCount = 100_000
                
                val result = benchmarkRunner.runBenchmark(
                    name = "Basic Throughput Test",
                    eventCount = eventCount,
                    setup = {
                        loggerManager.addConsumer(testConsumer)
                    },
                    operation = {
                        runBlocking {
                            val logger = loggerManager.getLogger("BenchmarkLogger")
                            logger.info("Throughput test message")
                        }
                    },
                    teardown = {
                        loggerManager.shutdown()
                    }
                )
                
                println(result.summary())
                
                result.throughputEventsPerSecond shouldBeGreaterThan 50_000.0
                result.averageLatencyMs shouldBeLessThan 0.1 // Less than 0.1ms average
                result.gcPressureMB shouldBeLessThan 10.0 // Less than 10MB GC pressure
                
                // Verify all events were processed
                runBlocking {
                    testConsumer.waitForEvents(eventCount, 30_000L) shouldBe true
                }
            }
            
            then("debe mantener rendimiento con BatchingConsoleConsumer") {
                val loggerManager = DefaultLoggerManager()
                val batchingConsumer = BatchingConsoleConsumer(
                    batchSize = 100,
                    flushTimeoutMs = 10L,
                    queueCapacity = 10_000,
                    enableColors = false
                )
                val eventCount = 50_000
                
                val result = benchmarkRunner.runBenchmark(
                    name = "Batching Console Consumer Throughput",
                    eventCount = eventCount,
                    setup = {
                        loggerManager.addConsumer(batchingConsumer)
                    },
                    operation = {
                        runBlocking {
                            val logger = loggerManager.getLogger("BatchingBenchmark")
                            logger.warn("Batching throughput test message with some additional content")
                        }
                    },
                    teardown = {
                        Thread.sleep(1000) // Allow final batches to flush
                        loggerManager.shutdown()
                    }
                )
                
                println(result.summary())
                
                result.throughputEventsPerSecond shouldBeGreaterThan 25_000.0
                result.averageLatencyMs shouldBeLessThan 0.2 // Slightly higher due to batching
                
                val consumerStats = batchingConsumer.getStats()
                consumerStats.eventsReceived shouldBe eventCount.toLong()
                consumerStats.averageBatchSize shouldBeGreaterThan 20.0 // Good batching
            }
        }
        
        `when`("se compara con logging tradicional") {
            then("debe ser significativamente más rápido que logging tradicional") {
                val eventCount = 10_000
                val traditionalLogger = TraditionalLogger()
                
                // Benchmark traditional logging
                val traditionalResult = benchmarkRunner.runBenchmark(
                    name = "Traditional Logging",
                    eventCount = eventCount,
                    setup = { traditionalLogger.clear() },
                    operation = {
                        traditionalLogger.log(
                            "INFO", 
                            "TraditionalLogger", 
                            "Traditional logging message", 
                            mapOf("key" to "value")
                        )
                    }
                )
                
                // Benchmark high-performance logging
                val loggerManager = DefaultLoggerManager()
                val testConsumer = TestLogEventConsumer("ComparisonTest")
                
                val highPerfResult = benchmarkRunner.runBenchmark(
                    name = "High-Performance Logging",
                    eventCount = eventCount,
                    setup = {
                        loggerManager.addConsumer(testConsumer)
                    },
                    operation = {
                        runBlocking {
                            val logger = loggerManager.getLogger("ComparisonLogger")
                            logger.info("High-performance logging message", 
                                mapOf("key" to "value")
                            )
                        }
                    },
                    teardown = {
                        loggerManager.shutdown()
                    }
                )
                
                println("=== COMPARISON RESULTS ===")
                println(traditionalResult.summary())
                println()
                println(highPerfResult.summary())
                
                // High-performance should be significantly faster
                val throughputImprovement = highPerfResult.throughputEventsPerSecond / traditionalResult.throughputEventsPerSecond
                val latencyImprovement = traditionalResult.averageLatencyMs / highPerfResult.averageLatencyMs
                
                println("\n=== PERFORMANCE IMPROVEMENTS ===")
                println("Throughput improvement: ${String.format("%.1f", throughputImprovement)}x")
                println("Latency improvement: ${String.format("%.1f", latencyImprovement)}x")
                
                throughputImprovement shouldBeGreaterThan 10.0 // At least 10x faster
                latencyImprovement shouldBeGreaterThan 5.0 // At least 5x lower latency
                
                runBlocking {
                    testConsumer.waitForEvents(eventCount, 15_000L) shouldBe true
                }
            }
            
            then("debe usar menos memoria que logging tradicional") {
                val eventCount = 50_000
                val traditionalLogger = TraditionalLogger()
                
                // Traditional logging memory usage
                val traditionalMemoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                
                repeat(eventCount) {
                    traditionalLogger.log(
                        "INFO",
                        "MemoryTestLogger",
                        "Memory usage test message with context data $it",
                        mapOf("iteration" to it.toString(), "batch" to (it / 100).toString())
                    )
                }
                
                System.gc()
                Thread.sleep(100)
                val traditionalMemoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val traditionalMemoryUsage = (traditionalMemoryAfter - traditionalMemoryBefore) / (1024.0 * 1024.0)
                
                // High-performance logging memory usage
                val loggerManager = DefaultLoggerManager()
                val testConsumer = TestLogEventConsumer("MemoryTest")
                loggerManager.addConsumer(testConsumer)
                
                val highPerfMemoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                
                repeat(eventCount) {
                    runBlocking {
                        val logger = loggerManager.getLogger("MemoryTestLogger")
                        logger.info("Memory usage test message with context data $it",
                            mapOf("iteration" to it.toString(), "batch" to (it / 100).toString())
                        )
                    }
                }
                
                runBlocking {
                    testConsumer.waitForEvents(eventCount, 30_000L)
                }
                
                System.gc()
                Thread.sleep(100)
                val highPerfMemoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val highPerfMemoryUsage = (highPerfMemoryAfter - highPerfMemoryBefore) / (1024.0 * 1024.0)
                
                loggerManager.shutdown()
                
                println("Traditional logging memory usage: ${String.format("%.1f", traditionalMemoryUsage)} MB")
                println("High-performance logging memory usage: ${String.format("%.1f", highPerfMemoryUsage)} MB")
                println("Memory efficiency improvement: ${String.format("%.1f", traditionalMemoryUsage / highPerfMemoryUsage)}x")
                
                // High-performance should use less memory due to object pooling
                highPerfMemoryUsage shouldBeLessThan traditionalMemoryUsage
            }
        }
        
        `when`("se mide escalabilidad con concurrencia") {
            then("debe escalar linealmente hasta 50 threads") {
                val threadCounts = listOf(1, 5, 10, 20, 50)
                val results = mutableListOf<Pair<Int, BenchmarkResult>>()
                
                threadCounts.forEach { threadCount ->
                    val loggerManager = DefaultLoggerManager()
                    val testConsumer = TestLogEventConsumer("ScalabilityTest$threadCount")
                    val eventsPerThread = 1000
                    val totalEvents = threadCount * eventsPerThread
                    
                    val result = benchmarkRunner.runBenchmark(
                        name = "Scalability Test - $threadCount threads",
                        eventCount = totalEvents,
                        setup = {
                            loggerManager.addConsumer(testConsumer)
                        },
                        operation = {
                            runBlocking {
                                withContext(Dispatchers.Default) {
                                    (1..threadCount).map { threadId ->
                                        async {
                                            runBlocking {
                                                val logger = loggerManager.getLogger("ScalabilityLogger$threadId")
                                                repeat(eventsPerThread) { eventId ->
                                                    logger.debug("Scalability test thread $threadId event $eventId")
                                                }
                                            }
                                        }
                                    }.awaitAll()
                                }
                            }
                        },
                        teardown = {
                            runBlocking {
                                testConsumer.waitForEvents(totalEvents, 30_000L)
                            }
                            loggerManager.shutdown()
                        }
                    )
                    
                    results.add(threadCount to result)
                    println(result.summary())
                }
                
                // Analyze scalability
                val baselineThroughput = results.first().second.throughputEventsPerSecond
                
                println("\n=== SCALABILITY ANALYSIS ===")
                results.forEach { (threads, result) ->
                    val scalability = result.throughputEventsPerSecond / baselineThroughput
                    println("${threads} threads: ${String.format("%.1f", scalability)}x baseline throughput")
                }
                
                // Should scale well up to 20 threads
                val result20Threads = results.find { it.first == 20 }!!.second
                val scalability20 = result20Threads.throughputEventsPerSecond / baselineThroughput
                scalability20 shouldBeGreaterThan 10.0 // Should scale well
                
                // Should maintain reasonable performance at 50 threads
                val result50Threads = results.find { it.first == 50 }!!.second
                result50Threads.throughputEventsPerSecond shouldBeGreaterThan 10_000.0
            }
            
            then("debe mantener latencia baja bajo alta concurrencia") {
                val loggerManager = DefaultLoggerManager()
                val testConsumer = TestLogEventConsumer("LatencyTest")
                val threadCount = 100
                val eventsPerThread = 100
                val totalEvents = threadCount * eventsPerThread
                
                loggerManager.addConsumer(testConsumer)
                
                val latencies = mutableListOf<Double>()
                
                val executionTime = measureTime {
                    runBlocking {
                        withContext(Dispatchers.Default) {
                            (1..threadCount).map { threadId ->
                                async {
                                    val logger = loggerManager.getLogger("LatencyLogger$threadId")
                                    repeat(eventsPerThread) { eventId ->
                                        val startTime = System.nanoTime()
                                        logger.info("Latency test thread $threadId event $eventId")
                                        val endTime = System.nanoTime()
                                        
                                        synchronized(latencies) {
                                            latencies.add((endTime - startTime) / 1_000_000.0)
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }
                
                runBlocking {
                    testConsumer.waitForEvents(totalEvents, 60_000L) shouldBe true
                }
                loggerManager.shutdown()
                
                latencies.sort()
                val averageLatency = latencies.average()
                val p95Latency = latencies[(latencies.size * 0.95).toInt()]
                val p99Latency = latencies[(latencies.size * 0.99).toInt()]
                val maxLatency = latencies.maxOrNull() ?: 0.0
                
                println("\n=== LATENCY ANALYSIS ===")
                println("Average latency: ${String.format("%.3f", averageLatency)} ms")
                println("P95 latency: ${String.format("%.3f", p95Latency)} ms")
                println("P99 latency: ${String.format("%.3f", p99Latency)} ms")
                println("Max latency: ${String.format("%.3f", maxLatency)} ms")
                println("Execution time: ${executionTime.inWholeMilliseconds} ms")
                
                // Latency requirements
                averageLatency shouldBeLessThan 1.0 // Less than 1ms average
                p95Latency shouldBeLessThan 5.0 // Less than 5ms P95
                p99Latency shouldBeLessThan 10.0 // Less than 10ms P99
            }
        }
        
        `when`("se simula escenarios del mundo real") {
            then("debe manejar carga web típica (mixed levels, context)") {
                val loggerManager = DefaultLoggerManager()
                val batchingConsumer = BatchingConsoleConsumer(
                    batchSize = 50,
                    flushTimeoutMs = 100L,
                    queueCapacity = 2000,
                    enableColors = false
                )
                loggerManager.addConsumer(batchingConsumer)
                
                val webLoadSimulation = {
                    val random = Random.Default
                    val loggers = listOf("AuthService", "UserService", "OrderService", "PaymentService")
                    val levels = listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
                    val weights = listOf(0.4, 0.4, 0.15, 0.05) // Realistic distribution
                    
                    repeat(10_000) { requestId ->
                        val loggerName = loggers.random(random)
                        val level = levels.zip(weights).let { pairs ->
                            val threshold = random.nextDouble()
                            var cumulative = 0.0
                            pairs.first { (_, weight) ->
                                cumulative += weight
                                threshold <= cumulative
                            }.first
                        }
                        
                        val context = LoggingContext(
                            correlationId = "req-$requestId",
                            userId = "user-${random.nextInt(1000)}",
                            sessionId = "session-${random.nextInt(100)}",
                            customData = mapOf(
                                "endpoint" to "/api/endpoint-${random.nextInt(20)}",
                                "method" to listOf("GET", "POST", "PUT", "DELETE").random(random)
                            )
                        )
                        
                        runBlocking {
                            LoggingContext.withContext(context) {
                                val logger = loggerManager.getLogger(loggerName)
                                
                                when (level) {
                                    LogLevel.DEBUG -> logger.debug("Debug trace for request processing")
                                    LogLevel.INFO -> logger.info("Request processed successfully")
                                    LogLevel.WARN -> logger.warn("Slow response detected", 
                                        mapOf("responseTime" to "${random.nextInt(1000, 3000)}ms"))
                                    LogLevel.ERROR -> logger.error("Request processing failed", 
                                        RuntimeException("Simulated error"))
                                    LogLevel.TRACE -> logger.trace("Detailed trace for request processing")
                                    LogLevel.QUIET -> { /* No logging for QUIET level */ }
                                }
                            }
                        }
                    }
                }
                
                val executionTime = measureTime {
                    webLoadSimulation()
                    Thread.sleep(2000) // Allow final processing
                }
                
                val consumerStats = batchingConsumer.getStats()
                val throughput = consumerStats.eventsReceived / (executionTime.inWholeMilliseconds / 1000.0)
                
                println("\n=== WEB LOAD SIMULATION RESULTS ===")
                println("Total events: ${consumerStats.eventsReceived}")
                println("Execution time: ${executionTime.inWholeMilliseconds} ms")
                println("Throughput: ${String.format("%.0f", throughput)} events/sec")
                println("Average batch size: ${String.format("%.1f", consumerStats.averageBatchSize)}")
                println("Events per second: ${String.format("%.0f", consumerStats.eventsPerSecond)}")
                
                loggerManager.shutdown()
                
                // Web load performance requirements
                throughput shouldBeGreaterThan 3_000.0 // Handle typical web load
                consumerStats.averageBatchSize shouldBeGreaterThan 15.0 // Good batching
                consumerStats.isPerformant() shouldBe true
            }
            
            then("debe manejar batch processing con logging intensivo") {
                val loggerManager = DefaultLoggerManager()
                val testConsumer = TestLogEventConsumer("BatchProcessingTest")
                loggerManager.addConsumer(testConsumer)
                
                val batchProcessingSimulation = {
                    val batchSize = 10_000
                    val recordsPerBatch = 100
                    
                    repeat(batchSize) { batchId ->
                        val batchContext = LoggingContext(
                            correlationId = "batch-$batchId",
                            customData = mapOf(
                                "batchId" to batchId.toString(),
                                "batchSize" to recordsPerBatch.toString()
                            )
                        )
                        
                        runBlocking {
                            LoggingContext.withContext(batchContext) {
                                val logger = loggerManager.getLogger("BatchProcessor")
                                logger.info("Starting batch processing")
                                
                                repeat(recordsPerBatch) { recordId ->
                                    if (recordId % 20 == 0) { // Periodic progress logging
                                        logger.debug("Processing record $recordId of $recordsPerBatch")
                                    }
                                    
                                    if (Random.nextDouble() < 0.001) { // Rare errors
                                        logger.error("Failed to process record $recordId",
                                            RuntimeException("Record processing error"))
                                    }
                                }
                                
                                logger.info("Batch processing completed", 
                                    mapOf("recordsProcessed" to recordsPerBatch.toString()))
                            }
                        }
                    }
                }
                
                val executionTime = measureTime {
                    batchProcessingSimulation()
                }
                
                val expectedEvents = 10_000 * (2 + 5 + 1) // 2 info + ~5 debug + ~1 error per batch
                runBlocking {
                    testConsumer.waitForEvents(expectedEvents - 1000, 60_000L) // Allow some variance
                }
                
                val actualEvents = testConsumer.getReceivedEvents().size
                val throughput = actualEvents / (executionTime.inWholeMilliseconds / 1000.0)
                
                println("\n=== BATCH PROCESSING SIMULATION RESULTS ===")
                println("Expected events: ~$expectedEvents")
                println("Actual events: $actualEvents")
                println("Execution time: ${executionTime.inWholeMilliseconds} ms")
                println("Throughput: ${String.format("%.0f", throughput)} events/sec")
                
                loggerManager.shutdown()
                
                // Batch processing performance requirements
                throughput shouldBeGreaterThan 5_000.0
                executionTime shouldBeLessThan 30.seconds
            }
        }
    }
})