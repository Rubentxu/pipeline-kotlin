package dev.rubentxu.pipeline.v2.events.shape

/**
 * M7 — closed ADT of typed events returned by [EventShapeReadPort].
 *
 * The five cases are the typed events the M6 audit names as the source
 * of truth for stage / step boundaries. A sixth case would be a
 * breaking change requiring consumer recompile. The contract test
 * `EventShapeReadPortAdapterTest` pinna the 5-case set (the M7 audit's
 * B.3 hardening item).
 *
 * The `runId` and `occurredAtMs` fields are common to all cases; the
 * type-specific fields are unique to each case.
 */
sealed interface TypedEvent {

    /** The run the event belongs to. */
    val runId: String

    /** The event's recorded timestamp in epoch milliseconds. */
    val occurredAtMs: Long
}

/** Typed shape of a `StageStarted` event. */
data class StageStartedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stageIndex: String,
    val stageName: String,
) : TypedEvent {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stageIndex.isNotBlank()) { "stageIndex must be non-blank" }
        require(stageName.isNotBlank()) { "stageName must be non-blank" }
    }
}

/** Typed shape of a `StageFinished` event. */
data class StageFinishedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stageIndex: String,
    val outcome: String,
) : TypedEvent {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stageIndex.isNotBlank()) { "stageIndex must be non-blank" }
        require(outcome.isNotBlank()) { "outcome must be non-blank" }
    }
}

/** Typed shape of a `StageSkipped` event. */
data class StageSkippedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stageName: String,
    val reason: String,
) : TypedEvent {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stageName.isNotBlank()) { "stageName must be non-blank" }
        require(reason.isNotBlank()) { "reason must be non-blank" }
    }
}

/** Typed shape of a `StepStarted` event. */
data class StepStartedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stepKey: String,
    val stepName: String,
) : TypedEvent {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stepKey.isNotBlank()) { "stepKey must be non-blank" }
        require(stepName.isNotBlank()) { "stepName must be non-blank" }
    }
}

/** Typed shape of a `StepFinished` event. */
data class StepFinishedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stepKey: String,
    val outcome: String,
) : TypedEvent {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stepKey.isNotBlank()) { "stepKey must be non-blank" }
        require(outcome.isNotBlank()) { "outcome must be non-blank" }
    }
}
