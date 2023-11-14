package dev.rubentxu.pipeline.model.pipeline

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