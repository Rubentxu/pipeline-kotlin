package dev.rubentxu.pipeline.model.jobs

/**
 * Enum representing the status of a pipeline or stage.
 */
enum class Status {
    Success,
    Failure,
    Unstable,
    Aborted,
    NotBuilt
}