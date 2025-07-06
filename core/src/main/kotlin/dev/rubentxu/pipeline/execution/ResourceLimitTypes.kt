package dev.rubentxu.pipeline.execution

import dev.rubentxu.pipeline.dsl.DslResourceLimits

/**
 * Types of resource limits that can be enforced.
 */
enum class ResourceLimitType {
    MEMORY,
    CPU_TIME,
    WALL_TIME,
    THREADS,
    FILE_HANDLES,
    EXECUTION_ERROR
}

/**
 * Represents a violation of a resource limit.
 */
data class ResourceLimitViolation(
    val type: ResourceLimitType,
    val message: String,
    val actualValue: Long,
    val limitValue: Long,
    val timestamp: java.time.Instant = java.time.Instant.now()
)

/**
 * Statistics about resource usage during execution.
 */
data class ResourceUsageStats(
    val executionId: String,
    val totalWallTimeMs: Long,
    val totalCpuTimeMs: Long,
    val peakMemoryUsedMb: Long,
    val threadsCreated: Int,
    val limitsApplied: DslResourceLimits,
    val timestamp: java.time.Instant = java.time.Instant.now()
) {
    
    /**
     * Formats the resource usage statistics in a human-readable format.
     */
    fun toHumanReadable(): String {
        return buildString {
            appendLine("Resource Usage Statistics for execution: $executionId")
            appendLine("- Wall Time: ${totalWallTimeMs}ms")
            appendLine("- CPU Time: ${totalCpuTimeMs}ms")
            appendLine("- Peak Memory: ${peakMemoryUsedMb}MB")
            appendLine("- Threads Created: $threadsCreated")
            appendLine("- Limits Applied:")
            limitsApplied.maxMemoryMb?.let { appendLine("  - Max Memory: ${it}MB") }
            limitsApplied.maxCpuTimeMs?.let { appendLine("  - Max CPU Time: ${it}ms") }
            limitsApplied.maxWallTimeMs?.let { appendLine("  - Max Wall Time: ${it}ms") }
            limitsApplied.maxThreads?.let { appendLine("  - Max Threads: $it") }
            limitsApplied.maxFileHandles?.let { appendLine("  - Max File Handles: $it") }
            appendLine("- Recorded at: $timestamp")
        }
    }
    
    /**
     * Checks if any limits were exceeded based on the applied limits.
     */
    fun hasViolations(): Boolean {
        return when {
            limitsApplied.maxMemoryMb != null && peakMemoryUsedMb > limitsApplied.maxMemoryMb -> true
            limitsApplied.maxCpuTimeMs != null && totalCpuTimeMs > limitsApplied.maxCpuTimeMs -> true
            limitsApplied.maxWallTimeMs != null && totalWallTimeMs > limitsApplied.maxWallTimeMs -> true
            limitsApplied.maxThreads != null && threadsCreated > limitsApplied.maxThreads -> true
            else -> false
        }
    }
    
    /**
     * Gets the efficiency ratio (0.0 to 1.0) for each resource type.
     */
    fun getEfficiencyRatios(): Map<ResourceLimitType, Double> {
        val ratios = mutableMapOf<ResourceLimitType, Double>()
        
        limitsApplied.maxMemoryMb?.let { limit ->
            ratios[ResourceLimitType.MEMORY] = peakMemoryUsedMb.toDouble() / limit.toDouble()
        }
        
        limitsApplied.maxCpuTimeMs?.let { limit ->
            ratios[ResourceLimitType.CPU_TIME] = totalCpuTimeMs.toDouble() / limit.toDouble()
        }
        
        limitsApplied.maxWallTimeMs?.let { limit ->
            ratios[ResourceLimitType.WALL_TIME] = totalWallTimeMs.toDouble() / limit.toDouble()
        }
        
        limitsApplied.maxThreads?.let { limit ->
            ratios[ResourceLimitType.THREADS] = threadsCreated.toDouble() / limit.toDouble()
        }
        
        return ratios
    }
}

/**
 * Result of a resource-limited operation.
 */
sealed class ResourceLimitedResult<out T> {
    
    /**
     * Successful execution within resource limits.
     */
    data class Success<T>(
        val result: T,
        val resourceStats: ResourceUsageStats
    ) : ResourceLimitedResult<T>()
    
    /**
     * Execution failed due to resource limit violation.
     */
    data class Failure(
        val violation: ResourceLimitViolation
    ) : ResourceLimitedResult<Nothing>()
    
    /**
     * Maps the result value if successful, preserving failure.
     */
    fun <R> map(transform: (T) -> R): ResourceLimitedResult<R> {
        return when (this) {
            is Success -> Success(transform(result), resourceStats)
            is Failure -> this
        }
    }
    
    /**
     * Flat maps the result value if successful, preserving failure.
     */
    fun <R> flatMap(transform: (T) -> ResourceLimitedResult<R>): ResourceLimitedResult<R> {
        return when (this) {
            is Success -> transform(result)
            is Failure -> this
        }
    }
    
    /**
     * Returns the result value if successful, or null if failed.
     */
    fun getOrNull(): T? {
        return when (this) {
            is Success -> result
            is Failure -> null
        }
    }
    
    /**
     * Returns the result value if successful, or the default value if failed.
     */
    fun getOrDefault(default: @UnsafeVariance T): @UnsafeVariance T {
        return when (this) {
            is Success -> result
            is Failure -> default
        }
    }
    
    /**
     * Throws an exception if the result is a failure, otherwise returns the result.
     */
    fun getOrThrow(): T {
        return when (this) {
            is Success -> result
            is Failure -> throw ResourceLimitExceededException(violation)
        }
    }
    
    /**
     * Returns true if the result is successful.
     */
    fun isSuccess(): Boolean = this is Success
    
    /**
     * Returns true if the result is a failure.
     */
    fun isFailure(): Boolean = this is Failure
    
    /**
     * Executes the given action if the result is successful.
     */
    fun onSuccess(action: (T, ResourceUsageStats) -> Unit): ResourceLimitedResult<T> {
        if (this is Success) {
            action(result, resourceStats)
        }
        return this
    }
    
    /**
     * Executes the given action if the result is a failure.
     */
    fun onFailure(action: (ResourceLimitViolation) -> Unit): ResourceLimitedResult<T> {
        if (this is Failure) {
            action(violation)
        }
        return this
    }
}

/**
 * Exception thrown when resource limits are exceeded.
 */
class ResourceLimitExceededException(
    val violation: ResourceLimitViolation
) : RuntimeException("Resource limit exceeded: ${violation.message}") {
    
    override fun toString(): String {
        return "ResourceLimitExceededException(${violation.type}): ${violation.message} " +
                "(actual=${violation.actualValue}, limit=${violation.limitValue})"
    }
}

/**
 * Configuration for resource monitoring behavior.
 */
data class ResourceMonitoringConfig(
    val monitoringIntervalMs: Long = 100,
    val enableCpuTimeTracking: Boolean = true,
    val enableMemoryTracking: Boolean = true,
    val enableThreadTracking: Boolean = true,
    val enableFileHandleTracking: Boolean = false,
    val warningThresholdPercent: Double = 0.8,
    val enableEarlyWarnings: Boolean = true
)

/**
 * Resource monitoring event that can be emitted during execution.
 */
sealed class ResourceMonitoringEvent {
    abstract val executionId: String
    abstract val timestamp: java.time.Instant
    
    data class ResourceUsageUpdate(
        override val executionId: String,
        val stats: ResourceUsageStats,
        override val timestamp: java.time.Instant = java.time.Instant.now()
    ) : ResourceMonitoringEvent()
    
    data class ResourceLimitWarning(
        override val executionId: String,
        val type: ResourceLimitType,
        val currentValue: Long,
        val limitValue: Long,
        val thresholdPercent: Double,
        override val timestamp: java.time.Instant = java.time.Instant.now()
    ) : ResourceMonitoringEvent()
    
    data class ResourceLimitViolated(
        override val executionId: String,
        val violation: ResourceLimitViolation,
        override val timestamp: java.time.Instant = java.time.Instant.now()
    ) : ResourceMonitoringEvent()
}