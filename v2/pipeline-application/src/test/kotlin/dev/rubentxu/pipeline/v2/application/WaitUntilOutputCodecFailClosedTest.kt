package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * F-B1.2-001: `core.waitUntil`'s output codec fails closed on a terminal it does not recognise.
 *
 * ## The defect, stated as an observable
 *
 * `WaitUntilOutput.resultOutcome` is a `String` and the step outcome is reconstructed from it by
 * matching one literal:
 *
 * ```kotlin
 * override val outcome: StepOutcome
 *     get() = if (resultOutcome == "completed") StepOutcome.Success else StepOutcome.Failure(…)
 * ```
 *
 * A `String` cannot fail closed. `"Completed"`, `"completed "`, a terminal a future version adds
 * and a corrupted journal all take the `else` branch and become `Failure(TIMEOUT)` — a deadline
 * the run never reached, announced with a message about a wait it never exceeded. The run then
 * ends as a timeout that never happened, which is the information loss B1.2 exists to remove.
 *
 * The three contract tests that already round-trip this value cannot see that, because they only
 * ever construct the value the encoder produces. Every one of them agrees with the code, and the
 * suite is green on a type that makes the defect unreachable from inside the type.
 *
 * ## Harness fidelity
 *
 * HF0 (pure contract). This crosses the production `StepCodec` seam of
 * `CoreWaitUntilStep.definition` — the real decoder — not a reimplementation of it. No clock, no
 * filesystem, no process, no shared mutable state.
 *
 * RED: the decoder accepts `"Completed"` and returns a `WaitUntilOutput` whose outcome is a
 * `Failure` about a deadline. Asserted through the production codec, so there is no way to pass
 * this by changing the test.
 * GREEN: the decoder throws, naming the value it refused.
 */
@DisplayName("F-B1.2-001 · the waitUntil output codec fails closed on an unknown terminal")
class WaitUntilOutputCodecFailClosedTest {

    private val codec = CoreWaitUntilStep.definition.contract.outputCodec

    /** Every token the domain froze. A decoder that rejects one of these is broken, not strict. */
    private val frozenTokens = listOf("completed", "deadline-exceeded", "aborted")

    @Test
    fun `NON-VACUITY · the codec really is the production decoder for this Step`() {
        // A strictness test on the wrong codec is a test of nothing. This row fails if the Step is
        // ever re-registered behind a different codec, rather than proving the decoder is strict.
        assertTrue(
            codec.javaClass != dev.rubentxu.pipeline.v2.domain.step.StepCodec::class.java,
            "NON-VACUITY: the output codec is the StepCodec interface itself, so no decoder is being exercised.",
        )
    }

    @Test
    fun `the three frozen terminals still decode, so strictness did not come from rejecting the real ones`() {
        frozenTokens.forEach { token ->
            val payload = """{"kind":"waitUntil","outcome":"$token","totalAttempts":1,"totalDurationMs":0}"""

            val decoded = codec.decode(EncodedStepValue(payload))

            assertEquals(
                token,
                decoded.resultOutcome,
                "The frozen terminal '$token' must keep decoding. A decoder that refuses the real " +
                    "values is broken, not strict.",
            )
        }
    }

    @Test
    fun `an unknown terminal is rejected by name instead of becoming a failure`() {
        listOf("Completed", "COMPLETED", "completed ", "timed-out", "cancelled", "").forEach { token ->
            val payload = """{"kind":"waitUntil","outcome":"$token","totalAttempts":1,"totalDurationMs":0}"""

            val failure = assertThrows(
                IllegalArgumentException::class.java,
                { codec.decode(EncodedStepValue(payload)) },
                "The codec accepted the unknown terminal '$token'. Every value outside the frozen " +
                    "set must be refused, because the `else` branch turns it into a Failure that " +
                    "reports a deadline the run never hit.",
            )

            assertTrue(
                failure.message.orEmpty().contains(token.ifEmpty { "" } ),
                "The rejection must name the value it refused, so a corrupt journal is diagnosable " +
                    "instead of looking like a timeout. Token was '$token', message was: ${failure.message}",
            )
        }
    }
}
