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
 * A bounded, FILTERED answer for an observer, assembled only from [EventTail].
 * ## Why this exists instead of filtering in the caller
 * An observer that wants "the next 10 `RunFinished` events after this cursor" holds two things that
 * do not fit together in one call. [EventTail.readAfter] bounds a page by durable ROWS and is the
 * sequence authority, so it is the only thing that may decide order, continuation and `hasMore`. But
 * it applies no [EventQuery], because a store that pages cannot filter without a second authority
 * over which rows count.
 * The old shape resolved that by not paging at all: `history(run, query).filter { cursor == null
 * || it.sequence > cursor.lastSequence }.take(limit)`.
 * **What that failure actually was, measured.** It is tempting to say `history` silently drops the
 * unreadable row, because its result type has no case for one. It does not: `EventStore.eventsFor`
 * cannot represent a refusal and therefore THROWS `UndecodableEventRecordException` at that row. The
 * Observed consequence under mutation is that one unreadable row destroys the entire read — an
 * unhandled exception, a stack trace, no history, and an exit status the command never chose. The
 * forty readable rows before row 41 are lost too, which is the part that makes it worse than a gap.
 * The second defect — a continuation taken from `envelopes.lastOrNull()`, i.e. from the last DECODED
 * row — is real but unreachable through that path, because the throw always fires first. It is not
 * hypothetical either: it is exactly what re-deriving the continuation here would have reintroduced,
 * and it is proved at the reader level by the page that ENDS in a refusal.
 * Reading through [EventTail] removes the first failure by construction, because `readAfter` is the
 * only method this type can call and it returns refusals. It does not remove the second on its own,
 * because a row-bounded page means a filter can legitimately match nothing in it; the loop below is
 * what turns "this page had no matches" into "ask for the next one" instead of "there is nothing".
 * ## The seam is the point
 * [EventTail] has exactly one method. A fail-open implementation is not a mistake here, it is
 * unrepresentable: this type cannot reach `history`, cannot name `EventSink`, and cannot see the
 * store. Reverting the CLI to `history` is possible; doing it here is not.
 * ## Whose cursor the page carries
 * The page this drain returns is a CLIENT's page, and its cursor is the client's position: the
 * sequence of the last fact the consumer was handed. The store's page carries the store's
 * position, and the two are not the same when a filter is in play — the page is bounded by rows
 * and the limit counts matching facts, so the read can stop in the middle of a page, and a cursor
 * that pointed past the facts that were read and not handed out would lose them. The rule that
 * decides this lives in [clientCursor], and both drains call it. See
 * [dev.rubentxu.pipeline.v2.events.identity.EventPage.nextCursor] for the two meanings side by
 * side.
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
         * Whether the rows the STORE returned hold the run's terminal fact.
         *
         * ## Why the drain reports it
         * The drain is the only component that sees the store's rows, so it is the only one that
         * can say whether the run has ended **independently of the filter**. A filter decides what
         * the client is handed; it does not decide whether the run is over. See [RunTerminal] for
         * the rule, and for what happened when the answer was read from the delivered facts
         * instead: the follow with a filter never ended, and polled forever on a finished run.
         *
         * The fact is read from the rows of every page the drain read, not only from the facts it
         * delivered: the terminal fact is often exactly the fact a filter excludes, and a page
         * holds rows the client was not handed.
         */
        val runEnded: Boolean

        /**
         * The requested number of matching envelopes was reached, or the store reported no more rows.
         * [page.hasMore] is the CLIENT's answer, computed by [clientHasMore]: the store's flag alone
         * says nothing about what stands in front of the consumer, because a limit cut leaves
         * matching rows the store already read — and when the store exhausted the history on that
         * same page, its flag is false while the consumer still has a fact ahead. Copying the
         * store's flag used to hand the consumer "nothing remains" with a live fact behind it, and
         * the consumer never asked again.
*/
        data class Answered(override val page: EventPage, override val runEnded: Boolean) : Outcome

        /**
         * The store claimed more rows exist and the continuation did not move.
         * Unreachable against both stores today — it is the guard that keeps the loop from spinning
         * if that ever changes, and it fails loudly because the alternative to reporting it is an
         * answer that silently stops short.
*/
        data class Stalled(
            override val page: EventPage,
            val stuckAfter: EventCursor?,
            override val runEnded: Boolean,
        ) : Outcome
    }

    /**
     * Reads pages from [tail] until [limit] envelopes satisfy [query], or the store reports the end.
     * Every page, and therefore the refusal set, comes from the store's own cut. Nothing here
     * re-decides order, continuation or `hasMore`; the loop only decides whether to ask again.
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
        var lastDelivered: Long? = null
        var runEnded = false

        while (true) {
            val page = tail.readAfter(run, readFrom, limit)
            val continuation = page.nextCursor
            refusals.addAll(page.refusals)
            // The terminal fact is read from the ROWS the store returned, never from the facts
            // this drain hands out: with a filter in play the terminal fact is usually the one
            // fact the filter excludes, and a follow that cannot see it never ends.
            if (!runEnded && RunTerminal.hasEnded(page.envelopes.asSequence().map { it.kind })) {
                runEnded = true
            }
            for (envelope in page.envelopes) {
                if (envelopes.size == limit) break
                if (query.matches(envelope)) {
                    envelopes.add(envelope)
                    lastDelivered = envelope.sequence
                }
            }

            val reachedLimit = envelopes.size == limit
            val advanced = continuation != null &&
                continuation.lastSequence > (readFrom?.lastSequence ?: 0L)

            if (reachedLimit || !page.hasMore) {
                return Outcome.Answered(
                    page = EventPage(
                        envelopes = envelopes.toList(),
                        nextCursor = clientCursor(run.segments.last(), lastDelivered, continuation),
                        hasMore = clientHasMore(
                            storeHasMore = page.hasMore,
                            undeliveredMatchesRemain =
                                undeliveredMatchesRemain(page.envelopes.asSequence(), lastDelivered, query),
                        ),
                        refusals = refusals.toList(),
                    ),
                    runEnded = runEnded,
                )
            }
            if (!advanced) {
                return Outcome.Stalled(
                    page = EventPage(
                        envelopes = envelopes.toList(),
                        nextCursor = clientCursor(run.segments.last(), lastDelivered, continuation),
                        hasMore = true,
                        refusals = refusals.toList(),
                    ),
                    stuckAfter = readFrom,
                    runEnded = runEnded,
                )
            }
            readFrom = continuation
        }
    }

    /**
     * The same bounded, filtered read, but answering with the stored [DomainEvent] instead of its
     * [PipelineEventEnvelope].
     * ## Why this is a second entry point and not a flag on [drain]
     * An envelope cannot be turned back into its event. `StageStarted.stageName`, `StepStarted.stepName`
     * and `StageFinished.outcome` are not fields of the projection — the projection is what dropped
     * them — so any conversion here would be fabrication: a codec that invented `"stageName":"stage-0"`
     * to fill the hole would make an observer believe a fact nothing observed. The typed answer has to
     * come from the record, which means the read has to go through the port that returns records.
     * ## Why it reads [store] and not [tail]
     * [EventTail] is one method wide on purpose. Widening it with a `decodeAfter` would take a
     * sequence number back from the caller and re-ask the store for a row it has already paged —
     * which makes the caller, not the store, responsible for position. [EventStore.readRecords] is the
     * port that already returns the records of a page, and it returns them with the SAME continuation
     * and `hasMore` rule [drain] relies on, so this loop keeps the store as the single authority over
     * position instead of adding a second reader beside it.
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
        var lastDelivered: Long? = null
        var runEnded = false

        while (true) {
            val slice = store.readRecords(run.segments.last(), readFrom, limit)
            val continuation = slice.nextCursor
            refusals.addAll(slice.refusals)
            // The same fact, read from the same rows: a record that could not be decoded carries
            // no kind, and a row the client was not handed still counts.
            if (!runEnded && RunTerminal.hasEnded(
                    slice.records.asSequence().mapNotNull { (it as? EventRecordRead.Decoded)?.event?.kind },
                )
            ) {
                runEnded = true
            }
            for (record in slice.records) {
                if (events.size == limit) break
                if (record !is EventRecordRead.Decoded) continue
                if (!query.matches(envelopeOf(record.event))) continue
                events.add(record.event)
                lastDelivered = record.event.sequence
            }

            val reachedLimit = events.size == limit
            val advanced = continuation.lastSequence > (readFrom?.lastSequence ?: 0L)

            if (reachedLimit || !slice.hasMore) {
                return TypedOutcome.Answered(
                    page = TypedPage(
                        events = events.toList(),
                        nextCursor = clientCursor(run.segments.last(), lastDelivered, continuation),
                        hasMore = clientHasMore(
                            storeHasMore = slice.hasMore,
                            undeliveredMatchesRemain = undeliveredMatchesRemain(
                                slice.records.asSequence().mapNotNull {
                                    if (it is EventRecordRead.Decoded) envelopeOf(it.event) else null
                                },
                                lastDelivered,
                                query,
                            ),
                        ),
                        refusals = refusals.toList(),
                    ),
                    runEnded = runEnded,
                )
            }
            if (!advanced) {
                return TypedOutcome.Stalled(
                    page = TypedPage(
                        events = events.toList(),
                        nextCursor = clientCursor(run.segments.last(), lastDelivered, continuation),
                        hasMore = true,
                        refusals = refusals.toList(),
                    ),
                    stuckAfter = readFrom,
                    runEnded = runEnded,
                )
            }
            readFrom = continuation
        }
    }

    /**
     * The envelope a query is evaluated against when the row is only available as a record.
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

        /** The same fact as [Outcome.runEnded], read from the same rows. */
        val runEnded: Boolean

        data class Answered(override val page: TypedPage, override val runEnded: Boolean) : TypedOutcome

        data class Stalled(
            override val page: TypedPage,
            val stuckAfter: EventCursor?,
            override val runEnded: Boolean,
        ) : TypedOutcome
    }
}

/** Only sizes the first allocation; the answer is bounded by `limit`, not by this. */
private const val DRAIN_PAGE_HINT = 64

/**
 * The position a client may resume from, given what it was handed and where the store is.
 *
 * ## Why the store's position is not always the answer
 * The store's page is bounded by ROWS; the verb's `--limit` counts FACTS THAT MATCH. Two counts
 * over one page, and the drain can stop in the middle of a page: when the matching facts
 * accumulated reach the limit, the rest of the page — which the store already read — is left
 * undelivered. If the cursor were the store's position, those undelivered facts would sit BEHIND
 * the cursor, and a client that resumed from it would never see them. Measured on this tree, with
 * a history whose rows 2, 3 and 4 all match the filter and a limit of two: the first read
 * delivered rows 2 and 3 and handed out a cursor at row 4, and row 4 was **lost** — silently, with
 * exit code 0 and no gap marker.
 *
 * ## The rule
 * The cursor is the position of the last fact the client was HANDED. That is the only position
 * that cannot skip an undelivered fact: everything after it is still ahead of the client, and
 * everything before it was either delivered or did not match the filter.
 *
 * When the call delivered nothing, there is no fact to point at, and the store's position is the
 * answer: every row it read was considered and none of them matched, so nothing is lost by
 * advancing to it. That is also what lets a client whose filter matches nothing yet keep polling
 * forward instead of rescanning the whole history on every call.
 *
 * ## What it does not change
 * Without a filter, every row matches, the drain cannot stop in the middle of a page (the page
 * holds at most `limit` rows and all of them match), and the last delivered fact IS the last row
 * of the page: the cursor is exactly the store's position, as before.
 *
 * ## One rule, two projections
 * The envelope drain and the typed drain read through different seams but owe the client the same
 * cursor, so the rule lives HERE and both call it. A paging rule that exists in one projection
 * and not in the other is a divergence waiting to be discovered by a consumer that reads one of
 * them.
 */
private fun clientCursor(
    runId: String,
    lastDeliveredSequence: Long?,
    storePosition: EventCursor?,
): EventCursor? = lastDeliveredSequence?.let { EventCursor(runId, it) } ?: storePosition

/**
 * Whether the client still has work ahead of it, given where the store stands and what the last
 * page left undelivered.
 *
 * ## The flag is the other half of the cursor's law
 * [clientCursor] answers WHERE the consumer stands: the last fact it was handed. This answers
 * WHAT REMAINS in front of it, and the store's own `hasMore` is not that answer. The two horizons
 * split exactly when the drain stops at the limit: the page is bounded by ROWS, the limit counts
 * MATCHING facts, so the cut can leave matching rows the store already read. When the store
 * exhausted the history on that same page, its flag is FALSE while the consumer still has a fact
 * ahead — and a consumer that reads "nothing remains" never asks again, so the fact is lost with
 * exit code 0 and no gap marker. Measured on this tree: a history whose rows 2, 3 and 4 match a
 * filter and a limit of two delivered rows 2 and 3 with `hasMore=false`, leaving row 4 behind.
 *
 * ## The rule
 * `storeHasMore || undeliveredMatchesRemain`. The second term is true only when the last page
 * holds a row BEHIND the cut that matches the filter: a row the consumer was not handed and would
 * never see again on its own. The term is cheap because the page holds at most `limit` rows, and
 * it is computed only on the exit path.
 *
 * ## What it does not change
 * Without a filter the drain cannot stop in the middle of a page — the page holds at most `limit`
 * rows and all of them match — so no matching row is ever left behind and the flag IS the store's.
 * A cut that fills the limit exactly with the page's last matching row leaves nothing behind
 * either: the term scans the rows after the last delivered one and finds none.
 */
private fun clientHasMore(
    storeHasMore: Boolean,
    undeliveredMatchesRemain: Boolean,
): Boolean = storeHasMore || undeliveredMatchesRemain

/**
 * Whether the page that was just read holds a MATCHING row the limit cut left undelivered.
 *
 * The page arrives in sequence order and the drain delivers in sequence order, so the rows behind
 * the last delivered fact are exactly the undelivered remainder: no count, no re-derivation —
 * every compared sequence was observed. When the page delivered nothing, [lastDeliveredSequence]
 * names a position from an earlier page and every row of THIS page was considered by the loop,
 * which handed out everything it matched (it only stops handing out when the limit is full, and a
 * limit reached on an earlier page never opens this one); so a null position correctly finds
 * nothing undelivered here.
 *
 * One rule, two projections: the envelope drain and the typed drain call it with the envelopes
 * their own rows project to, so the filter semantics stay the one [EventQuery.matches] both loops
 * already use.
 */
private fun undeliveredMatchesRemain(
    visibleEnvelopes: Sequence<PipelineEventEnvelope>,
    lastDeliveredSequence: Long?,
    query: EventQuery,
): Boolean {
    val delivered = lastDeliveredSequence ?: return false
    return visibleEnvelopes.any { it.sequence > delivered && query.matches(it) }
}
