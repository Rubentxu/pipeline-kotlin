package dev.rubentxu.pipeline.events

import dev.rubentxu.pipeline.model.pipeline.Status
import java.time.Instant
import java.util.UUID

/**
 * Base interface for all domain events in the pipeline execution system.
 * Following Domain-Driven Design principles for event-driven architecture.
 */
sealed interface DomainEvent {
    val eventId: String
    val timestamp: Instant
    val pipelineId: String
    val correlationId: String
}

/**
 * Pipeline lifecycle events
 */
sealed interface PipelineEvent : DomainEvent

/**
 * DSL execution events
 */
sealed interface DslEvent : DomainEvent

data class DslExecutionStarted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val dslType: String,
    val executionId: String,
    val scriptName: String,
    val context: Map<String, Any>
) : DslEvent

data class DslExecutionCompleted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val dslType: String,
    val executionId: String,
    val result: Any?,
    val executionTimeMs: Long
) : DslEvent

data class DslExecutionFailed(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val dslType: String,
    val executionId: String,
    val error: String,
    val cause: String?
) : DslEvent

data class PipelineStarted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val pipelineName: String?,
    val agentType: String,
    val stageCount: Int,
    val environmentVariables: Map<String, String>
) : PipelineEvent

data class PipelineCompleted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val status: Status,
    val duration: Long,
    val totalStages: Int,
    val successfulStages: Int,
    val failedStages: Int
) : PipelineEvent

data class PipelineFailed(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val error: String,
    val failedStage: String?,
    val failedStep: String?,
    val duration: Long
) : PipelineEvent

/**
 * Stage lifecycle events
 */
sealed interface StageEvent : DomainEvent {
    val stageId: String
    val stageName: String
}

data class StageStarted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val stageId: String,
    override val stageName: String,
    val stageIndex: Int,
    val stepCount: Int,
    val agentType: String?
) : StageEvent

data class StageCompleted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val stageId: String,
    override val stageName: String,
    val status: Status,
    val duration: Long,
    val totalSteps: Int,
    val successfulSteps: Int,
    val failedSteps: Int
) : StageEvent

data class StageFailed(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val stageId: String,
    override val stageName: String,
    val error: String,
    val failedStep: String?,
    val duration: Long
) : StageEvent

/**
 * Step execution events
 */
sealed interface StepEvent : DomainEvent {
    val stageId: String
    val stageName: String
    val stepId: String
    val stepType: String
}

data class StepStarted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val stageId: String,
    override val stageName: String,
    override val stepId: String,
    override val stepType: String,
    val stepIndex: Int,
    val stepDescription: String?,
    val parameters: Map<String, Any>
) : StepEvent

data class StepCompleted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val stageId: String,
    override val stageName: String,
    override val stepId: String,
    override val stepType: String,
    val status: Status,
    val duration: Long,
    val output: String?,
    val exitCode: Int?
) : StepEvent

data class StepFailed(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val stageId: String,
    override val stageName: String,
    override val stepId: String,
    override val stepType: String,
    val error: String,
    val duration: Long,
    val exitCode: Int?
) : StepEvent

/**
 * Post-execution events
 */
sealed interface PostExecutionEvent : DomainEvent {
    val postType: String // "always", "success", "failure"
}

data class PostExecutionStarted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val postType: String,
    val stepCount: Int,
    val triggerReason: String
) : PostExecutionEvent

data class PostExecutionCompleted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val postType: String,
    val status: Status,
    val duration: Long,
    val totalSteps: Int,
    val successfulSteps: Int,
    val failedSteps: Int
) : PostExecutionEvent

data class PostExecutionFailed(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val postType: String,
    val error: String,
    val failedStep: String?,
    val duration: Long
) : PostExecutionEvent

/**
 * Agent-related events
 */
sealed interface AgentEvent : DomainEvent {
    val agentType: String
}

data class AgentStarted(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val agentType: String,
    val agentConfiguration: Map<String, Any>
) : AgentEvent

data class AgentStopped(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val agentType: String,
    val duration: Long,
    val exitCode: Int?
) : AgentEvent

data class AgentFailed(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    override val agentType: String,
    val error: String,
    val duration: Long
) : AgentEvent

/**
 * Resource and performance events
 */
sealed interface ResourceEvent : DomainEvent

data class ResourceUsageRecorded(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val cpuUsage: Double,
    val memoryUsage: Long,
    val diskUsage: Long,
    val networkUsage: Long
) : ResourceEvent

/**
 * Error and warning events
 */
sealed interface DiagnosticEvent : DomainEvent

data class WarningIssued(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val warningType: String,
    val message: String,
    val context: Map<String, Any>
) : DiagnosticEvent

data class ErrorOccurred(
    override val eventId: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    override val pipelineId: String,
    override val correlationId: String,
    val errorType: String,
    val message: String,
    val stackTrace: String?,
    val context: Map<String, Any>
) : DiagnosticEvent