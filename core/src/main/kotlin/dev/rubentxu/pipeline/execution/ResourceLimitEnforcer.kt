package dev.rubentxu.pipeline.execution

import dev.rubentxu.pipeline.dsl.DslResourceLimits
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import kotlinx.coroutines.*
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.ThreadMXBean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Enforces resource limits on script execution to prevent resource exhaustion.
 * This class monitors memory usage, CPU time, wall clock time, and thread count
 * during script execution and terminates execution if limits are exceeded.
 */
class ResourceLimitEnforcer(
    private val logger: ILogger
) {
    
    private val activeExecutions = ConcurrentHashMap<String, ExecutionMonitor>()
    private val memoryMXBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    private val threadMXBean: ThreadMXBean = ManagementFactory.getThreadMXBean()
    private val monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Executes a block of code with resource limits enforced.
     */
    suspend fun <T> enforceResourceLimits(
        executionId: String,
        limits: DslResourceLimits?,
        block: suspend () -> T
    ): ResourceLimitedResult<T> {
        
        if (limits == null) {
            // No limits specified, execute normally
            return try {
                val result = block()
                ResourceLimitedResult.Success(
                    result = result,
                    resourceStats = ResourceUsageStats(
                        executionId = executionId,
                        totalWallTimeMs = 0,
                        totalCpuTimeMs = 0,
                        peakMemoryUsedMb = 0,
                        threadsCreated = 0,
                        limitsApplied = DslResourceLimits()
                    )
                )
            } catch (e: Exception) {
                ResourceLimitedResult.Failure(
                    ResourceLimitViolation(
                        type = ResourceLimitType.EXECUTION_ERROR,
                        message = "Execution failed: ${e.message}",
                        actualValue = 0,
                        limitValue = 0
                    )
                )
            }
        }
        
        val monitor = ExecutionMonitor(executionId, limits)
        activeExecutions[executionId] = monitor
        
        try {
            // Start monitoring in background
            val monitoringJob = startMonitoring(monitor)
            
            // Record initial memory state
            val initialMemoryUsed = getCurrentMemoryUsageMb()
            val initialTime = System.currentTimeMillis()
            
            // Execute the block with timeout if wall time limit is specified
            val result = if (limits.maxWallTimeMs != null) {
                withTimeout(limits.maxWallTimeMs!!) {
                    block()
                }
            } else {
                block()
            }
            
            // Calculate final stats
            val finalTime = System.currentTimeMillis()
            val finalMemoryUsed = getCurrentMemoryUsageMb()
            val wallTimeMs = finalTime - initialTime
            
            // Stop monitoring
            monitoringJob.cancel()
            
            // Check if any limits were violated during execution
            val violations = monitor.getViolations()
            if (violations.isNotEmpty()) {
                logger.warn("Resource limit violations detected for execution $executionId: ${violations.joinToString(", ")}")
                return ResourceLimitedResult.Failure(violations.first())
            }
            
            return ResourceLimitedResult.Success(
                result = result,
                resourceStats = ResourceUsageStats(
                    executionId = executionId,
                    totalWallTimeMs = wallTimeMs,
                    totalCpuTimeMs = monitor.getCpuTime(),
                    peakMemoryUsedMb = maxOf(finalMemoryUsed, initialMemoryUsed),
                    threadsCreated = monitor.getThreadCount(),
                    limitsApplied = limits
                )
            )
            
        } catch (e: TimeoutCancellationException) {
            logger.warn("Execution $executionId exceeded wall time limit of ${limits.maxWallTimeMs}ms")
            return ResourceLimitedResult.Failure(
                ResourceLimitViolation(
                    type = ResourceLimitType.WALL_TIME,
                    message = "Wall time limit exceeded",
                    actualValue = limits.maxWallTimeMs ?: 0,
                    limitValue = limits.maxWallTimeMs ?: 0
                )
            )
        } catch (e: Exception) {
            logger.error("Execution $executionId failed with exception: ${e.message}")
            return ResourceLimitedResult.Failure(
                ResourceLimitViolation(
                    type = ResourceLimitType.EXECUTION_ERROR,
                    message = "Execution failed: ${e.message}",
                    actualValue = 0,
                    limitValue = 0
                )
            )
        } finally {
            activeExecutions.remove(executionId)
        }
    }
    
    /**
     * Terminates an active execution.
     */
    fun terminateExecution(executionId: String): Boolean {
        val monitor = activeExecutions[executionId]
        return if (monitor != null) {
            monitor.terminate()
            activeExecutions.remove(executionId)
            logger.info("Terminated execution: $executionId")
            true
        } else {
            logger.warn("Attempted to terminate non-existent execution: $executionId")
            false
        }
    }
    
    /**
     * Gets resource usage statistics for an active execution.
     */
    fun getResourceUsage(executionId: String): ResourceUsageStats? {
        val monitor = activeExecutions[executionId] ?: return null
        return monitor.getCurrentStats()
    }
    
    /**
     * Gets all active executions with their resource usage.
     */
    fun getAllActiveExecutions(): Map<String, ResourceUsageStats> {
        return activeExecutions.mapValues { (_, monitor) -> monitor.getCurrentStats() }
    }
    
    /**
     * Shuts down the resource limit enforcer.
     */
    fun shutdown() {
        monitoringScope.cancel()
        activeExecutions.clear()
        logger.info("Resource limit enforcer shut down")
    }
    
    private fun startMonitoring(monitor: ExecutionMonitor): Job {
        return monitoringScope.launch {
            while (isActive) {
                try {
                    // Check memory usage
                    val currentMemoryMb = getCurrentMemoryUsageMb()
                    monitor.updateMemoryUsage(currentMemoryMb)
                    
                    // Check CPU time
                    val currentCpuTimeMs = getCurrentCpuTimeMs()
                    monitor.updateCpuTime(currentCpuTimeMs)
                    
                    // Check thread count
                    val currentThreadCount = getCurrentThreadCount()
                    monitor.updateThreadCount(currentThreadCount)
                    
                    // Check for violations
                    val violations = monitor.checkLimits()
                    if (violations.isNotEmpty()) {
                        logger.warn("Resource limit violations detected: ${violations.joinToString(", ")}")
                        monitor.addViolations(violations)
                        break // Stop monitoring on first violation
                    }
                    
                    delay(100) // Check every 100ms
                } catch (e: Exception) {
                    logger.error("Error during resource monitoring: ${e.message}")
                    break
                }
            }
        }
    }
    
    private fun getCurrentMemoryUsageMb(): Long {
        val memoryUsage = memoryMXBean.heapMemoryUsage
        return memoryUsage.used / 1024 / 1024
    }
    
    private fun getCurrentCpuTimeMs(): Long {
        // Get CPU time for current thread
        val threadId = Thread.currentThread().id
        return if (threadMXBean.isThreadCpuTimeSupported) {
            threadMXBean.getThreadCpuTime(threadId) / 1_000_000 // Convert nanoseconds to milliseconds
        } else {
            0
        }
    }
    
    private fun getCurrentThreadCount(): Int {
        return threadMXBean.threadCount
    }
    
    /**
     * Monitors resource usage for a specific execution.
     */
    private class ExecutionMonitor(
        val executionId: String,
        val limits: DslResourceLimits
    ) {
        private val startTime = System.currentTimeMillis()
        private val peakMemoryUsed = AtomicLong(0)
        private val totalCpuTime = AtomicLong(0)
        private val threadCount = AtomicLong(0)
        private val violations = mutableListOf<ResourceLimitViolation>()
        private var terminated = false
        
        fun updateMemoryUsage(memoryMb: Long) {
            peakMemoryUsed.updateAndGet { current -> maxOf(current, memoryMb) }
        }
        
        fun updateCpuTime(cpuTimeMs: Long) {
            totalCpuTime.set(cpuTimeMs)
        }
        
        fun updateThreadCount(threads: Int) {
            threadCount.updateAndGet { current -> maxOf(current, threads.toLong()) }
        }
        
        fun checkLimits(): List<ResourceLimitViolation> {
            val currentViolations = mutableListOf<ResourceLimitViolation>()
            
            // Check memory limit
            limits.maxMemoryMb?.let { limit ->
                val current = peakMemoryUsed.get()
                if (current > limit) {
                    currentViolations.add(
                        ResourceLimitViolation(
                            type = ResourceLimitType.MEMORY,
                            message = "Memory usage ($current MB) exceeded limit ($limit MB)",
                            actualValue = current,
                            limitValue = limit.toLong()
                        )
                    )
                }
            }
            
            // Check CPU time limit
            limits.maxCpuTimeMs?.let { limit ->
                val current = totalCpuTime.get()
                if (current > limit) {
                    currentViolations.add(
                        ResourceLimitViolation(
                            type = ResourceLimitType.CPU_TIME,
                            message = "CPU time ($current ms) exceeded limit ($limit ms)",
                            actualValue = current,
                            limitValue = limit
                        )
                    )
                }
            }
            
            // Check wall time limit
            limits.maxWallTimeMs?.let { limit ->
                val current = System.currentTimeMillis() - startTime
                if (current > limit) {
                    currentViolations.add(
                        ResourceLimitViolation(
                            type = ResourceLimitType.WALL_TIME,
                            message = "Wall time ($current ms) exceeded limit ($limit ms)",
                            actualValue = current,
                            limitValue = limit
                        )
                    )
                }
            }
            
            // Check thread count limit
            limits.maxThreads?.let { limit ->
                val current = threadCount.get()
                if (current > limit) {
                    currentViolations.add(
                        ResourceLimitViolation(
                            type = ResourceLimitType.THREADS,
                            message = "Thread count ($current) exceeded limit ($limit)",
                            actualValue = current,
                            limitValue = limit.toLong()
                        )
                    )
                }
            }
            
            return currentViolations
        }
        
        fun addViolations(newViolations: List<ResourceLimitViolation>) {
            violations.addAll(newViolations)
        }
        
        fun getViolations(): List<ResourceLimitViolation> = violations.toList()
        
        fun terminate() {
            terminated = true
        }
        
        fun isTerminated(): Boolean = terminated
        
        fun getCpuTime(): Long = totalCpuTime.get()
        
        fun getThreadCount(): Int = threadCount.get().toInt()
        
        fun getCurrentStats(): ResourceUsageStats {
            return ResourceUsageStats(
                executionId = executionId,
                totalWallTimeMs = System.currentTimeMillis() - startTime,
                totalCpuTimeMs = totalCpuTime.get(),
                peakMemoryUsedMb = peakMemoryUsed.get(),
                threadsCreated = threadCount.get().toInt(),
                limitsApplied = limits
            )
        }
    }
}