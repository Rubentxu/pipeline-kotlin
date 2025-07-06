package dev.rubentxu.pipeline.model.pipeline

/**
 * Enum representing the status of a pipeline or stage.
 */
enum class Status {
    SUCCESS,
    FAILURE,
    UNSTABLE,
    ABORTED,
    NOT_BUILT,
    RUNNING,
    PENDING;
    
    companion object {
        fun success() = SUCCESS
        fun failure() = FAILURE
    }
}