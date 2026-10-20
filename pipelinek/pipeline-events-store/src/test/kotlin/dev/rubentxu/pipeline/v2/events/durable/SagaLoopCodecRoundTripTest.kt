package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.CompensationFinished
import dev.rubentxu.pipeline.v2.events.CompensationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The three facts of the compensation loop, on real bytes.
 *
 * ## Why this test exists
 *
 * The writer and the decoder are two halves of ONE wire format, and a field added to one side
 * only would compile, run, and silently decode wrong. The `Detail` of a compensation is the
 * field most likely to drift: it is absent on a confirmation and present on a failure, and the
 * empty string is how this format says "absent".
 *
 * So the two sides are pinned to each other here, on the events the loop announces: what goes
 * out is what comes back, field for field.
 */
class SagaLoopCodecRoundTripTest {

    @Test
    fun `a compensation that started survives the round trip`() {
        assertRoundTrip(
            CompensationStarted(
                eventId = "e-1",
                runId = "run-42",
                sequence = 3L,
                occurredAt = AT,
                sagaId = "checkout",
                actionId = "charge",
                compensationId = "refund",
                resourceRef = "resource/charge",
                slot = "compensate-charge",
            ),
        )
    }

    @Test
    fun `a confirmed compensation survives the round trip without a detail`() {
        assertRoundTrip(
            CompensationFinished(
                eventId = "e-2",
                runId = "run-42",
                sequence = 4L,
                occurredAt = AT,
                sagaId = "checkout",
                actionId = "charge",
                compensationId = "refund",
                slot = "compensate-charge",
                outcome = "CONFIRMED",
                detail = null,
            ),
        )
    }

    @Test
    fun `a failed compensation survives the round trip with its detail`() {
        assertRoundTrip(
            CompensationFinished(
                eventId = "e-3",
                runId = "run-42",
                sequence = 5L,
                occurredAt = AT,
                sagaId = "checkout",
                actionId = "charge",
                compensationId = "refund",
                slot = "compensate-charge",
                outcome = "FAILED",
                detail = "the provider refused the refund",
            ),
        )
    }

    @Test
    fun `the terminal state of a Saga survives the round trip`() {
        assertRoundTrip(
            SagaTerminal(
                eventId = "e-4",
                runId = "run-42",
                sequence = 6L,
                occurredAt = AT,
                sagaId = "checkout",
                state = "NEEDS_INTERVENTION",
                reason = "COMPENSATION_NOT_DISPATCHED",
                compensatedActions = listOf("charge", "reserve"),
            ),
        )
    }

    @Test
    fun `a Saga that settled without compensating anything survives the round trip`() {
        assertRoundTrip(
            SagaTerminal(
                eventId = "e-5",
                runId = "run-42",
                sequence = 7L,
                occurredAt = AT,
                sagaId = "checkout",
                state = "SETTLED",
                reason = null,
                compensatedActions = emptyList(),
            ),
        )
    }

    /** What goes out is what comes back. */
    private fun assertRoundTrip(event: DomainEvent) {
        val encoded = JsonEventLog.encodeOne(event)
        assertEquals(listOf(event), JsonEventLog.decode("[$encoded]"))
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
