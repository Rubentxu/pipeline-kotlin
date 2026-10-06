package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.events.EventRecordRead

/**
 * EVT-2 local event-history ports. Transport-agnostic and storage-agnostic:
 * adapters (InMemory, SQLite) implement these; domain/application code depends
 * only on the interfaces. No remote consumers, no transport in EVT-2.
 */

/**
 * Opaque continuation token over persisted history.
 *
 * Semantics: `runId` + `lastSequence`, where sequence is the STORE-assigned
 * monotonic per-run value (frozen law: the store/boundary owns sequence
 * assignment). Continuation selects events with sequence > lastSequence, ordered
 * by sequence. NEVER timestamp-based (INC-021d). Deliberately a distinct type
 * from the OperationJournal ReplayCursor: event-tail cursors and durable
 * replay cursors are different authorities and MUST NOT be unified.
 */
data class EventCursor(val runId: String, val lastSequence: Long) {
    /**
     * Opaque wire form: `evt-cursor-v1:<percent-escaped runId>:<lastSequence>`.
     *
     * The run id is percent-escaped, and that is a fix rather than a detail. `encode()` used to
     * interpolate the run id raw while `decode()` split on `:` and demanded exactly three parts — so
     * any run whose id contained a colon produced a token its own decoder rejected. A run id built
     * the normal way does contain colons: `ResourceRef.canonicalText()` is
     * `v<version>:<kind>:<segments>`. The first version of this pair therefore round-tripped only
     * for hand-written run ids that no producer emits.
     *
     * It was found by the independent consumer build in `examples/fabric-contract-consumer`, which
     * could not decode the token it had just encoded. The escaping deliberately matches
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
}

/**
 * A page of history plus the continuation cursor. Never claims completeness:
 * [remaining] distinguishes a definite end from a truncated page. Absence of a
 * RunFinished event is NOT interpreted as "complete" (EVT-2 law).
 */
data class EventPage(
    val envelopes: List<PipelineEventEnvelope>,
    /**
     * Cursor to resume from.
     *
     * The KDoc used to say "null only when the page reached the end of history", and the code has
     * never done that: the reader always answers with a position, so the null was a promise nothing
     * kept. It is not a good promise either. A `null` cursor already means "start at the beginning"
     * — that is what `readAfter(run, null, limit)` does — so a null continuation could not tell the
     * end of a run apart from its start, and a consumer would have to treat the two identically
     * and guess. [hasMore] is the field that says whether more is coming.
     */
    val nextCursor: EventCursor?,
    val hasMore: Boolean,
    /**
     * S5.4 — the durable rows in this page that exist and could NOT be read.
     *
     * These are not envelopes and must never become envelopes. A row whose `kind` this runtime
     * does not know, or whose payload is unparseable text, is still a row: it occupies its place in
     * the sequence, it is counted by [hasMore], and [nextCursor] moves past it. Before this field
     * it could only be represented by throwing the page away, and a consumer paging history could
     * not tell "there was nothing more" from "there was one more thing I could not read".
     *
     * The type is the store's own, deliberately. `EventRecordSlice.refusals` already produces
     * `EventRecordRead.Undecodable` with the identity read from the row's COLUMNS and the reason
     * already classified as [dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload] or
     * `UnknownKind`. Declaring a page-side type of the same shape would be a second authority of
     * refusal: the store would name one thing and the page another, and nothing could prove they
     * correspond.
     *
     * A refusal names its `sequence`, so the consumer can say WHICH row exists without being able
     * to interpret it. That is the honest answer: the row is accounted for and its contents are
     * unknown.
     */
    val refusals: List<EventRecordRead.Undecodable> = emptyList(),
)

/**
 * Publish-side port: adapters forward confirmed envelopes to the local durable
 * history. EVT-2 adapters are InMemory and SQLite only.
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
 * Read-side port for paged continuation. EVT-4 will reuse this local capability
 * for reconnect; EVT-2 proves it in-process only.
 */
interface EventTail {
    fun readAfter(run: ResourceRef, cursor: EventCursor?, limit: Int): EventPage
}
