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
     * ## Where the decisions live
     * The loop, the continuation rule, the stall guard and the refusal accumulation are NOT written
     * here: they live in ONE shared rule, [drainPages], and this function only adapts the pages
     * [EventTail] returns to it. The typed drain answers with the stored event instead of the
     * envelope, so it needs its own adaptation — but the DECISIONS (when to ask again, when to
     * stop, what the cursor names, what the flag says) are made once. Two loops side by side would
     * be two places where a paging bug can live, and each one looks right on its own.
    */
    fun drain(
        tail: EventTail,
        run: ResourceRef,
        query: EventQuery,
        cursor: EventCursor?,
        limit: Int,
    ): Outcome {
        require(limit > 0) { "limit must be positive, got $limit" }

        val progress = drainPages(
            limit = limit,
            query = query,
            runId = run.segments.last(),
            cursor = cursor,
            read = { readFrom ->
                val page = tail.readAfter(run, readFrom, limit)
                DrainPage(
                    rows = page.envelopes.map { envelope ->
                        DrainRow(sequence = envelope.sequence, envelope = envelope, payload = envelope)
                    },
                    refusals = page.refusals,
                    position = page.nextCursor,
                    hasMore = page.hasMore,
                )
            },
        )

        val page = EventPage(
            envelopes = progress.delivered,
            nextCursor = progress.nextCursor,
            hasMore = progress.hasMore,
            refusals = progress.refusals,
        )
        val stall = progress.stall
        return if (stall == null) {
            Outcome.Answered(page = page, runEnded = progress.runEnded)
        } else {
            Outcome.Stalled(page = page, stuckAfter = stall.stuckAfter, runEnded = progress.runEnded)
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
     * and `hasMore` rule [drain] relies on, so this read keeps the store as the single authority over
     * position instead of adding a second reader beside it.
     * ## What is deliberately NOT duplicated
     * The paging loop, the continuation rule, the stall guard and the refusal accumulation are not
     * re-derived here: they live in ONE shared rule, [drainPages], which both drains call with their
     * own pages. The reason is not tidiness — the two loops used to sit side by side, and a paging
     * bug that exists in one projection and not in the other is exactly the kind of divergence that
     * survives review, because each one reads correctly on its own. What stays specialized is the
     * PROJECTION: this function hands out the stored event and [drain] hands out the envelope, and
     * only the payload differs.
     * The filter is shared for the same reason: the query is evaluated against the envelope the store
     * hands back alongside each record, and `kind` is the shared discriminator, so `--kind
     * StepStarted --typed` selects the rows its envelope-mode counterpart selects.
    */
    fun drainTyped(
        store: EventStore,
        run: ResourceRef,
        query: EventQuery,
        cursor: EventCursor?,
        limit: Int,
    ): TypedOutcome {
        require(limit > 0) { "limit must be positive, got $limit" }

        val progress = drainPages(
            limit = limit,
            query = query,
            runId = run.segments.last(),
            cursor = cursor,
            read = { readFrom ->
                val slice = store.readRecords(run.segments.last(), readFrom, limit)
                DrainPage(
                    rows = slice.records.mapNotNull { record ->
                        val decoded = record as? EventRecordRead.Decoded ?: return@mapNotNull null
                        DrainRow(
                            sequence = decoded.event.sequence,
                            envelope = envelopeOf(decoded.event),
                            payload = decoded.event,
                        )
                    },
                    refusals = slice.refusals,
                    position = slice.nextCursor,
                    hasMore = slice.hasMore,
                )
            },
        )

        val page = TypedPage(
            events = progress.delivered,
            nextCursor = progress.nextCursor,
            hasMore = progress.hasMore,
            refusals = progress.refusals,
        )
        val stall = progress.stall
        return if (stall == null) {
            TypedOutcome.Answered(page = page, runEnded = progress.runEnded)
        } else {
            TypedOutcome.Stalled(page = page, stuckAfter = stall.stuckAfter, runEnded = progress.runEnded)
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

/**
 * The ONE rule that decides how a filtered read advances, stops and reports.
 *
 * ## What it owns
 * Everything that is a DECISION and not a projection: when to ask for another page, when to stop
 * because the limit is full, when to stop because the store ran out, when to report a stall, which
 * refusals may be told about now, what the cursor names and what the flag says. Both drains used to
 * spell that out twice, and the two copies were free to drift apart: a paging bug that exists in one
 * projection and not in the other is exactly the kind of divergence that survives review, because
 * each copy reads correctly on its own.
 *
 * ## What it does NOT own
 * The payload. The page arrives already projected: each row carries the envelope the filter is
 * evaluated against and the payload the client is handed, and the two projections differ only in
 * that payload — the envelope one hands out the envelope, the typed one hands out the stored event.
 *
 * ## The read seam
 * [read] is the only thing that differs between the projections: it turns the port's own page into
 * the shape below. It is a function, not a port, so the rule stays pure and testable without a
 * store, and the port stays the single authority over position.
 */
private fun <P> drainPages(
    limit: Int,
    query: EventQuery,
    runId: String,
    cursor: EventCursor?,
    read: (EventCursor?) -> DrainPage<P>,
): DrainProgress<P> {
    val delivered = ArrayList<P>(minOf(limit, DRAIN_PAGE_HINT))
    val refusals = ArrayList<EventRecordRead.Undecodable>()
    var readFrom = cursor
    var lastDelivered: Long? = null
    var maxNotifiedRefusal: Long? = null
    var runEnded = false

    while (true) {
        val page = read(readFrom)
        // The terminal fact is read from the ROWS the store returned, never from the facts this
        // drain hands out: with a filter in play the terminal fact is usually the one fact the
        // filter excludes, and a follow that cannot see it never ends. A row that could not be
        // decoded carries no kind, and a row the client was not handed still counts.
        if (!runEnded && RunTerminal.hasEnded(page.rows.asSequence().map { it.envelope.kind })) {
            runEnded = true
        }
        for (row in page.rows) {
            if (delivered.size == limit) break
            if (query.matches(row.envelope)) {
                delivered.add(row.payload)
                lastDelivered = row.sequence
            }
        }

        val reachedLimit = delivered.size == limit
        val advanced = page.position != null &&
            page.position.lastSequence > (readFrom?.lastSequence ?: 0L)

        if (reachedLimit || !page.hasMore) {
            // The EXIT page. Whether one of its refusals can be told about depends on what the
            // cut left undelivered, and that is only known now that the delivery loop above has
            // run: a refusal behind an undelivered matching fact would force the cursor past that
            // fact and lose it. So the page's refusals are split here, and only the ones no
            // undelivered matching fact precedes are told about.
            val undelivered = undeliveredMatchingSequence(
                page.rows.asSequence().map { it.envelope },
                lastDelivered,
                query,
            )
            val notifiable = notifiableRefusals(page.refusals, undelivered)
            refusals.addAll(notifiable)
            maxNotifiedRefusal = maxRefusalSequence(maxNotifiedRefusal, notifiable)
            return DrainProgress(
                delivered = delivered.toList(),
                nextCursor = clientCursor(runId, lastDelivered, page.position, maxNotifiedRefusal),
                hasMore = clientHasMore(storeHasMore = page.hasMore, undelivered = undelivered),
                refusals = refusals.toList(),
                runEnded = runEnded,
                stall = null,
            )
        }
        // A page that arrives before the limit is reached is consumed whole: every matching row it
        // held was handed out and none of them waits behind the cut. Every row of a later page sits
        // above this one's, so a refusal of this page can be told about now and the cursor can
        // cover it without skipping anything.
        refusals.addAll(page.refusals)
        maxNotifiedRefusal = maxRefusalSequence(maxNotifiedRefusal, page.refusals)

        if (!advanced) {
            return DrainProgress(
                delivered = delivered.toList(),
                nextCursor = clientCursor(runId, lastDelivered, page.position, maxNotifiedRefusal),
                hasMore = true,
                refusals = refusals.toList(),
                runEnded = runEnded,
                stall = DrainStall(stuckAfter = readFrom),
            )
        }
        readFrom = page.position
    }
}

/**
 * One row of a page, as both projections see it: the envelope the filter is evaluated against, the
 * payload the client is handed, and the sequence that names the row's position.
 */
private class DrainRow<P>(
    val sequence: Long,
    val envelope: PipelineEventEnvelope,
    val payload: P,
)

/**
 * One page, in the shape the shared rule reads it: the rows, the refusals, the store's position and
 * the store's own flag. The position is nullable because the envelope page may carry none; the typed
 * page always carries one, and the rule treats a missing position as "the store did not move".
 */
private class DrainPage<P>(
    val rows: List<DrainRow<P>>,
    val refusals: List<EventRecordRead.Undecodable>,
    val position: EventCursor?,
    val hasMore: Boolean,
)

/**
 * Why the drain stopped short: the store promised more rows and the continuation did not move.
 * It is a case of its own rather than a flag because the position it was stuck at can be null — the
 * store did not move from the very beginning — and a null position cannot say which case it was.
 */
private class DrainStall(val stuckAfter: EventCursor?)

/**
 * What the shared rule decided, in the shape the caller needs to build its own answer.
 * [stall] is null when the read was answered; it is the stall case otherwise, carrying the position
 * the reader was stuck at.
 */
private class DrainProgress<P>(
    val delivered: List<P>,
    val nextCursor: EventCursor?,
    val hasMore: Boolean,
    val refusals: List<EventRecordRead.Undecodable>,
    val runEnded: Boolean,
    val stall: DrainStall?,
)

/** Only sizes the first allocation; the answer is bounded by `limit`, not by this. */
private const val DRAIN_PAGE_HINT = 64

/**
 * The position a client may resume from, given what it was handed, where the store is and how far
 * the refusals it was told about reach.
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
 * The cursor is the position of the last fact the client was HANDED, extended to cover every
 * refusal the client was TOLD ABOUT. Both are positions the client actually knows: a fact it
 * holds, and a row it was told exists. The cursor is `max(lastDelivered, highest notified
 * refusal)`.
 *
 * Why the refusal has to be included: a refusal is a row that exists and could not be read. If
 * the client is told about it and the cursor stays below it, the client resumes, the store reads
 * the same row again, and the client is told the same thing a second time — the refusal is
 * reported forever, or until a round that happens to deliver nothing. That was the defect this
 * rule fixes, measured on this tree: three rounds, three copies of the same refusal, and a cursor
 * that never advanced past it.
 *
 * Why it is safe to advance the cursor to a notified refusal: a refusal is notified only when no
 * UNDELIVERED matching fact precedes it, so the notified refusal sits BELOW the first undelivered
 * matching row; and the last delivered fact also sits below it. Their maximum is therefore still
 * below the first undelivered matching fact, so advancing to it never skips one.
 *
 * When the call delivered nothing, there is no fact to point at, and the store's position is the
 * answer: every row it read was considered and none of them matched, so nothing is lost by
 * advancing to it. That is also what lets a client whose filter matches nothing yet keep polling
 * forward instead of rescanning the whole history on every call. The store's position is never
 * below a notified refusal — it is the last row the store read, and every refusal it reported is a
 * row it read.
 *
 * ## What it does not change
 * Without a filter, every row matches, the drain cannot stop in the middle of a page (the page
 * holds at most `limit` rows and all of them match), and the last delivered fact IS the last row
 * of the page: the cursor is exactly the store's position, as before.
 *
 * ## One rule, two projections
 * The envelope drain and the typed drain read through different seams but owe the client the same
 * cursor, so the rule lives HERE and the shared rule calls it for both. A paging rule that exists
 * in one projection and not in the other is a divergence waiting to be discovered by a consumer
 * that reads one of them.
 */
private fun clientCursor(
    runId: String,
    lastDeliveredSequence: Long?,
    storePosition: EventCursor?,
    maxNotifiedRefusalSequence: Long?,
): EventCursor? {
    if (lastDeliveredSequence == null) {
        // Nothing was delivered: the store's position covers every row it read, and every
        // notified refusal sits at or below it.
        return storePosition ?: maxNotifiedRefusalSequence?.let { EventCursor(runId, it) }
    }
    val covered = maxNotifiedRefusalSequence?.let { maxOf(lastDeliveredSequence, it) }
        ?: lastDeliveredSequence
    return EventCursor(runId, covered)
}

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
 * `storeHasMore || undeliveredRemains`. The second term is true when the last page holds a row
 * BEHIND the cut that matches the filter: a row the consumer was not handed and would never see
 * again on its own. The term is cheap because the page holds at most `limit` rows, and it is
 * computed only on the exit path.
 *
 * ## What it does not change
 * Without a filter the drain cannot stop in the middle of a page — the page holds at most `limit`
 * rows and all of them match — so no matching row is ever left behind and the flag IS the store's.
 * A cut that fills the limit exactly with the page's last matching row leaves nothing behind
 * either: the term scans the rows after the last delivered one and finds none.
 */
private fun clientHasMore(
    storeHasMore: Boolean,
    undelivered: Long?,
): Boolean = storeHasMore || undelivered != null

/**
 * The LOWEST sequence of a MATCHING row the limit cut left undelivered, or null when there is
 * none.
 *
 * The page arrives in sequence order and the drain delivers in sequence order, so the rows behind
 * the last delivered fact are exactly the undelivered remainder: no count, no re-derivation —
 * every compared sequence was observed. When the page delivered nothing, [lastDeliveredSequence]
 * names a position from an earlier page and every row of THIS page was considered by the loop,
 * which handed out everything it matched (it only stops handing out when the limit is full, and a
 * limit reached on an earlier page never opens this one); so a null position correctly finds
 * nothing undelivered here.
 *
 * The lowest sequence, and not a boolean, because the number answers a second question: whether a
 * refusal of this page sits BEFORE the cut or behind it. A refusal that precedes the first
 * undelivered matching row can be told about now; one that follows it cannot, because the cursor
 * would have to jump past an undelivered fact to cover it.
 *
 * One rule, two projections: the shared paging rule calls it with the envelopes both projections'
 * rows carry, so the filter semantics stay the one [EventQuery.matches] both drains already use.
 */
private fun undeliveredMatchingSequence(
    visibleEnvelopes: Sequence<PipelineEventEnvelope>,
    lastDeliveredSequence: Long?,
    query: EventQuery,
): Long? {
    val delivered = lastDeliveredSequence ?: return null
    return visibleEnvelopes
        .filter { it.sequence > delivered && query.matches(it) }
        .minOfOrNull { it.sequence }
}

/**
 * The refusals that can be told about NOW: those no undelivered matching row precedes.
 *
 * A refusal is a row that exists and could not be read. Reporting it costs the client a fact
 * about the history, so it may only be reported when the client can also keep its promise about
 * the facts: the cursor that covers the refusal must not skip a matching row that the client was
 * not handed. A refusal that sits below the first undelivered matching row satisfies that — the
 * cursor advances to it and every matching row stays ahead of it. A refusal that sits ABOVE it
 * does not: covering it would push the cursor past an undelivered fact, and the client would never
 * see that fact again.
 *
 * So a refusal is withheld until the round that delivers the fact in front of it. It is not lost:
 * it stays in the store, and the round that delivers the fact reads it again and reports it then.
 * The rule is the same in both projections.
 */
private fun notifiableRefusals(
    refusals: List<EventRecordRead.Undecodable>,
    undeliveredMatchingSequence: Long?,
): List<EventRecordRead.Undecodable> = refusals.filter {
    undeliveredMatchingSequence == null || it.sequence < undeliveredMatchingSequence
}

/**
 * The highest sequence among the refusals told about so far, and the one the cursor must cover.
 *
 * The refusal set is accumulated across pages, so the highest one can come from an EARLIER page
 * than the last. The cursor has to cover the highest, because the client's promise is that it
 * resumes after everything it was already told about; covering only the last would let the
 * earlier one be read again.
 */
private fun maxRefusalSequence(
    current: Long?,
    additions: List<EventRecordRead.Undecodable>,
): Long? {
    val added = additions.maxOfOrNull { it.sequence }
    return when {
        current == null -> added
        added == null -> current
        else -> maxOf(current, added)
    }
}
