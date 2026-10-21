package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Side-effect classification for durable operations.
 *
 * This is the single canonical authority.
 *
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
     * Step writes to the ENGINE's own durable state.
     *
     * A Saga Step that arms a compensation obligation writes a promise into
     * the run's durable store, and none of the values above describes that:
     * it is not a read of local state, it is not a subprocess, it does not
     * abort the pipeline, and it does not write the workspace. Declaring
     * `WRITES_WORKSPACE` would be a lie that a ReplayPolicy
     * resolver would then believe.
     *
     * The value matters for replay: a Step that records a promise must not
     * be memoised as if it had done nothing, because the promise is the
     * effect.
     */
    WRITES_DURABLE_STATE,

    /**
     * Step performs I/O against a remote service (`http.request`, an
     * official plugin that declares this effect).
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
