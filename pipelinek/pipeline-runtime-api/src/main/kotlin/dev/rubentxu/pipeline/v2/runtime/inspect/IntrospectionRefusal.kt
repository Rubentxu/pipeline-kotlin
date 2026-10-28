package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * Closed ADT of reasons an inspection could not be served.
 * Mirrors the convention (`OutputRefusal`, `EventRecordReadRefusal`
 * `EventFollowRefusal`): a sealed interface per port, no `Either`/`Result` reuse.
 * It names `NoEventStore`, `NoControlRoot`, `UnknownRun`,
 * `LeaseHeldByAnother`, plus the substrate gap `InconsistentLeaseState`.
*/
sealed interface IntrospectionRefusal {

    /** The run is not known to this instance. */
    data class UnknownRun(val runId: String) : IntrospectionRefusal

    /** No `--control-root` was given at startup; there is no journal or lease to consult. */
    data object NoControlRoot : IntrospectionRefusal

    /** The event plane is not configured for this instance. */
    data object NoEventStore : IntrospectionRefusal

    /** The output plane is not configured for this instance. */
    data object NoOutputPlane : IntrospectionRefusal

    /**
     * The run is held by another process whose lease is still live. The `heldBy`
     * and `fencingToken` name the live authority; this is the same fact the
     * cancel port refuses on. The introspection port reports it; it
     * does NOT attempt to take over.
*/
    data class LeaseHeldByAnother(
        val heldBy: String,
        val fencingToken: FencingToken,
    ) : IntrospectionRefusal

    /** The underlying storage failed; `cause` is a short diagnostic. */
    data class StorageError(val cause: String) : IntrospectionRefusal

    /**
     * The journal, the lease record, and the cursor store disagree about the
     * run's state. `details` is a one-line diagnostic so an operator can read
     * what the inspector saw.
*/
    data class InconsistentLeaseState(val details: String) : IntrospectionRefusal

    /**
     * [inspectOperation] was called with an `(operationId, attempt)`
     * pair that the journal has no row for. The case is additive on the
     * Existing `IntrospectionRefusal` ADT (no breaking change). The
     * first-cut adapter used `StorageError("no journal row ...")` for
     * This case; the hardening is to lift the
     * "no row" mode into a typed refusal case so a consumer can
     * `when` on it without parsing the StorageError's cause string.
*/
    data class UnknownOperation(val operationId: String, val attempt: Int) : IntrospectionRefusal
}
