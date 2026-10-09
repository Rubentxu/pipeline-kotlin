package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilAbortCause
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilCompletion
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * F-B1.2-001: `core.waitUntil`'s typed output carries a closed ADT, and the wire token is a
 * projection of it that nothing decodes back into a decision.
 *
 * ## What this holds, and why the pre-existing tests could not
 *
 * Before this slice `WaitUntilOutput.resultOutcome` was a `String`, and the step outcome was
 * reconstructed from it by matching one literal:
 *
 * ```kotlin
 * override val outcome: StepOutcome
 *     get() = if (resultOutcome == "completed") StepOutcome.Success else StepOutcome.Failure(…)
 * ```
 *
 * Three contract tests round-tripped that value and every one of them agreed with the code, so
 * the suite was green **on a type that made the third outcome unreachable**. `WaitUntilCompletion`
 * already existed in `pipeline-domain` with exactly the three terminals the domain has, and
 * `WaitUntilEngine` already projected all five of its emission sites from it — the non-durable
 * path was the single remaining string authority, and the pinned read in
 * `FArchE4b4WaitUntilTerminalAuthorityTest` was the shape of the debt.
 *
 * A `String` cannot fail closed. `"Completed"`, `"completed "`, a future terminal and a corrupted
 * journal all take the `else` branch and become `Failure(TIMEOUT)` — a terminal the run never
 * reached, announced with a message about a deadline it never hit. The run then ends as a timeout
 * that never happened, which is precisely the information loss B1.2 exists to remove.
 *
 * So the claim here is not "the value is a String that happens to be right". It is:
 *
 * 1. the typed output carries [WaitUntilCompletion], so no fourth terminal can be spelled;
 * 2. the wire `outcome` field stays **byte-identical** to the three frozen tokens, because S8
 *    freezes event schemas and this payload is a published scripting surface;
 * 3. a wire value outside the frozen set is **rejected by the codec**, not coerced into a failure.
 *
 * ## Harness fidelity
 *
 * HF0 (pure contract) for rows 1-3: this crosses the production `StepCodec` seam of
 * `CoreWaitUntilStep.definition`, not a reimplementation of it. Rows 1-2 construct the real type
 * and call the real codec. There is no clock, no filesystem and no process.
 *
 * RED: the round-trip and the byte-identity rows fail while `resultOutcome` is a `String`; the
 * rejection row fails because the old decoder accepted every value.
 * GREEN: all rows pass, and the mutation below is what keeps them honest.
 */
@DisplayName("F-B1.2-001 · the waitUntil typed terminal is a closed ADT")
class WaitUntilTerminalAdtTest {

    private val codec = CoreWaitUntilStep.definition.contract.outputCodec

    @Test
    fun `the typed output carries the ADT, not a token a caller can rewrite`() {
        val satisfied = WaitUntilOutput(
            completion = WaitUntilCompletion.Satisfied,
            totalAttempts = 3,
            totalDurationMs = 1500L,
        )

        assertEquals(WaitUntilCompletion.Satisfied, satisfied.completion)
        // The projection is derived, never stored: there is no field that a caller could set to a
        // token inconsistent with the terminal it claims to report.
        assertEquals("completed", satisfied.resultOutcome)
    }

    @Test
    fun `each terminal projects the frozen wire token and its own step outcome`() {
        val terminals = listOf(
            WaitUntilCompletion.Satisfied to ("completed" to true),
            WaitUntilCompletion.DeadlineExceeded(attempt = 2, ceilingMs = 5000L) to ("deadline-exceeded" to false),
            WaitUntilCompletion.Aborted(WaitUntilAbortCause.DurableRowAlreadyAborted) to ("aborted" to false),
        )

        terminals.forEach { (completion, expected) ->
            val output = WaitUntilOutput(completion, totalAttempts = 2, totalDurationMs = 10L)
            val (token, isSuccess) = expected

            assertEquals(token, output.resultOutcome, "wire token must not move for ${completion::class.simpleName}")
            assertEquals(
                isSuccess,
                output.outcome is dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
                "StepOutcome must be projected from the terminal, not matched from a token",
            )
        }
    }

    @Test
    fun `the wire payload keeps the historical outcome field byte-identical`() {
        // S8 freezes the schema. A reader of an older journal must decode this unchanged, so the
        // encoded JSON has to carry exactly the same key and the same three token values.
        val encoded = codec.encode(
            WaitUntilOutput(WaitUntilCompletion.Satisfied, totalAttempts = 1, totalDurationMs = 0L),
        ).value

        assertTrue(
            encoded.contains("\"outcome\":\"completed\""),
            "The encoded output must keep the historical `outcome` field and its frozen value; was $encoded",
        )
        assertTrue(
            encoded.contains("\"kind\":\"waitUntil\""),
            "The encoded output must keep the historical envelope kind; was $encoded",
        )
    }

    @Test
    fun `an outcome outside the frozen set is rejected by the codec, not coerced into a failure`() {
        val corrupt = """{"kind":"waitUntil","outcome":"Completed","totalAttempts":1,"totalDurationMs":0}"""

        val failure = assertThrows(IllegalArgumentException::class.java) {
            codec.decode(EncodedStepValue(corrupt))
        }

        assertTrue(
            failure.message.orEmpty().contains("Completed"),
            "The rejection must name the value it refused so a corrupt journal is diagnosable; " +
                "was: ${failure.message}",
        )
    }

    @Test
    fun `a fourth terminal cannot be spelled, and the frozen token set is held`() {
        // The rejection row above proves the decoder fails closed. This row proves the *set* is
        // the one the domain declared, so adding a terminal is a visible change here and not a
        // literal that quietly appears in a codec.
        assertEquals(
            setOf("completed", "deadline-exceeded", "aborted"),
            dev.rubentxu.pipeline.v2.domain.durable.WaitUntilCompletionWireOutcomes,
            "The frozen wire token set moved. S8 freezes event schemas against exactly these three.",
        )
    }
}
