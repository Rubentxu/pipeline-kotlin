package dev.rubentxu.pipeline.v2.runtime.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The seam a plugin observes its own work through.
 *
 * ## THE GUARANTEE
 *
 * A plugin can say WHAT it is doing — a bounded name — and HOW it ended — a
 * closed status — and can say nothing else. No command, no path, no address
 * and no fragment of output can be spelled into this API, and a refusal
 * never repeats the value it refused.
 *
 * ## THE CAPACITY IT SERVES
 *
 * The reader of a trace has to be able to trust that a span named after a
 * plugin's work describes that work and carries nothing else. The moment a
 * plugin can put an arbitrary string into that name, the name stops being a
 * label and becomes a channel, and everything downstream — the series a
 * collector builds, the label an operator reads, the log a support engineer
 * pastes into a ticket — inherits whatever the plugin put there.
 *
 * ## WHY THE NAME IS A VALUE AND NOT A STRING
 *
 * The refusal is a property of the TYPE, so it cannot be forgotten at a call
 * site: a caller that holds an [ObservationName] already holds something
 * this API agreed to publish. A string parameter would push the same rule
 * onto every plugin, and one plugin would get it wrong.
 *
 * ## WHY THE REFUSAL IS QUIET ABOUT THE VALUE
 *
 * A refusal that repeats the offending value turns the telemetry API into
 * the place a secret is written down twice: once in the call and once in the
 * error message. The message therefore says what shape was expected and
 * never what was received.
 */
class StepObservabilityTest {

    @Test
    fun `a dotted lowercase name is accepted`() {
        val name = ObservationName.of("uppercase.transform")

        assertEquals("uppercase.transform", name.value)
    }

    @Test
    fun `a name that is not dotted lowercase words is refused`() {
        val refused = listOf(
            "rm -rf /",
            "/etc/passwd",
            "https://example.test/x",
            "hello world",
            "Uppercase",
            "uppercase_transform",
            "uppercase..transform",
            ".uppercase",
            "1uppercase",
        )

        refused.forEach { raw ->
            assertNull(ObservationName.ofOrNull(raw), "'$raw' must not be a name")
            val failure = runCatching { ObservationName.of(raw) }.exceptionOrNull()
            assertTrue(
                failure is IllegalArgumentException,
                "'$raw' must be refused with an argument failure",
            )
        }
    }

    @Test
    fun `an empty name is refused`() {
        assertNull(ObservationName.ofOrNull(""))
    }

    @Test
    fun `a name longer than the published ceiling is refused`() {
        val tooLong = "a".repeat(ObservationName.MAX_LENGTH + 1)

        assertNull(ObservationName.ofOrNull(tooLong))

        val atCeiling = "a".repeat(ObservationName.MAX_LENGTH)
        assertEquals(atCeiling, ObservationName.of(atCeiling).value)
    }

    @Test
    fun `a refusal does not repeat the value it refused`() {
        val secret = "token-abc123"

        val failure = runCatching { ObservationName.of(secret) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "the value must be refused")
        val message = failure?.message.orEmpty()
        assertFalse(
            message.contains(secret),
            "the refusal must not echo the value back",
        )
        assertFalse(
            message.contains("abc123"),
            "the refusal must not echo any part of the value back",
        )
    }

    @Test
    fun `the seam that observes nothing says so`() {
        assertFalse(NoopStepObservability.enabled, "a disabled seam must say so")
        assertSame(
            NoopStepSpan,
            NoopStepObservability.openSpan(ObservationName.of("noop.work")),
            "a disabled seam opens no span",
        )
        NoopStepObservability.count(ObservationName.of("noop.work"), ObservationStatus.Succeeded)
    }

    @Test
    fun `a counted observation carries the name and the status and nothing else`() {
        val seam = RecordingObservability()

        seam.count(ObservationName.of("uppercase.transform"), ObservationStatus.Failed)

        assertEquals(
            listOf(ObservationName.of("uppercase.transform") to ObservationStatus.Failed),
            seam.counted,
        )
    }

    @Test
    fun `an observation that returns is ended as succeeded`() {
        val seam = RecordingObservability()

        val value = seam.inSpan(ObservationName.of("uppercase.transform")) { "done" }

        assertEquals("done", value)
        assertEquals(
            listOf(ObservationName.of("uppercase.transform") to ObservationStatus.Succeeded),
            seam.ended,
        )
        assertTrue(seam.discarded.isEmpty(), "a finished observation is not discarded")
    }

    @Test
    fun `an observation that fails is ended as failed and the failure travels`() {
        val seam = RecordingObservability()

        val failure = runCatching {
            seam.inSpan<Unit>(ObservationName.of("uppercase.transform")) { error("the work failed") }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "the failure must travel unchanged")
        assertEquals(
            listOf(ObservationName.of("uppercase.transform") to ObservationStatus.Failed),
            seam.ended,
        )
    }

    /**
     * A seam that remembers what it was told.
     *
     * The seam is the published contract and nothing else, so a recording of
     * it needs no library: the point of the rows above is that the contract
     * alone is enough to observe the behaviour.
     */
    private class RecordingObservability : StepObservability {
        override val enabled: Boolean = true

        val ended: MutableList<Pair<ObservationName, ObservationStatus>> = mutableListOf()

        val discarded: MutableList<ObservationName> = mutableListOf()

        val counted: MutableList<Pair<ObservationName, ObservationStatus>> = mutableListOf()

        override fun openSpan(name: ObservationName): StepSpan = object : StepSpan {
            override fun end(status: ObservationStatus) {
                ended += name to status
            }

            override fun discard() {
                discarded += name
            }
        }

        override fun count(name: ObservationName, status: ObservationStatus) {
            counted += name to status
        }
    }
}
