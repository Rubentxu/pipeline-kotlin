package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.events.SagaTerminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the run's verdict says about its Sagas.
 *
 * The claim under test is a NEGATIVE one, and that is the whole point: before this, a Saga
 * that needed a human and a Saga that was fully compensated produced the same stderr. The
 * verdict is what tells them apart, so the test that matters is the one that proves the
 * two no longer read alike.
 */
class SagaVerdictTest {

    private fun terminal(
        sagaId: String = "checkout",
        state: String = "SETTLED",
        reason: String? = null,
    ) = SagaTerminal(
        eventId = "event-1",
        runId = "run-1",
        sequence = 1L,
        occurredAt = Instant.parse("2026-10-19T00:00:00Z"),
        sagaId = sagaId,
        state = state,
        reason = reason,
        compensatedActions = emptyList(),
    )

    @Test
    fun `a settled saga says so`() {
        assertEquals(
            listOf("  saga [checkout]: SETTLED"),
            SagaVerdict.verdicts(listOf(terminal())),
        )
    }

    @Test
    fun `a saga that needs a human says why`() {
        assertEquals(
            listOf("  saga [checkout]: NEEDS_INTERVENTION (UNKNOWN_EFFECT)"),
            SagaVerdict.verdicts(listOf(terminal(state = "NEEDS_INTERVENTION", reason = "UNKNOWN_EFFECT"))),
        )
    }

    /**
     * The distinction the verdict exists to draw: two runs that both failed, one whose Saga
     * closed and one that left a human a job. They must not read alike.
     */
    @Test
    fun `a settled saga and an unsettled one do not read alike`() {
        val settled = SagaVerdict.verdicts(listOf(terminal()))
        val unsettled = SagaVerdict.verdicts(
            listOf(terminal(state = "NEEDS_INTERVENTION", reason = "COMPENSATION_UNCERTAIN")),
        )

        assertEquals(1, settled.size)
        assertEquals(1, unsettled.size)
        org.junit.jupiter.api.Assertions.assertNotEquals(settled, unsettled)
    }

    @Test
    fun `each saga of the run gets its own line`() {
        assertEquals(
            listOf(
                "  saga [checkout]: SETTLED",
                "  saga [inventory]: NEEDS_INTERVENTION (UNKNOWN_EFFECT)",
            ),
            SagaVerdict.verdicts(
                listOf(
                    terminal(),
                    terminal(sagaId = "inventory", state = "NEEDS_INTERVENTION", reason = "UNKNOWN_EFFECT"),
                ),
            ),
        )
    }

    @Test
    fun `a run without sagas says nothing`() {
        assertEquals(emptyList<String>(), SagaVerdict.verdicts(emptyList()))
    }
}
