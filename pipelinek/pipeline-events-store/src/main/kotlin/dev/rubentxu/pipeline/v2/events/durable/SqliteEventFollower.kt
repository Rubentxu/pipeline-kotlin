package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.follow.EventFollower
import dev.rubentxu.pipeline.v2.events.follow.EventFollowHandle
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort

/**
 * Production [EventFollower] backed by the real
 * [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort] (the
 * read port).
 * ## What this is and what it is not
 * The follower is a **factory**: it owns no state of its own, and each
 * call to [open] returns a fresh [SqliteEventFollowHandle] driven by a
 * polling loop on the consumer's thread. No background thread, no
 * coroutine, no new write path.
 * ## The run's existence is the PORT's answer, and only the port's
 * The follower used to carry a "does this run exist" callback of its own and probe the store before
 * the first poll. That was a SECOND authority for one fact, and the two could disagree: the port
 * knows about runs that were declared and have not written an event yet, and a store-side probe
 * does not, so a follow could refuse a run the port would have served — on a fact read from
 * somewhere else.
 * The port already answers the question, and it answers it in the words the follow publishes
 * ([dev.rubentxu.pipeline.v2.events.follow.EventFollowRefusal.UnknownRun]), so the follow asks the
 * port and translates what it says. One authority, one answer, and no probe to pay for.
 * ## Why this is in `:pipeline-events-store` and not in `:pipeline-events`
 * `:pipeline-events` is the published contract; this implementation
 * depends on the Sqlite-backed adapter and the durable stores in
 * `:pipeline-events-store`. Publishing it would hand an external
 * consumer a dependency on the storage implementation, which is
 * Exactly the separation made non-negotiable
*/
class SqliteEventFollower(
    private val port: EventRecordReadPort,
) : EventFollower {

    /**
     * Open a follow handle. The handle is `AutoCloseable`; the
     * consumer MUST close it when finished (a `use { }` block in
     * Kotlin, or a try-with-resources in Java).
     * @param runId the run to follow. If the runId is unknown to the
     *   store at the time of `open`, the handle emits
     *   `Refused(UnknownRun(runId))` and terminates with the next
     *   `hasNext` returning `false`. A cursor past the tail surfaces
     *   as `Refused(CursorBeyondTail(...))`: the run exists and the
     *   position does not, which is a different answer from a missing
     *   run and a different action for the consumer.
     * @param options the polling knobs (`pollIntervalMs`, `maxRecords`,
     *   `after`, `until`, `lagReportInterval`).
    */
    override fun open(runId: String, options: EventFollowOptions): EventFollowHandle =
        SqliteEventFollowHandle(
            port = port,
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
