package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.EventRecordReadPortStoreAdapter
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult

/**
 * Whether a run and a cursor can be read at all, asked through the port that can refuse.
 *
 * ## Why the read had to be asked, and not inferred
 * The paged read ([dev.rubentxu.pipeline.v2.events.EventStore.readRecords]) answers with ROWS, and
 * it cannot refuse: its type has no channel for a refusal. So a read of a run that does not
 * exist, or with a cursor past the end of the history, comes back as an EMPTY page — and an
 * empty page is a perfectly good answer for a run that exists and has not written anything yet.
 * The two situations are indistinguishable from the answer, and the difference matters: one is
 * "there is nothing to show", the other is "you asked for something that is not there".
 *
 * Measured on the shipped binary, before this existed: `pipelinek events <run-inexistente>`
 * answered with exit code 0, no output, and even a continuation cursor for a run that had never
 * existed. An operator could not tell that from a run with no events, and a script that polls
 * could not tell that from "the history ended".
 *
 * ## Why the answer comes from the PORT and not from here
 * The authority for "does this run exist" and "is this cursor inside the run" is the store
 * (`hasRun`, `tailSequence`), and the port that already consults both is
 * [EventRecordReadPortStoreAdapter]. This function does not decide anything: it asks that port
 * and returns what it said. A second place that answered the same question could disagree with
 * the first, and the disagreement would be invisible.
 *
 * ## Why the probe reads ONE row
 * The question is about the run and the cursor, not about the page. One row is enough to answer
 * it, and the read that follows is the one the caller asked for.
 *
 * @return the refusal, or `null` when the run exists and the cursor is inside it.
 */
internal fun admitEventRead(
    store: SqliteEventStore,
    runId: String,
    cursor: EventCursor?,
): EventRecordReadRefusal? {
    val port = EventRecordReadPortStoreAdapter(
        store = store,
        runExists = { candidate -> store.hasRun(candidate) },
        tailSequence = { candidate -> store.tailSequence(candidate) },
    )
    return when (val probe = port.readRecords(runId, cursor, EventQuery.All, 1)) {
        is EventRecordReadResult.Refused -> probe.refusal
        is EventRecordReadResult.Page -> null
    }
}
