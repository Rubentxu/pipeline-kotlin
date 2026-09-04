package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Replay policy for durable operations.
 *
 * This is the single canonical authority.
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
