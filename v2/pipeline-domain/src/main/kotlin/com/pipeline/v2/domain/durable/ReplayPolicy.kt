package com.pipeline.v2.domain.durable

/**
 * Replay policy for durable operations.
 *
 * Duplicated from [com.pipeline.v2.sdk.ReplayPolicy] to avoid circular dependency
 * between `:pipeline-domain` and `:pipeline-step-sdk:api`.
 *
 * M3-R2 should reconcile these into a single source of truth.
 *
 * @see <a href="design.md §E4-06">Design §E4-06</a>
 */
enum class ReplayPolicy {
    /** Output is cached and reused if available; otherwise re-executes. */
    MEMOIZED,

    /** Always re-executes regardless of cached output. */
    RERUN,

    /** Never replays; throws if replay is attempted. */
    NEVER,
}
