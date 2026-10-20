package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.CompensationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.CompensationStarted
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import java.time.Instant

/**
 * The codecs of the compensation loop.
 *
 * ## Why these three travel together
 *
 * They are the three facts of ONE loop: a compensation started, a compensation finished, and
 * the Saga reached a terminal state. They share their identity (the Saga, the action, the
 * compensation) and their reading rules, so their codecs live together and the dispatcher in
 * [JsonEventLog] stays a dispatcher — the same reason [CompensationArmedCodec] is its own file.
 *
 * ## Failing closed
 *
 * A field that cannot be read makes the whole event unreadable, exactly as in
 * [CompensationArmedCodec]. A partially decoded compensation would tell a reader that a body ran
 * without saying WHICH one, and the slot is the only thing that joins the event to the journal
 * rows of the body that ran — so an event without it is worse than no event.
 */
object SagaLoopCodec {

    /**
     * Decodes one event of this family, by its kind.
     *
     * ## Why the family dispatches itself
     *
     * The dispatcher in [JsonEventLog] is a dispatcher, and a dispatcher that grows one branch
     * per event is a dispatcher whose SIZE is the price of every new event. The knowledge of
     * how these three are encoded lives here, so the caller needs ONE branch for the whole
     * family and this file owns the rest.
     */
    fun decode(
        kind: String,
        s: String,
        eventId: String,
        runId: String,
        sequence: Long,
        occurredAt: Instant,
    ): DomainEvent? = when (kind) {
        "CompensationStarted" -> decodeStarted(
            s = s,
            eventId = eventId,
            runId = runId,
            sequence = sequence,
            occurredAt = occurredAt,
        )

        "CompensationFinished" -> decodeFinished(
            s = s,
            eventId = eventId,
            runId = runId,
            sequence = sequence,
            occurredAt = occurredAt,
        )

        "SagaTerminal" -> decodeTerminal(
            s = s,
            eventId = eventId,
            runId = runId,
            sequence = sequence,
            occurredAt = occurredAt,
        )

        else -> null
    }

    /**
     * Decodes one [CompensationStarted] event, or `null` when any of its fields is unreadable.
     */
    fun decodeStarted(
        s: String,
        eventId: String,
        runId: String,
        sequence: Long,
        occurredAt: Instant,
    ): CompensationStarted? {
        val sagaId = EventJsonFields.stringField(s, "sagaId") ?: return null
        val actionId = EventJsonFields.stringField(s, "actionId") ?: return null
        val compensationId = EventJsonFields.stringField(s, "compensationId") ?: return null
        val resourceRef = EventJsonFields.stringField(s, "resourceRef") ?: return null
        val slot = EventJsonFields.stringField(s, "slot") ?: return null
        return CompensationStarted(
            eventId = eventId,
            runId = runId,
            sequence = sequence,
            occurredAt = occurredAt,
            sagaId = sagaId,
            actionId = actionId,
            compensationId = compensationId,
            resourceRef = resourceRef,
            slot = slot,
        )
    }

    /**
     * Decodes one [CompensationFinished] event, or `null` when any of its fields is unreadable.
     */
    fun decodeFinished(
        s: String,
        eventId: String,
        runId: String,
        sequence: Long,
        occurredAt: Instant,
    ): CompensationFinished? {
        val sagaId = EventJsonFields.stringField(s, "sagaId") ?: return null
        val actionId = EventJsonFields.stringField(s, "actionId") ?: return null
        val compensationId = EventJsonFields.stringField(s, "compensationId") ?: return null
        val slot = EventJsonFields.stringField(s, "slot") ?: return null
        val outcome = EventJsonFields.stringField(s, "outcome") ?: return null
        return CompensationFinished(
            eventId = eventId,
            runId = runId,
            sequence = sequence,
            occurredAt = occurredAt,
            sagaId = sagaId,
            actionId = actionId,
            compensationId = compensationId,
            slot = slot,
            outcome = outcome,
            // The detail is optional on purpose: a confirmation has nothing to add, and the
            // empty string is how this wire format says "absent".
            detail = EventJsonFields.optionalStringField(s, "detail"),
        )
    }

    /**
     * Decodes one [SagaTerminal] event, or `null` when any of its fields is unreadable.
     */
    fun decodeTerminal(
        s: String,
        eventId: String,
        runId: String,
        sequence: Long,
        occurredAt: Instant,
    ): SagaTerminal? {
        val sagaId = EventJsonFields.stringField(s, "sagaId") ?: return null
        val state = EventJsonFields.stringField(s, "state") ?: return null
        val compensatedActions = EventJsonFields.stringListField(s, "compensatedActions") ?: return null
        return SagaTerminal(
            eventId = eventId,
            runId = runId,
            sequence = sequence,
            occurredAt = occurredAt,
            sagaId = sagaId,
            state = state,
            reason = EventJsonFields.optionalStringField(s, "reason"),
            compensatedActions = compensatedActions,
        )
    }
}
