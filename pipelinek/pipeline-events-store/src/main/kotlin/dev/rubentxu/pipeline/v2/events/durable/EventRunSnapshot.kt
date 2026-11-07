package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.identity.EventCursor

/**
 * The run's facts, read at ONE instant.
 *
 * ## What it holds
 *
 * - the run's TAIL — its durable `MAX(sequence)`, or `null` when the run holds no row at all;
 * - the PAGE that follows the cursor, cut by the store.
 *
 * The two travel together because they are ONE read: a page that is newer than the tail it is cut
 * against is a page whose answer cannot be believed, and a tail that is older than the page it
 * travels with is a fact that was true when it was read and is not true now. Reading them together
 * makes the disagreement impossible instead of unlikely.
 *
 * ## What it does NOT hold
 *
 * It does not hold whether the run EXISTS: that is the tail's own answer. A run with a row has a
 * tail, and a run without a row has none, so `tail != null` IS the existence — and a second field
 * saying it would be a second authority for one fact, free to disagree with the first.
 *
 * Whether a run was DECLARED and has not produced an event yet is a different fact about a
 * different authority (the lease), and it is not here: this type says what the event plane holds,
 * and the lease says what the run is.
 */
data class EventRunSnapshot(
    /** The durable `MAX(sequence)` of the run, or `null` when the run holds no row. */
    val tail: Long?,
    /** The page of records after the cursor, cut by the store. */
    val page: EventRecordSlice,
)

/**
 * The reader that answers a run's facts in ONE read.
 *
 * ## The contract
 *
 * One call, one instant. The tail and the page come from the same read, so they agree: an append
 * that lands while the read is running is in both of them or in neither, and a consumer can never
 * be told that its cursor is past the end of a run whose page it has not seen.
 *
 * ## What it promises about resources
 *
 * The read is BOUNDED: it opens what it needs, it closes it before returning, and it holds
 * nothing open across the call. No transaction is left open, and no connection is shared with
 * another thread — a shared connection would make two readers see two different instants and
 * would need a lock to keep it from happening.
 *
 * ## Why it exists
 *
 * The facts used to be asked one at a time, each in its own connection and each at its own
 * instant, and a consumer was answered from the mixture. One read that answers them together is
 * the difference between a fact and a guess about a fact.
 */
interface EventRunSnapshotReader {

    /**
     * The run's tail and the page after the cursor, read together.
     *
     * @param runId The run to read.
     * @param after The cursor to resume from, or `null` to start at the beginning.
     * @param limit The bound of the page, in ROWS.
     * @return The tail of the run and the page that follows the cursor.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun snapshotOf(runId: String, after: EventCursor?, limit: Int): EventRunSnapshot
}
