package dev.rubentxu.pipeline.core.jobs

/**
 * Enum representing the status of a pipeline or stage.
 */
enum class Status {
    NotStarted,
    Running,
    Success,
    Failure,
    Unstable,
    Aborted,
    NotBuilt
}