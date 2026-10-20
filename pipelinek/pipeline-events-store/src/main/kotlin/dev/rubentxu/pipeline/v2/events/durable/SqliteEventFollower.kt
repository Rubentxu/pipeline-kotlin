package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.follow.EventFollower
import dev.rubentxu.pipeline.v2.events.follow.EventFollowHandle
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort

/**
 * Production [EventFollower] backed by the real
 * [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort] (the
 * read port) composed with the existing event-store authority.
 * ## What this is and what it is not
 * The follower is a **factory**: it owns no state of its own, and each
 * call to [open] returns a fresh [SqliteEventFollowHandle] driven by a
 * polling loop on the consumer's thread. No background thread, no
 * coroutine, no new write path.
 * The constructor takes the read port
 * ([EventRecordReadPort]) plus a [runExists] callback that decides
 * whether a `runId` is known to the underlying store. The callback
 * Matches the `EventRecordReadPortStoreAdapter` convention from
 * a separation between the port (the published contract) and the
 * store-side authority the implementation does not own directly.
 * For a Sqlite-backed adapter the natural implementation is
 * `SqliteEventStore::hasRun`, which the adapter wires through
 * `(store, store::hasRun, store::tailSequence)`.
 * ## Why a callback and not a new port
 * The published contract in `:pipeline-events` does not need a
 * "does this run exist" question on the read side. The
 * `EventRecordReadPort` adapter already answers it for read-side
 * paging purposes (so an empty page is unambiguous). A reader that
 * needs it again at the follow boundary gets it through the same
 * authority by way of a constructor argument. A new port would be
 * additive but it would also be a second way to ask the store about
 * the same fact, and two ways to ask the same store the same
 * question drift.
 * ## Why this is in `:pipeline-events-store` and not in `:pipeline-events`
 * `:pipeline-events` is the published contract; this implementation
 * depends on the Sqlite-backed adapter and the durable stores in
 * `:pipeline-events-store`. Publishing it would hand an external
 * consumer a dependency on the storage implementation, which is
 * Exactly the separation made non-negotiable
*/
class SqliteEventFollower(
    private val port: EventRecordReadPort,
    /**
     * The store-side authority that distinguishes a run that does not
     * exist from a run that exists but has not yet produced any
     * event. The handle calls this BEFORE the first poll so an empty
     * page is unambiguous. The Sqlite adapter supplies
     * `SqliteEventStore::hasRun`.
*/
    private val runExists: (String) -> Boolean,
) : EventFollower {

    /**
     * Open a follow handle. The handle is `AutoCloseable`; the
     * consumer MUST close it when finished (a `use { }` block in
     * Kotlin, or a try-with-resources in Java).
     * @param runId the run to follow. If the runId is unknown to the
     *   store at the time of `open`, the handle emits
     *   `Refused(UnknownRun(runId))` and terminates with the next
     *   `hasNext` returning `false`. A cursor past the tail also
     *   surfaces as `Refused(UnknownRun(runId))` because the cursor
     *   points past known history.
     * @param options the polling knobs (`pollIntervalMs`, `maxRecords`,
     *   `after`, `until`, `lagReportInterval`).
*/
    override fun open(runId: String, options: EventFollowOptions): EventFollowHandle =
        SqliteEventFollowHandle(
            port = port,
            runExists = runExists,
            runId = runId,
            options = options,
        )

    companion object {
        /**
         * The standard 25 ms polling cadence that the in-tree
         * `pipeline observe --follow` loop has used since. The
         * baseline rests on an explicit reason: the wakeup transport
         * (`ObservationWakeup`) is not wired to a real emitter in this
         * repository.
         * Mirrors the constant in
         * `SegmentOutputFollower.FOLLOW_IDLE_MILLIS` so both follow
         * contracts honour the same baseline.
*/
        const val FOLLOW_IDLE_MILLIS: Long = 25L
    }
}
