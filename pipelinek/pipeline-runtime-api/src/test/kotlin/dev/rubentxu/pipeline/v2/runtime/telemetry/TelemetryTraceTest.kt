package dev.rubentxu.pipeline.v2.runtime.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The trace id of a finished run is the key a consumer joins its own signals
 * with, and it is frozen.
 *
 * ## The guarantee
 *
 * The historical trace id is a digest of the run id, and the digest is the
 * same one for everybody who derives it: the product writes it into the
 * exported document, and a consumer that holds the journal derives it again
 * and correlates the two. That only works while the derivation is the same
 * derivation, so the value for a known run is frozen here: a change to the
 * derivation moves every historical trace id, and a consumer's stored joins
 * stop matching without anybody noticing.
 *
 * The three properties the join depends on are checked as well: the value is
 * deterministic, it is a different one for every run, and it has the shape of
 * a W3C trace id — which is the same shape as the live trace id, and that is
 * exactly why the two may not be confused.
 */
class TelemetryTraceTest {

    @Test
    fun `the trace id of a run is the digest everybody derives and a different one for every run`() {
        assertEquals(
            "0c8b86396d5a700fe123893965eb198e",
            TelemetryTrace.traceIdOf("run-1"),
            "the trace id of a run is the key a consumer joins its own signals with, and the " +
                "derivation is the same one for the writer and for the reader: a change here " +
                "moves every historical trace id and breaks the joins that were already stored",
        )

        assertEquals(
            TelemetryTrace.traceIdOf("run-1"),
            TelemetryTrace.traceIdOf("run-1"),
            "the same run has one trace id: a consumer that derives it twice has to derive the " +
                "same value",
        )

        assertNotEquals(
            TelemetryTrace.traceIdOf("run-1"),
            TelemetryTrace.traceIdOf("run-2"),
            "two runs are two traces: a trace id shared by two runs groups facts that do not " +
                "belong together",
        )

        assertTrue(
            TRACE_ID.matches(TelemetryTrace.traceIdOf("run-1")),
            "the historical trace id has the shape of a W3C trace id, because that is the shape " +
                "of the field it travels in",
        )
    }

    private companion object {
        /** The shape of a W3C trace id: 32 lowercase hexadecimal characters. */
        val TRACE_ID = Regex("^[0-9a-f]{32}$")
    }
}
