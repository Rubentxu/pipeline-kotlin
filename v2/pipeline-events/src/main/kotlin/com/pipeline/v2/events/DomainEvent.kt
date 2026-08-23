package com.pipeline.v2.events

import com.pipeline.v2.scripting.CacheKey
import com.pipeline.v2.scripting.ScriptingDiagnostic
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
    val stageName: String,
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
    val stepName: String,
    val stepType: String,
) : DomainEvent {
    override val kind: String get() = "StepFinished"
}
