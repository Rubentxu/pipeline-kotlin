package dev.rubentxu.pipeline.v2.events.shape

/**
 * Closed ADT of typed events returned by [EventShapeReadPort].
 * The five cases are the typed events the audit names as the source
 * of truth for stage / step boundaries. A sixth case would be a
 * breaking change requiring consumer recompile.
 * "Five" is not enforced by a test: this ADT is `sealed`, so adding a
 * sixth case is a compile-time fact a reader sees in this file rather than
 * something a red test reports. Until [EventShapeReadPort] stops being
 * EXPERIMENTAL, the declaration itself is the guard, and it is an honest one.
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

/**
 * Typed shape of a `StepFinished` event.
 *
 * ## The verdict, and where the step's type went
 *
 * [outcome] is the step's result — `success`, `unstable` or `failure` — and `null` when the step
 * reached no decided outcome. It used to carry the step's **type** (`sh`, `echo`, …), under a
 * field whose name said something else entirely: a consumer reading `outcome` got a confident
 * answer to a question nobody asked, and no answer to the question it did ask. The type now has
 * its own field, [stepType], and [outcome] means what it says.
 *
 * This is why the capability that carries this shape was re-versioned: a consumer compiled
 * against the old shape read a different thing out of the same field.
 */
data class StepFinishedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stepKey: String,
    val stepType: String,
    val outcome: String?,
) : TypedEvent {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stepKey.isNotBlank()) { "stepKey must be non-blank" }
        require(stepType.isNotBlank()) { "stepType must be non-blank" }
    }
}
