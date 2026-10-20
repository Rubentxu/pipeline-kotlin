package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.events.identity.EventCursor

/**
 * What ONE durable row turned out to be. P3-E E4c — Durable Read Truth.
 *
 * ## The defect this exists to close
 *
 * A durable event row is a fact of persistence. Its interpretation as a [DomainEvent] is a
 * SEPARATE question, and the read side used to answer only the second one while pretending the
 * first never arose:
 *
 * ```kotlin
 * // SqliteEventStore.eventsFor, before
 * JsonEventLog.decode(payload).firstOrNull()?.let { yield(it) }
 *
 * // SqliteEventStore.readSlice, before
 * JsonEventLog.decode(rs.getString(1)).firstOrNull()?.let { page.add(it) }
 * ```
 *
 * With rows `40 valid, 41 malformed, 42 valid`, both consumers produce `40, 42`. The gap is not
 * announced. A consumer cannot tell "there was no event 41" from "a record 41 existed and we
 * could not interpret it", and an external observer reading that stream as history is being told
 * a falsehood by omission — which is worse than a wrong value, because there is nothing in the
 * output to notice.
 *
 * Two things made this reachable rather than theoretical. `JsonEventLog.decodeEvent` ends in
 * `else -> null`, so an event `kind` this binary does not know — a plugin event from a newer
 * schema version, written by a newer runtime — decodes to nothing at all. And since E4b.3 the
 * decoders refuse to invent a semantic field, so a row whose payload lost `stageResult` or
 * `outcome` is malformed BY DESIGN. E4b.3 made the decode honest and thereby made rows that
 * legitimately refuse to decode; E4c is what stops those rows vanishing.
 *
 * ## The law
 *
 * ```
 * persistence ≠ interpretation
 * ```
 *
 * Every row the store holds produces EXACTLY ONE of these two cases. A row that exists is never
 * silently absent, and a row that will not decode is never turned into a fabricated event.
 * Specifically forbidden here, and the reason each is named:
 *
 * - a `MalformedDomainEvent` / `UnknownDomainEvent` standing in for the real one — that converts a
 *   read fault into a domain fact, and a consumer replaying history would act on it;
 * - a synthetic sequence — the sequence is the store's own, and inventing one creates a second
 *   authority for position;
 * - a `mapNotNull` / `?: continue` on the way out — that is the defect, restated;
 * - a default semantic value — that is E4b.3's rule, and it does not become allowed here.
 *
 * ## Why the refusal carries identity
 *
 * [Undecodable] keeps the row's real position and kind so a consumer can act on the refusal
 * instead of guessing: skip it by sequence, stop the run, or page past it deliberately. Without
 * the sequence, the caller could not even resume correctly, because the cursor is the store's
 * sequence authority and a hole in it is indistinguishable from the end of history.
 *
 * The identity is deliberately NOT a fabricated placeholder and NOT the raw payload: the payload
 * is precisely the part that failed to decode, and it can be GiB-scale (the store reads row by row
 * for exactly that reason). What survives is the row's identity — enough to prove a record
 * existed at that position, which is the whole claim E4c makes.
 *
 * [Decoded] carries the event; the row's identity is the event's own, because a decoded event has
 * its sequence by construction.
 */
sealed interface EventRecordRead {

    /** The store-assigned sequence of THIS row, whatever happened to its interpretation. */
    val sequence: Long

    /** The row decoded into a typed domain event. */
    data class Decoded(val event: DomainEvent) : EventRecordRead {
        override val sequence: Long get() = event.sequence
    }

    /**
     * The row exists and could NOT be interpreted.
     *
     * [kind] is the row's own discriminator, so "a record of a kind this binary does not know" and
     * "a record of a known kind whose payload is unreadable" stay distinguishable — the first is a
     * version-skew fact worth reporting upward, the second is a corruption fact worth
     * investigating. Collapsing both into one reason would lose the difference an operator needs.
     *
     * [rawRowPresent] is always true and exists to make the claim explicit at every call site: the
     * row was read, so its absence downstream is a choice somebody made, not a fact about storage.
     */
    data class Undecodable(
        override val sequence: Long,
        val kind: String?,
        val eventId: String?,
        val reason: UndecodableReason,
        val rawRowPresent: Boolean = true,
    ) : EventRecordRead

    /** The identity an [Undecodable] carries, when it has one. */
    val identityOrNull: EventRecordIdentity?
        get() = when (this) {
            is Decoded -> EventRecordIdentity(sequence, event.eventId, event.kind)
            is Undecodable -> EventRecordIdentity(sequence, eventId, kind)
        }
}

/**
 * What the row's own columns said, independent of whether the payload decoded.
 *
 * A refusal built from this is honest by construction: it repeats what storage already held rather
 * than deciding anything. The nulls are the case where the row itself could not supply the value —
 * they are not defaults.
 */
data class EventRecordIdentity(
    val sequence: Long,
    val eventId: String?,
    val kind: String?,
)

/**
 * Why a durable row could not be interpreted. Closed, because a caller that has to choose between
 * "stop" and "page past" cannot decide without knowing which of the two it is looking at.
 */
sealed interface UndecodableReason {

    /**
     * The payload did not satisfy the schema of a kind this binary DOES know.
     *
     * That is E4b.3's fail-closed decoder working as intended — a missing or unknown semantic field
     * refuses rather than defaulting — plus any structural corruption of a known kind.
     */
    data class MalformedPayload(val detail: String) : UndecodableReason

    /**
     * The row's `kind` has no decoder here.
     *
     * Not corruption: a record written by a runtime that knew a kind this one does not. It is the
     * expected shape of reading a newer history with an older binary, and S8 needs it to be
     * representable as a refusal rather than a gap.
     */
    data class UnknownKind(val kind: String) : UndecodableReason
}

/**
 * One bounded page of durable rows, each [Decoded] or [Undecodable], cut by the store's own
 * sequence. P3-E E4c.
 *
 * This is [EventSlice]'s honest counterpart and it replaces [EventSlice] as the authority: the
 * page bound counts ROWS, not decodable events, so a row that refuses to decode cannot shrink the
 * page or corrupt the continuation. [nextCursor] is the last row's sequence whether or not that row
 * decoded, which is what makes a resuming reader able to page past a refusal deliberately instead
 * of re-reading it forever.
 */
data class EventRecordSlice(
    val records: List<EventRecordRead>,
    val nextCursor: EventCursor,
    val hasMore: Boolean,
) {
    /** Only the rows that decoded. NOT a filtered view that hides refusals — read [records] too. */
    val decoded: List<DomainEvent>
        get() = records.mapNotNull { (it as? EventRecordRead.Decoded)?.event }

    /** The refusals in page order. Surfacing them is the point of the type. */
    val refusals: List<EventRecordRead.Undecodable>
        get() = records.filterIsInstance<EventRecordRead.Undecodable>()

    /**
     * The typed page, refusing rather than hiding.
     *
     * A consumer that only accepts semantic events has no honest way to proceed across a row it
     * could not read, and silently skipping one is the very defect E4c closes. So this throws with
     * the offending sequence named, and the caller that CAN tolerate a gap reads [records] and
     * picks its own policy.
     */
    fun requireFullyDecoded(): EventSlice =
        EventSlice(
            events = records.map { record ->
                when (record) {
                    is EventRecordRead.Decoded -> record.event
                    is EventRecordRead.Undecodable -> throw UndecodableEventRecordException(
                        runId = nextCursor.runId,
                        sequence = record.sequence,
                        kind = record.kind,
                        reason = record.reason,
                        message = "a durable event row in this page could not be decoded and this read " +
                            "accepts only semantic events; use readRecords and state a policy instead of " +
                            "reading a page with a hole in it",
                    )
                }
            },
            nextCursor = nextCursor,
            hasMore = hasMore,
        )
}

/**
 * Raised by a read that accepts only semantic events and met a durable row it could not interpret.
 *
 * It exists so the failure is a NAMED condition with the sequence attached, not a short page that a
 * consumer would read as the end of history.
 */
class UndecodableEventRecordException(
    val runId: String,
    val sequence: Long,
    val kind: String?,
    val reason: UndecodableReason?,
    message: String,
) : IllegalStateException(message) {

    override val message: String = buildString {
        append(message)
        append(" (runId=").append(runId).append(", sequence=").append(sequence)
        if (kind != null) append(", kind=").append(kind)
        if (reason != null) append(", reason=").append(renderReason(reason))
        append(')')
    }

    private fun renderReason(reason: UndecodableReason): String = when (reason) {
        is UndecodableReason.MalformedPayload -> "malformedPayload(${reason.detail})"
        is UndecodableReason.UnknownKind -> "unknownKind(${reason.kind})"
    }
}
