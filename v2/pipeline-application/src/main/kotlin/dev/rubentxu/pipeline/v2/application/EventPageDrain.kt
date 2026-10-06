package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.events.EventRecordRead
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
}

/** Only sizes the first allocation; the answer is bounded by `limit`, not by this. */
private const val DRAIN_PAGE_HINT = 64
