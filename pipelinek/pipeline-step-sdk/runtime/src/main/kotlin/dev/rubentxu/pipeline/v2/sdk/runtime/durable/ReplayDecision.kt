package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Decision returned by [EffectReplayPolicy.decide].
 *
 */
enum class ReplayDecision {
    /** Skip re-execution; return the cached output. */
    SKIP,

    /** Re-execute the operation. */
    RERUN,

    /** Abort the pipeline. */
    ABORT,
}
