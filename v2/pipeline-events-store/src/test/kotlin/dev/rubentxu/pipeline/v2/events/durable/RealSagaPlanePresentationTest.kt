package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.SagaOutcome
import dev.rubentxu.pipeline.v2.events.identity.RunPresentationProjection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * The published verdict, read from a REAL run's plane.
 *
 * ## Why this test is here and not in `:pipeline-events`
 *
 * Because the part that can lose information is not the projection but the DECODER. The wire
 * format writes an absent optional string as the empty string (the encoding every historical
 * record already uses), and a decoder that forgot to map "" back to `null` would hand the
 * projection a `SETTLED` Saga carrying a reason — a contradiction, and the reader would
 * answer "a human is needed" for a Saga that closed.
 *
 * The decoder lives in this module, so this is where the two halves meet: the real bytes, the
 * real decoder, and the published projection that a consumer of the SDK would call.
 *
 * ## Where the bytes come from
 *
 * They are the captured plane of a REAL run — the saga scenario whose provider cannot promise
 * that a failed call left nothing behind — read out of the durable store of that run. Nothing
 * here is written by hand: the file is what the engine wrote.
 */
class RealSagaPlanePresentationTest {

    @Test
    fun `the real plane says the run failed and a human is needed`() {
        val events = JsonEventLog.decode(plane(NEEDS_INTERVENTION_PLANE))

        val presentation = RunPresentationProjection.read(RUN_ID, events)

        // The run's own outcome, with the failure of the STEP OF THE RUN — not the failure of
        // the body nested inside it, which the plane also carries.
        val failure = assertInstanceOf<RunOutcome.Failure>(presentation.outcome)
        assertEquals(FailureKind.PLUGIN, failure.failure.kind)
        assertEquals("the action ship failed: shell exited with code 1", failure.failure.message)

        // What the Saga left behind, named and with the reason the engine gave.
        assertEquals(
            listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.UNKNOWN_EFFECT)),
            presentation.sagas,
        )
        assertTrue(presentation.needsIntervention)

        // A run that needs a human is NOT a finished run, however it ended.
        assertFalse(presentation.isFinished)
    }

    @Test
    fun `a settled Saga in a real plane is read as settled, not as a contradiction`() {
        // This is the case the wire format decides. The engine writes an absent optional
        // string as the empty string, so the captured plane carries `"reason": ""` for a Saga
        // that closed. A decoder that did not map "" back to `null` would hand the projection
        // a settled Saga WITH a reason — a contradiction — and the reader would ask a human
        // about a Saga that nobody has to touch.
        val events = JsonEventLog.decode(plane(RECONCILED_PLANE))

        val presentation = RunPresentationProjection.read(RECONCILED_RUN_ID, events)

        assertEquals(listOf(SagaOutcome.Settled("checkout")), presentation.sagas)
        assertFalse(presentation.needsIntervention)

        // The run still failed: the Saga recovered, and the failure is still a failure.
        val failure = assertInstanceOf<RunOutcome.Failure>(presentation.outcome)
        assertEquals("the write-ahead for reserve was refused", failure.failure.message)

        // Nothing is pending, so the run's story is over — even though it failed.
        assertTrue(presentation.isFinished)
    }

    private fun plane(name: String): String =
        checkNotNull(javaClass.getResource("/$name")) {
            "the captured plane '$name' is missing from the test resources"
        }.readText()

    private companion object {
        /** The run id of the captured plane that needed a human. */
        const val RUN_ID = "3e31ccb4-6c98-4e6d-a15e-e2df04779733"

        /** The run id of the captured plane whose Saga was reconciled. */
        const val RECONCILED_RUN_ID = "20b5ca3c-d2e3-4f2f-be33-4ccfcdeba3d9"

        const val NEEDS_INTERVENTION_PLANE = "real-saga-plane-needs-intervention.json"
        const val RECONCILED_PLANE = "real-saga-plane-reconciled.json"
    }
}
