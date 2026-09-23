package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
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
 *
 * **F5.1 / ADR-0092 / C8**: the reader accepts an optional
 * [providerLookup] seam so envelopes emitted for Step-keyed events can
 * carry the [ProviderProvenance] projection when the Step was registered
 * with [StepProviderMetadata] through the additive
 * [dev.rubentxu.pipeline.v2.domain.step.StepRegistration] path. The
 * lookup is O(1) (the registry's `providerOf(key)`); the projector does
 * NOT scan and does NOT branch on
 * [dev.rubentxu.pipeline.v2.domain.step.Delivery] (delivery is metadata,
 * never a verdict).
 *
 * The default constructor signature is unchanged (providerLookup =
 * null) — existing call sites continue to read envelopes with `null`
 * provenance, which is the C10 backwards-compatible behaviour.
 */
class EventHistoryReader(
    private val sink: EventSink,
    private val providerLookup: ((PluginStepId) -> StepProviderMetadata?)? = null,
) : EventHistory, EventTail {

    constructor(sink: EventSink) : this(sink, null)

    override fun history(run: ResourceRef, query: EventQuery): Sequence<PipelineEventEnvelope> {
        val runId = run.segments.last()
        return sink.eventsFor(runId)
            .map { EnvelopeProjector.project(it, providerLookup) }
            .filter { matches(it, query) }
    }

    override fun readAfter(run: ResourceRef, cursor: EventCursor?, limit: Int): EventPage {
        require(limit > 0) { "limit must be positive, got $limit" }
        val runId = run.segments.last()
        val after = cursor?.lastSequence ?: 0L
        // Ordered by store-assigned sequence; cursor continuation is
        // sequence > lastSequence — NEVER occurredAt-based (INC-021d).
        // WU-RP-044: eventsFor is now single-iteration (lazy SQL stream).
        // Single pass: take limit+1 filtered events — the extra element proves
        // hasMore (same semantics as the previous full-history maxKnown scan)
        // without materialising the whole history.
        val filtered = sink.eventsFor(runId)
            .map { EnvelopeProjector.project(it, providerLookup) }
            .filter { it.sequence > after }
        val pageIterator = filtered.iterator()
        val page = ArrayList<PipelineEventEnvelope>(limit)
        var hasMore = false
        while (pageIterator.hasNext()) {
            if (page.size == limit) {
                hasMore = true // an element beyond the page exists (peeked)
                break
            }
            page.add(pageIterator.next())
        }
        val last = page.lastOrNull()?.sequence ?: after
        return EventPage(
            envelopes = page,
            nextCursor = EventCursor(runId, last),
            hasMore = hasMore,
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
