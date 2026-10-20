package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.EventRecordSlice

// EventQuery and EventCursor live in this same package; no import needed.
// The only cross-package references are to the typed event hierarchy
// at dev.rubentxu.pipeline.v2.events.DomainEvent (referenced only in
// KDoc) and to the EventRecordSlice data class at
// dev.rubentxu.pipeline.v2.events.EventRecordSlice.

/**
 * P1 — public read-only port for typed [dev.rubentxu.pipeline.v2.events.DomainEvent]
 * reads, paginated by sequence.
 *
 * ## Why this port exists
 *
 * The existing [dev.rubentxu.pipeline.v2.events.EventStore] interface
 * combines a write side (`append`, `appendAssigned`) and a read side
 * (`readRecords`, `readSlice`, `eventsFor`). For external consumers
 * like Fabric, the read side is the only thing needed; the write side
 * is a runtime concern that should not be part of the public ABI.
 *
 * [EventTail.readAfter] is the only existing paged read port, but it
 * returns `EventPage(envelopes: List<PipelineEventEnvelope>, ...)` —
 * wire envelopes, not the typed [dev.rubentxu.pipeline.v2.events.DomainEvent]
 * payload. The M1 design requires a port that returns the typed payload
 * directly, with refusal rows preserved, so consumers do not need to
 * project envelopes themselves.
 *
 * [EventRecordReadPort] is the new port. It is a thin wrapper around
 * [dev.rubentxu.pipeline.v2.events.EventStore.readRecords] that omits
 * the write side. Adding this port does NOT change [EventStore] or
 * [EventTail]; both stay where they are.
 *
 * ## Stability
 *
 * This port is `EXPERIMENTAL` for the M1 first cut (capability ID
 * `events.follow.v1` is published only after the contract test suite
 * is green and the M1-D cross-JVM e2e test is green). The
 * implementation may add a typed `RunId` parameter in a future M-block;
 * the port signature is additive.
 *
 * @see M1_FOLLOW_DESIGN.md §2.3.
 */
interface EventRecordReadPort {

    /**
     * Read a page of typed events starting after [after] (or from the
     * beginning of the run when [after] is `null`), filtered by [query]
     * and bounded by [limit].
     *
     * Returns [EventRecordReadResult.Page] on success. The page carries
     * the typed events (`records.decoded`), the row-level refusals
     * (`records.refusals`), the next cursor to pass back as [after], and
     * `hasMore` to tell the consumer whether the page is complete.
     *
     * Returns [EventRecordReadResult.Refused] when the read cannot be
     * served. The refusal is one of [EventRecordReadRefusal]. The
     * follow contract (`EventFollower`) translates a refusal into a
     * terminal [dev.rubentxu.pipeline.v2.events.follow.EventFollowEvent.Refused]
     * event.
     */
    fun readRecords(
        runId: String,
        after: EventCursor?,
        query: EventQuery,
        limit: Int,
    ): EventRecordReadResult
}

/** The result of a single [EventRecordReadPort.readRecords] call. */
sealed interface EventRecordReadResult {

    /**
     * The page was served. The consumer advances its cursor to
     * [EventRecordSlice.nextCursor] and continues, or terminates if
     * [EventRecordSlice.hasMore] is `false`.
     */
    data class Page(val slice: EventRecordSlice) : EventRecordReadResult

    /** The read could not be served. The consumer MUST NOT retry blindly. */
    data class Refused(val refusal: EventRecordReadRefusal) : EventRecordReadResult
}

/**
 * Closed hierarchy of refusal reasons for [EventRecordReadPort]. Mirrors
 * the existing `OutputRefusal` convention in
 * `v2/pipeline-output/src/main/kotlin/.../output/OutputRefusal.kt`: a
 * closed sealed interface per port, no `Either`/`Result` re-use.
 */
sealed interface EventRecordReadRefusal {

    /** The runId is not known to this store. */
    data class UnknownRun(val runId: String) : EventRecordReadRefusal

    /**
     * The cursor is past the current tail of the run. The follow
     * contract translates this into a refusal and lets the consumer
     * decide whether to reset-and-retry or escalate.
     */
    data class CursorBeyondTail(
        val runId: String,
        val requestedSequence: Long,
        val tailSequence: Long,
    ) : EventRecordReadRefusal

    /** The underlying storage failed; the [cause] is a short diagnostic. */
    data class StorageError(val cause: String) : EventRecordReadRefusal
}
