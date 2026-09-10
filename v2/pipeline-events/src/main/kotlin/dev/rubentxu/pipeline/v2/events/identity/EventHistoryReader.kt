package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.events.EventSink

/**
 * Shared read-side implementation over any [EventSink] (InMemory and SQLite
 * adapters get IDENTICAL semantics by construction — contract parity is
 * structural, not incidental).
 *
 * Reads stored DomainEvents (store = sequence authority), projects each to an
 * envelope, and applies the closed 80/20 query filters in Kotlin. Local volumes
 * make in-code filtering appropriate now; pushing filters into SQL with an index
 * on (run_id, sequence, kind) is a recorded optimization trigger, not EVT-2 work.
 */
class EventHistoryReader(private val sink: EventSink) : EventHistory, EventTail {

    override fun history(run: ResourceRef, query: EventQuery): Sequence<PipelineEventEnvelope> {
        val runId = run.segments.last()
        return sink.eventsFor(runId)
            .map { EnvelopeProjector.project(it) }
            .filter { matches(it, query) }
    }

    override fun readAfter(run: ResourceRef, cursor: EventCursor?, limit: Int): EventPage {
        require(limit > 0) { "limit must be positive, got $limit" }
        val runId = run.segments.last()
        val after = cursor?.lastSequence ?: 0L
        // Ordered by store-assigned sequence; cursor continuation is
        // sequence > lastSequence — NEVER occurredAt-based (INC-021d).
        val ordered = sink.eventsFor(runId)
            .map { EnvelopeProjector.project(it) }
            .sortedBy { it.sequence }
        val page = ordered.filter { it.sequence > after }.take(limit).toList()
        val last = page.lastOrNull()?.sequence ?: after
        val maxKnown = ordered.lastOrNull()?.sequence ?: after
        return EventPage(
            envelopes = page,
            nextCursor = EventCursor(runId, last),
            hasMore = maxKnown > last,
        )
    }

    private fun matches(envelope: PipelineEventEnvelope, query: EventQuery): Boolean = when (query) {
        is EventQuery.All -> true
        is EventQuery.ByKind -> envelope.kind == query.kind
        is EventQuery.BySource -> envelope.eventRef.source.canonicalText() == query.source.canonicalText()
        is EventQuery.BySubject -> envelope.subject.canonicalText() == query.subject.canonicalText()
        is EventQuery.BySequenceRange ->
            envelope.sequence in query.fromSequence..query.toSequence
    }
}
