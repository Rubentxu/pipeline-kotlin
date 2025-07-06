package dev.rubentxu.pipeline.events.handlers

import dev.rubentxu.pipeline.events.*
import dev.rubentxu.pipeline.logger.IPipelineLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Collection of default event handlers that provide common functionality
 * for pipeline event processing.
 */

/**
 * Logs all pipeline events to the provided logger.
 * This handler provides detailed logging of the pipeline execution flow.
 */
class LoggingEventHandler(private val logger: IPipelineLogger) : EventHandler<DomainEvent> {
    
    override suspend fun handle(event: DomainEvent) {
        when (event) {
            is PipelineStarted -> logger.info(
                "Pipeline started: ${event.pipelineName ?: "unnamed"} " +
                "with ${event.stageCount} stages using ${event.agentType} agent"
            )
            
            is PipelineCompleted -> logger.info(
                "Pipeline completed: ${event.status} in ${event.duration}ms. " +
                "Stages: ${event.successfulStages}/${event.totalStages} successful"
            )
            
            is PipelineFailed -> logger.error(
                "Pipeline failed after ${event.duration}ms: ${event.error}. " +
                "Failed at stage: ${event.failedStage ?: "unknown"}, step: ${event.failedStep ?: "unknown"}"
            )
            
            is StageStarted -> logger.info(
                "Stage '${event.stageName}' started (${event.stageIndex + 1}/${event.stepCount} steps)"
            )
            
            is StageCompleted -> logger.info(
                "Stage '${event.stageName}' completed: ${event.status} in ${event.duration}ms. " +
                "Steps: ${event.successfulSteps}/${event.totalSteps} successful"
            )
            
            is StageFailed -> logger.error(
                "Stage '${event.stageName}' failed after ${event.duration}ms: ${event.error}"
            )
            
            is StepStarted -> logger.debug(
                "Step ${event.stepType} started: ${event.stepDescription ?: "no description"}"
            )
            
            is StepCompleted -> logger.debug(
                "Step ${event.stepType} completed: ${event.status} in ${event.duration}ms"
            )
            
            is StepFailed -> logger.error(
                "Step ${event.stepType} failed after ${event.duration}ms: ${event.error}"
            )
            
            is PostExecutionStarted -> logger.info(
                "Post-execution '${event.postType}' started: ${event.stepCount} steps"
            )
            
            is PostExecutionCompleted -> logger.info(
                "Post-execution '${event.postType}' completed: ${event.status} in ${event.duration}ms"
            )
            
            is PostExecutionFailed -> logger.error(
                "Post-execution '${event.postType}' failed after ${event.duration}ms: ${event.error}"
            )
            
            is AgentStarted -> logger.info(
                "Agent ${event.agentType} started"
            )
            
            is AgentStopped -> logger.info(
                "Agent ${event.agentType} stopped after ${event.duration}ms"
            )
            
            is AgentFailed -> logger.error(
                "Agent ${event.agentType} failed after ${event.duration}ms: ${event.error}"
            )
            
            is WarningIssued -> logger.warn(
                "Warning [${event.warningType}]: ${event.message}"
            )
            
            is ErrorOccurred -> logger.error(
                "Error [${event.errorType}]: ${event.message}"
            )
            
            else -> logger.debug("Event: ${event::class.simpleName} - ${event.eventId}")
        }
    }
}

/**
 * Collects metrics and statistics from pipeline events.
 * This handler can be used to generate reports and analytics.
 */
class MetricsCollectorHandler : EventHandler<DomainEvent> {
    private val metrics = mutableMapOf<String, Any>()
    private val durations = mutableListOf<Long>()
    private var totalPipelines = 0
    private var successfulPipelines = 0
    private var failedPipelines = 0
    
    override suspend fun handle(event: DomainEvent) {
        when (event) {
            is PipelineStarted -> {
                totalPipelines++
                metrics["total_pipelines"] = totalPipelines
            }
            
            is PipelineCompleted -> {
                when (event.status) {
                    dev.rubentxu.pipeline.model.pipeline.Status.SUCCESS -> successfulPipelines++
                    dev.rubentxu.pipeline.model.pipeline.Status.FAILURE -> failedPipelines++
                    else -> {} // Handle other statuses if needed
                }
                durations.add(event.duration)
                updateMetrics()
            }
            
            is PipelineFailed -> {
                failedPipelines++
                durations.add(event.duration)
                updateMetrics()
            }
            
            else -> {
                // Handle other domain events
            }
        }
    }
    
    private fun updateMetrics() {
        metrics["successful_pipelines"] = successfulPipelines
        metrics["failed_pipelines"] = failedPipelines
        metrics["success_rate"] = if (totalPipelines > 0) {
            (successfulPipelines.toDouble() / totalPipelines) * 100
        } else 0.0
        
        if (durations.isNotEmpty()) {
            metrics["avg_duration"] = durations.average()
            metrics["min_duration"] = durations.minOrNull() ?: 0
            metrics["max_duration"] = durations.maxOrNull() ?: 0
        }
    }
    
    fun getMetrics(): Map<String, Any> = metrics.toMap()
    
    fun generateReport(): String {
        return buildString {
            appendLine("Pipeline Execution Metrics:")
            appendLine("===========================")
            appendLine("Total Pipelines: ${metrics["total_pipelines"] ?: 0}")
            appendLine("Successful: ${metrics["successful_pipelines"] ?: 0}")
            appendLine("Failed: ${metrics["failed_pipelines"] ?: 0}")
            appendLine("Success Rate: ${"%.2f".format(metrics["success_rate"] ?: 0.0)}%")
            if (durations.isNotEmpty()) {
                appendLine("Average Duration: ${"%.2f".format(metrics["avg_duration"] ?: 0.0)}ms")
                appendLine("Min Duration: ${metrics["min_duration"]}ms")
                appendLine("Max Duration: ${metrics["max_duration"]}ms")
            }
        }
    }
}

/**
 * Handles pipeline failures and can trigger notifications or recovery actions.
 */
class FailureNotificationHandler(
    private val onPipelineFailure: suspend (PipelineFailed) -> Unit = {},
    private val onStageFailure: suspend (StageFailed) -> Unit = {},
    private val onStepFailure: suspend (StepFailed) -> Unit = {}
) : EventHandler<DomainEvent> {
    
    override suspend fun handle(event: DomainEvent) {
        when (event) {
            is PipelineFailed -> onPipelineFailure(event)
            is StageFailed -> onStageFailure(event)
            is StepFailed -> onStepFailure(event)
            else -> {
                // Handle other domain events
            }
        }
    }
}

/**
 * Tracks resource usage throughout pipeline execution.
 */
class ResourceTrackingHandler : EventHandler<ResourceUsageRecorded> {
    private val resourceHistory = mutableListOf<ResourceSnapshot>()
    
    override suspend fun handle(event: ResourceUsageRecorded) {
        resourceHistory.add(
            ResourceSnapshot(
                timestamp = event.timestamp,
                cpuUsage = event.cpuUsage,
                memoryUsage = event.memoryUsage,
                diskUsage = event.diskUsage,
                networkUsage = event.networkUsage
            )
        )
        
        // Keep only last 1000 entries to prevent memory issues
        if (resourceHistory.size > 1000) {
            resourceHistory.removeFirst()
        }
    }
    
    fun getResourceHistory(): List<ResourceSnapshot> = resourceHistory.toList()
    
    fun getAverageResourceUsage(): ResourceSnapshot? {
        if (resourceHistory.isEmpty()) return null
        
        return ResourceSnapshot(
            timestamp = resourceHistory.last().timestamp,
            cpuUsage = resourceHistory.map { it.cpuUsage }.average(),
            memoryUsage = resourceHistory.map { it.memoryUsage }.average().toLong(),
            diskUsage = resourceHistory.map { it.diskUsage }.average().toLong(),
            networkUsage = resourceHistory.map { it.networkUsage }.average().toLong()
        )
    }
    
    data class ResourceSnapshot(
        val timestamp: java.time.Instant,
        val cpuUsage: Double,
        val memoryUsage: Long,
        val diskUsage: Long,
        val networkUsage: Long
    )
}

/**
 * Extension functions for easier event subscription
 */
suspend fun EventBus.subscribeToFailures(handler: suspend (DomainEvent) -> Unit) {
    subscribe<PipelineFailed>().collect { handler(it) }
    subscribe<StageFailed>().collect { handler(it) }
    subscribe<StepFailed>().collect { handler(it) }
}

suspend fun EventBus.subscribeToCompletions(handler: suspend (DomainEvent) -> Unit) {
    subscribe<PipelineCompleted>().collect { handler(it) }
    subscribe<StageCompleted>().collect { handler(it) }
    subscribe<StepCompleted>().collect { handler(it) }
}

suspend fun EventBus.subscribeToStarts(handler: suspend (DomainEvent) -> Unit) {
    subscribe<PipelineStarted>().collect { handler(it) }
    subscribe<StageStarted>().collect { handler(it) }
    subscribe<StepStarted>().collect { handler(it) }
}

/**
 * Creates a formatted event stream for debugging or monitoring
 */
fun Flow<DomainEvent>.formatted(): Flow<String> {
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    return map { event ->
        val eventType = when (event) {
            is PipelineEvent -> "PIPELINE"
            is StageEvent -> "STAGE"
            is StepEvent -> "STEP"
            is PostExecutionEvent -> "POST"
            is AgentEvent -> "AGENT"
            is ResourceEvent -> "RESOURCE"
            else -> "UNKNOWN"
        }
        
        val timestamp = event.timestamp.atZone(ZoneId.systemDefault()).format(formatter)
        "$timestamp [$eventType] ${event.eventId}: ${event::class.simpleName}"
    }
}