package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventStore
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventTail
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope

/**
 * S5.4 — a bounded, FILTERED answer for an observer, assembled only from [EventTail].
 *
 * ## Why this exists instead of filtering in the caller
 *
 * An observer that wants "the next 10 `RunFinished` events after this cursor" holds two things that
 * do not fit together in one call. [EventTail.readAfter] bounds a page by durable ROWS and is the
 * sequence authority, so it is the only thing that may decide order, continuation and `hasMore`. But
 * it applies no [EventQuery], because a store that pages cannot filter without a second authority
 * over which rows count.
 *
 * The old shape resolved that by not paging at all: `history(run, query).filter { cursor == null
 * || it.sequence > cursor.lastSequence }.take(limit)`.
 *
 * **What that failure actually was, measured.** It is tempting to say `history` silently drops the
 * unreadable row, because its result type has no case for one. It does not: `EventStore.eventsFor`
 * cannot represent a refusal and therefore THROWS `UndecodableEventRecordException` at that row. The
 * observed consequence under mutation D-M5 is that one unreadable row destroys the entire read — an
 * unhandled exception, a stack trace, no history, and an exit status the command never chose. The
 * forty readable rows before row 41 are lost too, which is the part that makes it worse than a gap.
 *
 * The second defect — a continuation taken from `envelopes.lastOrNull()`, i.e. from the last DECODED
 * row — is real but unreachable through that path, because the throw always fires first. It is not
 * hypothetical either: it is exactly what re-deriving the continuation here would have reintroduced,
 * and it is proved at the reader level by the page that ENDS in a refusal.
 *
 * Reading through [EventTail] removes the first failure by construction, because `readAfter` is the
 * only method this type can call and it returns refusals. It does not remove the second on its own,
 * because a row-bounded page means a filter can legitimately match nothing in it; the loop below is
 * what turns "this page had no matches" into "ask for the next one" instead of "there is nothing".
 *
 * ## The seam is the point
 *
 * [EventTail] has exactly one method. A fail-open implementation is not a mistake here, it is
 * unrepresentable: this type cannot reach `history`, cannot name `EventSink`, and cannot see the
 * store. Reverting the CLI to `history` is possible; doing it here is not.
 */
object EventPageDrain {

    /**
     * Why the drain stopped. The two cases differ in what the caller may claim afterwards, which is
     * why this is a closed type rather than a result plus a flag: after [Answered] the store said
     * there is nothing more, while after [Stalled] the store SAID there was more and the reader could
     * not reach it. Reporting the second as the first is a silent truncation, and reporting it as
     * nothing at all is the original fail-open.
     */
    sealed interface Outcome {

        /** The accumulated page. Only the case in which the continuation is trustworthy. */
        val page: EventPage

        /**
         * The requested number of matching envelopes was reached, or the store reported no more rows.
         *
         * [page.hasMore] is the store's own answer, copied rather than recomputed: it is true when
         * the drain stopped at the limit with rows still unread, and false when the history ended.
         */
        data class Answered(override val page: EventPage) : Outcome

        /**
         * The store claimed more rows exist and the continuation did not move.
         *
         * Unreachable against both stores today — it is the guard that keeps the loop from spinning
         * if that ever changes, and it fails loudly because the alternative to reporting it is an
         * answer that silently stops short.
         */
        data class Stalled(
            override val page: EventPage,
            val stuckAfter: EventCursor?,
        ) : Outcome
    }

    /**
     * Reads pages from [tail] until [limit] envelopes satisfy [query], or the store reports the end.
     *
     * Every page, and therefore the refusal set, comes from the store's own cut. Nothing here
     * re-decides order, continuation or `hasMore`; the loop only decides whether to ask again.
     *
     * @param limit bounds MATCHING envelopes, not rows, so the flag keeps meaning what it says even
     *   though the store's page bound counts rows. Each page therefore reads at most [limit] rows.
     */
    fun drain(
        tail: EventTail,
        run: ResourceRef,
        query: EventQuery,
        cursor: EventCursor?,
        limit: Int,
    ): Outcome {
        require(limit > 0) { "limit must be positive, got $limit" }

        val envelopes = ArrayList<PipelineEventEnvelope>(minOf(limit, DRAIN_PAGE_HINT))
        val refusals = ArrayList<EventRecordRead.Undecodable>()
        var readFrom = cursor

        while (true) {
            val page = tail.readAfter(run, readFrom, limit)
            val continuation = page.nextCursor
            refusals.addAll(page.refusals)
            for (envelope in page.envelopes) {
                if (envelopes.size == limit) break
                if (query.matches(envelope)) envelopes.add(envelope)
            }

            val reachedLimit = envelopes.size == limit
            val advanced = continuation != null &&
                continuation.lastSequence > (readFrom?.lastSequence ?: 0L)

            if (reachedLimit || !page.hasMore) {
                return Outcome.Answered(
                    EventPage(
                        envelopes = envelopes.toList(),
                        nextCursor = continuation,
                        hasMore = page.hasMore,
                        refusals = refusals.toList(),
                    ),
                )
            }
            if (!advanced) {
                return Outcome.Stalled(
                    page = EventPage(
                        envelopes = envelopes.toList(),
                        nextCursor = continuation,
                        hasMore = true,
                        refusals = refusals.toList(),
                    ),
                    stuckAfter = readFrom,
                )
            }
            readFrom = continuation
        }
    }

    /**
     * The same bounded, filtered read, but answering with the stored [DomainEvent] instead of its
     * [PipelineEventEnvelope].
     *
     * ## Why this is a second entry point and not a flag on [drain]
     *
     * An envelope cannot be turned back into its event. `StageStarted.stageName`, `StepStarted.stepName`
     * and `StageFinished.outcome` are not fields of the projection — the projection is what dropped
     * them — so any conversion here would be fabrication: a codec that invented `"stageName":"stage-0"`
     * to fill the hole would make an observer believe a fact nothing observed. The typed answer has to
     * come from the record, which means the read has to go through the port that returns records.
     *
     * ## Why it reads [store] and not [tail]
     *
     * [EventTail] is one method wide on purpose. Widening it with a `decodeAfter` would take a
     * sequence number back from the caller and re-ask the store for a row it has already paged —
     * which makes the caller, not the store, responsible for position. [EventStore.readRecords] is the
     * port that already returns the records of a page, and it returns them with the SAME continuation
     * and `hasMore` rule [drain] relies on, so this loop keeps the store as the single authority over
     * position instead of adding a second reader beside it.
     *
     * What is deliberately NOT duplicated: the paging loop, the continuation rule, the stall guard and
     * the refusal accumulation. Those mirror [drain] structurally rather than being re-derived,
     * because a paging bug that exists in one projection and not the other is exactly the kind of
     * divergence that survives review. The filter is shared for the same reason: the query is
     * evaluated against the envelope the store hands back alongside each record, and `kind` is the
     * shared discriminator, so `--kind StepStarted --typed` selects the rows its envelope-mode
     * counterpart selects.
     */
    fun drainTyped(
        store: EventStore,
        run: ResourceRef,
        query: EventQuery,
        cursor: EventCursor?,
        limit: Int,
    ): TypedOutcome {
        require(limit > 0) { "limit must be positive, got $limit" }

        val events = ArrayList<DomainEvent>(minOf(limit, DRAIN_PAGE_HINT))
        val refusals = ArrayList<EventRecordRead.Undecodable>()
        var readFrom = cursor

        while (true) {
            val slice = store.readRecords(run.segments.last(), readFrom, limit)
            val continuation = slice.nextCursor
            refusals.addAll(slice.refusals)
            for (record in slice.records) {
                if (events.size == limit) break
                if (record !is EventRecordRead.Decoded) continue
                if (!query.matches(envelopeOf(record.event))) continue
                events.add(record.event)
            }

            val reachedLimit = events.size == limit
            val advanced = continuation.lastSequence > (readFrom?.lastSequence ?: 0L)

            if (reachedLimit || !slice.hasMore) {
                return TypedOutcome.Answered(
                    TypedPage(
                        events = events.toList(),
                        nextCursor = continuation,
                        hasMore = slice.hasMore,
                        refusals = refusals.toList(),
                    ),
                )
            }
            if (!advanced) {
                return TypedOutcome.Stalled(
                    page = TypedPage(
                        events = events.toList(),
                        nextCursor = continuation,
                        hasMore = true,
                        refusals = refusals.toList(),
                    ),
                    stuckAfter = readFrom,
                )
            }
            readFrom = continuation
        }
    }

    /**
     * The envelope a query is evaluated against when the row is only available as a record.
     *
     * The store hands back the decoded event, and the query's predicates are written over the
     * envelope. Rather than teach [EventQuery] a second form of `matches` — a second authority for
     * what a filter means — the row is projected to the identity envelope it always had, and the
     * EXISTING predicate decides. A filter added later therefore applies to both projections for
     * free, and cannot be implemented for one and forgotten for the other.
     */
    private fun envelopeOf(event: DomainEvent): PipelineEventEnvelope =
        EnvelopeProjector.project(event, null)

    /** The typed page: the events themselves, plus the same continuation and refusal facts. */
    data class TypedPage(
        val events: List<DomainEvent>,
        val nextCursor: EventCursor?,
        val hasMore: Boolean,
        val refusals: List<EventRecordRead.Undecodable>,
    )

    /** Mirrors [Outcome] for the typed read; the two differ only in what the rows contain. */
    sealed interface TypedOutcome {
        val page: TypedPage

        data class Answered(override val page: TypedPage) : TypedOutcome

        data class Stalled(
            override val page: TypedPage,
            val stuckAfter: EventCursor?,
        ) : TypedOutcome
    }
}

/** Only sizes the first allocation; the answer is bounded by `limit`, not by this. */
private const val DRAIN_PAGE_HINT = 64
