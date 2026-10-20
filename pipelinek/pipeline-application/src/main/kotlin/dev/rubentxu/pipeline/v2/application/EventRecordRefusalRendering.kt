package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal

/**
 * The one operator-facing rendering of an event-read refusal.
 *
 * ## Why the text lives here and not at each verb
 * It is a public contract: operators and scripts grep it. `events` is the verb that reads the
 * event lane, and this is the only place that says what a refusal of that lane looks like on
 * the wire. A second spelling would be a second answer to "what did the runtime say", and the
 * two would drift the first time one of them gained a case.
 *
 * ## Why each refusal says something different
 * The three refusals have three different remedies, so the text has to carry the difference:
 * a run that does not exist means stop looking for it, a cursor past the tail means reset the
 * cursor, and a storage failure means the store could not answer. A rendering that folded them
 * into one sentence would throw away the only thing the refusal knew.
 */
internal fun EventRecordReadRefusal.render(): String = when (this) {
    is EventRecordReadRefusal.UnknownRun ->
        "Error: unknown run: $runId"

    is EventRecordReadRefusal.CursorBeyondTail ->
        "Error: cursor $requestedSequence is past the tail of run $runId (tail $tailSequence)"

    is EventRecordReadRefusal.StorageError ->
        "Error: event store failed: $cause"
}
