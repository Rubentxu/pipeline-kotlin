package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.CompensationArmed
import java.time.Instant

/**
 * The codec of the compensation obligation announcement.
 *
 * ## Why this is its own file and not a case inside [JsonEventLog]
 *
 * Two reasons, and the second is the one that matters. The first is that the sealed vocabulary
 * of events is not going to stop growing, and a decoder that accumulates one branch per event
 * is a decoder whose SIZE is the price of every new event. The second is the size itself:
 * [JsonEventLog] was already at the edge of what the architecture's own `LargeClass` rule
 * allows, and a branch that adds fifty lines to it turns every FUTURE event into a fight with
 * a limit that has nothing to do with the event.
 *
 * So the branch is here: the decoder stays a dispatcher, and the knowledge of how one event is
 * encoded lives next to the event's own vocabulary.
 *
 * ## Failing closed
 *
 * A field that cannot be read makes the whole event unreadable. A partially decoded obligation
 * would tell a reader that a promise exists while dropping the plan it was made under — which
 * is precisely the fact a reader needs to know whether the promise is the one they expect.
 */
object CompensationArmedCodec {

    /**
     * Decodes one [CompensationArmed] event, or `null` when any of its fields is unreadable.
     *
     * @param s the raw JSON of the event.
     * @param eventId the common event identity, already read by the caller.
     * @param runId the common run identity, already read by the caller.
     * @param sequence the common sequence, already read by the caller.
     * @param occurredAt the common timestamp, already read by the caller.
     */
    fun decode(
        s: String,
        eventId: String,
        runId: String,
        sequence: Long,
        occurredAt: Instant,
    ): CompensationArmed? {
        val sagaId = EventJsonFields.stringField(s, "sagaId") ?: return null
        val actionId = EventJsonFields.stringField(s, "actionId") ?: return null
        val compensationId = EventJsonFields.stringField(s, "compensationId") ?: return null
        val planFingerprint = EventJsonFields.stringField(s, "planFingerprint") ?: return null
        val intentVersion = EventJsonFields.intField(s, "intentVersion") ?: return null
        val resourceRef = EventJsonFields.stringField(s, "resourceRef") ?: return null
        val compensationOpId = EventJsonFields.stringField(s, "compensationOpId") ?: return null
        val leaseEpoch = EventJsonFields.longField(s, "leaseEpoch") ?: return null
        return CompensationArmed(
            eventId = eventId,
            runId = runId,
            sequence = sequence,
            occurredAt = occurredAt,
            sagaId = sagaId,
            actionId = actionId,
            compensationId = compensationId,
            planFingerprint = planFingerprint,
            intentVersion = intentVersion,
            resourceRef = resourceRef,
            compensationOpId = compensationOpId,
            leaseEpoch = leaseEpoch,
        )
    }
}
