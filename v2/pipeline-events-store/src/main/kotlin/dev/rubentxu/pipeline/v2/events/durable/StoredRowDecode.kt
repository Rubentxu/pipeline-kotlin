package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.UndecodableReason

/**
 * What ONE stored row's payload turned out to mean. P3-E E4c.
 *
 * This is the codec's answer at the granularity the STORE needs. [JsonEventLog.decode] answers the
 * same question about a JSON DOCUMENT and returns a list, which is the wrong shape here for a
 * reason that is about types rather than about taste: a durable row is a row the store has already
 * identified, so an empty result cannot be "nothing to report" — it is a row that exists and did
 * not decode, and the caller must be able to say so with that row's sequence attached.
 *
 * Two cases and both carry their own payload, so a caller cannot be tempted to branch on a nullable
 * and default the rest:
 *
 * - [Accepted] — the payload decoded into its typed event;
 * - [Refused] — it did not, and it says [UndecodableReason] why.
 *
 * The reason is closed rather than a string because the difference it preserves is the one an
 * operator acts on. An [UndecodableReason.UnknownKind] is version skew: an older binary reading a
 * history a newer one wrote, which is expected during a rolling upgrade. An
 * [UndecodableReason.MalformedPayload] is corruption or a truncated write, which belongs to whoever
 * owns the store. Collapsing them into one reason would make the first look like an incident.
 */
sealed interface StoredRowDecode {

    /** The payload decoded. The store wraps this as `EventRecordRead.Decoded`. */
    data class Accepted(val event: DomainEvent) : StoredRowDecode

    /** The payload did not decode, and the store MUST report this rather than skip the row. */
    data class Refused(val reason: UndecodableReason) : StoredRowDecode
}