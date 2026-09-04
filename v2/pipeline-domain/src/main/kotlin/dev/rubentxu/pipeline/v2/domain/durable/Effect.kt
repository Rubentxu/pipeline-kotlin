package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Side-effect classification for durable operations.
 *
 * This is the single canonical authority.
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

    /** Step writes to the workspace filesystem (writeFile, archiveArtifacts). */
    WRITES_WORKSPACE,
}
