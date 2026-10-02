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

    /**
     * Step performs I/O against a remote service (RP6-C / WU-093, `core.httpRequest`).
     *
     * Not one of the three above, and reusing one of them would be a lie in the
     * descriptor: the request is not a read of local state, there is no subprocess,
     * and unless it asks for an `outputFile` it does not write to the workspace. A
     * `ReplayPolicy` resolver that groups by effect then reasons about the wrong thing
     * — a remote POST is not a filesystem write.
     *
     * `Effect` is an enum with no exhaustive `when` over it, so adding a value is
     * source-compatible and forced no existing branch to change.
     */
    NETWORKS,
}
