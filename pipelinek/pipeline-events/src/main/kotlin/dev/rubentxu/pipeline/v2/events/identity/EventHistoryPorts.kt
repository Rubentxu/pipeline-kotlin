package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.events.EventRecordRead

/**
 * Local event-history ports. Transport-agnostic and storage-agnostic
 * adapters (InMemory, SQLite) implement these; domain/application code depends
 * Only on the interfaces. No remote consumers, no transport in
*/

/**
 * Opaque continuation token over persisted history.
 * Semantics: `runId` + `lastSequence`, where sequence is the STORE-assigned
 * monotonic per-run value (frozen law: the store/boundary owns sequence
 * assignment). Continuation selects events with sequence > lastSequence, ordered
 * by sequence. NEVER timestamp-based. Deliberately a distinct type
 * from the OperationJournal ReplayCursor: event-tail cursors and durable
 * replay cursors are different authorities and MUST NOT be unified.
 *
 * ## The law of projection: a cursor is an observed position, never a derived count
 * Every `lastSequence` this type carries was PRODUCED by one of exactly two
 * observations, and nothing else:
 *  - the sequence of the last row the reader was HANDED (`page.last().sequence`);
 *  - a position the reader itself already named (`after`, a consumer-declared
 *    bound), used verbatim when the page handed over nothing.
 * A count of records — rows read, page size, `start + length` — is never a
 * position: sequences are store-assigned and may skip, so a count over any
 * window diverges from the global position as soon as the window is not the
 * whole space. The divergence is not hypothetical: a continuation token that
 * carried `after + page.size` instead of the last delivered sequence resumed
 * at a position no row occupied and redelivered the same rows. Every new
 * projector goes through the same two observations; if it cannot name one of
 * them, it has no cursor to hand out.
*/
data class EventCursor(val runId: String, val lastSequence: Long) {
    /**
     * Opaque wire form: `evt-cursor-v1:<percent-escaped runId>:<lastSequence>`.
     * The run id is percent-escaped, and that is a fix rather than a detail. `encode()` used to
     * interpolate the run id raw while `decode()` split on `:` and demanded exactly three parts — so
     * any run whose id contained a colon produced a token its own decoder rejected. A run id built
     * the normal way does contain colons: `ResourceRef.canonicalText()` is
     * `v<version>:<kind>:<segments>`. The first version of this pair therefore round-tripped only
     * for hand-written run ids that no producer emits.
     * It was found by an independent consumer build, which could not decode the token it had
     * just encoded. The escaping deliberately matches
     * `OutputCursor`'s: two planes, two prefixes, and a token that decodes or fails loudly rather
     * than decoding to a different run.
*/
    fun encode(): String =
        "evt-cursor-v1:" + java.net.URLEncoder.encode(runId, Charsets.UTF_8) + ":" + lastSequence

    companion object {
        fun decode(token: String): EventCursor? {
            val parts = token.split(":")
            if (parts.size != 3 || parts[0] != "evt-cursor-v1") return null
            val seq = parts[2].toLongOrNull() ?: return null
            val runId = runCatching {
                java.net.URLDecoder.decode(parts[1], Charsets.UTF_8)
            }.getOrNull() ?: return null
            if (runId.isBlank()) return null
            return EventCursor(runId, seq)
        }
    }
}

/**
 * Closed, typed query over local history (80/20 filters only — no query
 * language, no arbitrary predicates).
*/
sealed class EventQuery {

    /** All events of the run, in sequence order. */
    data object All : EventQuery()

    /** Events of the run whose envelope kind matches exactly. */
    data class ByKind(val kind: String) : EventQuery()

    /** Events whose source ResourceRef canonical text matches. */
    data class BySource(val source: ResourceRef) : EventQuery()

    /** Events whose subject ResourceRef canonical text matches. */
    data class BySubject(val subject: ResourceRef) : EventQuery()

    /** Events with store-assigned sequence in [fromSequence, toSequence]. */
    data class BySequenceRange(val fromSequence: Long, val toSequence: Long) : EventQuery()

    /**
     * Whether [envelope] satisfies this query. Pure, total, no I/O.
     * This predicate used to be a private method of [EventHistoryReader], which made it unreachable
     * for a consumer that reads through [EventTail] instead: `readAfter` returns a typed [EventPage]
     * and applies no filter, so a paging consumer holding a `--kind` or `--subject` query had no way
     * to evaluate it. The two available answers were both wrong — re-derive the predicate and own a
     * second implementation of query semantics, or go back to `history` and lose the refusals.
     * It lives on the query because the query is what defines matching. The reader delegates to it,
     * so there is one implementation rather than two that have to be kept in agreement by review.
*/
    fun matches(envelope: PipelineEventEnvelope): Boolean = when (this) {
        is All -> true
        is ByKind -> envelope.kind == kind
        is BySource -> envelope.eventRef.source.canonicalText() == source.canonicalText()
        is BySubject -> envelope.subject.canonicalText() == subject.canonicalText()
        is BySequenceRange -> envelope.sequence in fromSequence..toSequence
    }
}

/**
 * A page of history plus the continuation cursor. Never claims completeness:
 * [remaining] distinguishes a definite end from a truncated page. Absence of a
 * RunFinished event is NOT interpreted as "complete" (law)
*/
data class EventPage(
    val envelopes: List<PipelineEventEnvelope>,
    /**
     * Cursor to resume from.
     * The KDoc used to say "null only when the page reached the end of history", and the code has
     * never done that: the reader always answers with a position, so the null was a promise nothing
     * kept. It is not a good promise either. A `null` cursor already means "start at the beginning"
     * — that is what `readAfter(run, null, limit)` does — so a null continuation could not tell the
     * end of a run apart from its start, and a consumer would have to treat the two identically
     * and guess. [hasMore] is the field that says whether more is coming.
     *
     * **Whose position this is depends on who built the page**, and the two are not the same:
     *  - a page that comes from the STORE ([EventHistoryReader.readAfter]) carries the store's
     *    position: the sequence of the last row the store read. Every row in the page was
     *    considered, so nothing is lost by resuming from there.
     *  - a page that comes from a FILTERED read (the drain in
     *    `dev.rubentxu.pipeline.v2.application.EventPageDrain`) carries the CLIENT's position: the
     *    sequence of the last fact the consumer was handed. The store's position would be wrong
     *    there, because the page is bounded by rows while the limit counts matching facts, and the
     *    read can stop in the middle of a page — leaving matching facts that were read and not
     *    handed out. A cursor that pointed past them would lose them, silently. This was measured
     *    on this tree: a fact disappeared from the stream with exit code 0 and no gap marker.
     *
     * So: the store's page answers "where did the store read up to", and the client's page answers
     * "where does the consumer's stream stand". A consumer of a page must know which one it holds.
*/
    val nextCursor: EventCursor?,
    /**
     * Whether the holder of this page still has work ahead of it.
     * The KDoc of this type used to point at a field named `remaining` that no longer exists; this
     * is that field's successor, and it carries its promise: never claim completeness silently.
     *
     * **Whose horizon this is depends on who built the page**, the same way as [nextCursor]:
     *  - a STORE page's flag says whether the store holds more rows;
     *  - a FILTERED page's flag (the drain in `dev.rubentxu.pipeline.v2.application.EventPageDrain`,
     *    rule `clientHasMore`) says whether the CONSUMER has facts ahead: the store's flag alone is
     *    wrong there, because a limit cut can leave matching rows the store already read — and when
     *    the store exhausted the history on that same page its flag is false while the consumer
     *    still has a fact ahead. Measured on this tree: a consumer was told "nothing remains" with
     *    a live matching fact behind the cut, and lost it with exit code 0.
    */
    val hasMore: Boolean,
    /**
     * The durable rows in this page that exist and could NOT be read.
     * These are not envelopes and must never become envelopes. A row whose `kind` this runtime
     * does not know, or whose payload is unparseable text, is still a row: it occupies its place in
     * the sequence, it is counted by [hasMore], and [nextCursor] moves past it. Before this field
     * it could only be represented by throwing the page away, and a consumer paging history could
     * not tell "there was nothing more" from "there was one more thing I could not read".
     * The type is the store's own, deliberately. `EventRecordSlice.refusals` already produces
     * `EventRecordRead.Undecodable` with the identity read from the row's COLUMNS and the reason
     * already classified as [dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload] or
     * `UnknownKind`. Declaring a page-side type of the same shape would be a second authority of
     * refusal: the store would name one thing and the page another, and nothing could prove they
     * correspond.
     * A refusal names its `sequence`, so the consumer can say WHICH row exists without being able
     * to interpret it. That is the honest answer: the row is accounted for and its contents are
     * unknown.
*/
    val refusals: List<EventRecordRead.Undecodable> = emptyList(),
)

/**
 * Publish-side port: adapters forward confirmed envelopes to the local durable
 * History. adapters are InMemory and SQLite only
*/
interface EventPublisher {
    fun publish(event: PipelineEventEnvelope)
}

/**
 * Read-side port: ordered local history for a run, in store-assigned sequence
 * order. Implementations MUST NOT depend on JDBC or any transport.
*/
interface EventHistory {
    fun history(run: ResourceRef, query: EventQuery = EventQuery.All): Sequence<PipelineEventEnvelope>
}

/**
 * Read-side port for paged continuation. will reuse this local capability
 * For reconnect; proves it in-process only
*/
interface EventTail {
    fun readAfter(run: ResourceRef, cursor: EventCursor?, limit: Int): EventPage
}
