package com.pipeline.v2.sdk.runtime.durable

/**
 * Decision returned by [EffectReplayPolicy.decide].
 *
 * @see <a href="design.md §E4-06">Design §E4-06</a>
 */
enum class ReplayDecision {
    /** Skip re-execution; return the cached output. */
    SKIP,

    /** Re-execute the operation. */
    RERUN,

    /** Abort the pipeline. */
    ABORT,
}
