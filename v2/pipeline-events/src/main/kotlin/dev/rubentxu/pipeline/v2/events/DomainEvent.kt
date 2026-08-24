package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.time.Instant

/**
 * Sealed hierarchy of domain events emitted during a pipeline run.
 */
sealed interface DomainEvent {
    val eventId: String
    val runId: String
    val sequence: Long
    val kind: String
    val occurredAt: Instant
}

/**
 * Emitted when a pipeline run starts.
 */
data class RunStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val scriptPath: String,
) : DomainEvent {
    override val kind: String get() = "RunStarted"
}

/**
 * Emitted when script compilation starts.
 */
data class CompilationStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
) : DomainEvent {
    override val kind: String get() = "CompilationStarted"
}

/**
 * Emitted when script compilation finishes (success or failure).
 */
data class CompilationFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val cacheKey: CacheKey,
    val diagnostics: List<ScriptingDiagnostic>,
) : DomainEvent {
    override val kind: String get() = "CompilationFinished"
}

/**
 * Emitted when a pipeline run finishes.
 */
data class RunFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val outcome: String,
    val diagnostics: List<ScriptingDiagnostic>,
) : DomainEvent {
    override val kind: String get() = "RunFinished"
}

/**
 * Emitted when a stage in the pipeline starts.
 */
data class StageStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageIndex: Int,
    val stageName: String,
) : DomainEvent {
    override val kind: String get() = "StageStarted"
}

/**
 * Emitted when a stage in the pipeline finishes.
 */
data class StageFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageIndex: Int,
    val stageName: String,
    val outcome: String,
) : DomainEvent {
    override val kind: String get() = "StageFinished"
}

/**
 * Emitted when a step within a stage starts.
 */
data class StepStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageIndex: Int,
    val stepIndex: Int,
    val stepName: String,
    val stepType: String,
) : DomainEvent {
    override val kind: String get() = "StepStarted"
}

/**
 * Emitted when a step within a stage finishes.
 */
data class StepFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageIndex: Int,
    val stepIndex: Int,
    val stepName: String,
    val stepType: String,
) : DomainEvent {
    override val kind: String get() = "StepFinished"
}

/**
 * Emitted when an agent is resolved for parallel execution.
 * Records which agent label was selected and any associated metadata.
 */
data class AgentResolved(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val agentLabel: String,
    val remoteUri: String?,
) : DomainEvent {
    override val kind: String get() = "AgentResolved"
}

/**
 * Emitted when a parallel branch starts execution.
 */
data class ParallelBranchStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val branchIndex: Int,
    val branchName: String,
    val parentStageIndex: Int,
) : DomainEvent {
    override val kind: String get() = "ParallelBranchStarted"
}

/**
 * Emitted when a parallel branch finishes execution.
 */
data class ParallelBranchFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val branchIndex: Int,
    val branchName: String,
    val parentStageIndex: Int,
    val outcome: String,
) : DomainEvent {
    override val kind: String get() = "ParallelBranchFinished"
}

/**
 * Emitted when a retry attempt starts.
 */
data class RetryAttemptStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val attemptNumber: Int,
    val maxAttempts: Int,
    val stepName: String,
    val stepType: String,
    val stageIndex: Int,
    val stepIndex: Int,
) : DomainEvent {
    override val kind: String get() = "RetryAttemptStarted"
}

/**
 * Emitted when a retry attempt finishes.
 */
data class RetryAttemptFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val attemptNumber: Int,
    val maxAttempts: Int,
    val stepName: String,
    val stepType: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val outcome: String,
) : DomainEvent {
    override val kind: String get() = "RetryAttemptFinished"
}

/**
 * Emitted when a timeout is scheduled for a step or stage.
 */
data class TimeoutScheduled(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val timeoutSeconds: Long,
    val timeoutAction: String,
    val stepName: String?,
    val stepType: String?,
    val stageIndex: Int?,
    val stepIndex: Int?,
) : DomainEvent {
    override val kind: String get() = "TimeoutScheduled"
}

/**
 * Emitted when a step fails (e.g., error step type).
 */
data class StepFailed(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stepIndex: Int,
    val stepName: String,
    val stepType: String,
    val failureKind: FailureKind,
    val message: String,
) : DomainEvent {
    override val kind: String get() = "StepFailed"
}

/**
 * Emitted when echo output is captured during step execution.
 */
data class EchoOutputCaptured(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stepIndex: Int,
    val content: String,
) : DomainEvent {
    override val kind: String get() = "EchoOutputCaptured"
}
