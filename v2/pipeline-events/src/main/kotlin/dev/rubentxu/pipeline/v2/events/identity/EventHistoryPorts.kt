package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef

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
    /** Opaque wire form: `evt-cursor-v1:<runId>:<lastSequence>`. */
    fun encode(): String = "evt-cursor-v1:$runId:$lastSequence"

    companion object {
        fun decode(token: String): EventCursor? {
            val parts = token.split(":")
            if (parts.size != 3 || parts[0] != "evt-cursor-v1") return null
            val seq = parts[2].toLongOrNull() ?: return null
            return EventCursor(parts[1], seq)
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
    /** Cursor to resume from; null only when the page reached the end of history. */
    val nextCursor: EventCursor?,
    val hasMore: Boolean,
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
