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
        // The store cuts the page; this reader only projects it. Cursor, `sequence > after`,
        // ordering, limit, the continuation and `hasMore` are the sequence authority's answer and
        // are decided once, in [EventStore.readRecords].
        //
        // This method used to re-decide all six on a full `eventsFor` scan — and a second
        // implementation of the store's own rules, living in a component that cannot itself be
        // asked what the order is. It also projected every event of the run to an envelope before
        // discarding the ones past the limit, so reading the first page of a long run built
        // envelopes for the whole run. Neither is a memory or a correctness matter today; both are
        // the same defect, which is a second place deciding what the store already decided.
        //
        // P3-E E4c made this call `readSlice`, which is `readRecords(...).requireFullyDecoded()`:
        // an unreadable row stopped the read by refusing the whole page. That was the right default
        // for a port whose result could only carry envelopes, and projecting an unreadable row into
        // an envelope would have been inventing a record the store could not interpret.
        //
        // S5.4 removes the reason that default was necessary, without reintroducing the defect it
        // guarded. [EventPage] now carries the refusals themselves, so this reads the SAME authority
        // the old call wrapped and splits the result instead of collapsing it: the decoded rows are
        // projected to envelopes, the unreadable ones travel on [EventPage.refusals], and the cursor
        // and `hasMore` are copied because they were decided per ROW — a refusal is a row, and
        // dropping it from the count would restart the page at the same place forever.
        //
        // Nothing here re-decides anything. `EventStore.readSlice` remains the strict all-or-nothing
        // variant for a consumer that wants typed events or an exception; this is the variant that
        // can tell a consumer that a row exists and cannot be read.
        val runId = run.segments.last()
        val slice = sink.readRecords(runId, cursor, limit)
        return EventPage(
            envelopes = slice.decoded.map { EnvelopeProjector.project(it, providerLookup) },
            nextCursor = slice.nextCursor,
            hasMore = slice.hasMore,
            refusals = slice.refusals,
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
