package com.pipeline.v2.domain.durable

/**
 * Side-effect classification for durable operations.
 *
 * Duplicated from [com.pipeline.v2.sdk.Effect] to avoid circular dependency
 * between `:pipeline-domain` and `:pipeline-step-sdk:api`.
 *
 * M3-R2 should reconcile these into a single source of truth.
 *
 * @see <a href="design.md §E4-06">Design §E4-06</a>
 */
enum class Effect {
    /** Step only reads state and does not modify external resources. */
    READ_ONLY,

    /** Step spawns an external process or subprocess. */
    EXECUTES_SUBPROCESS,

    /** Step aborts the entire pipeline when executed. */
    ABORTS_PIPELINE,
}
