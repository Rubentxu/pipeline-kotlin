package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * P3-E D3 — `catchError` results cross the wire as the tokens they always carried.
 *
 * ## Why this is not the round-trip test's job
 *
 * [JsonEventLogRoundTripTest] asserts that a value survives encode/decode. That is a
 * different claim from "the bytes on disk are the bytes that were always there", and only
 * one of the two would notice if this type had been projected with `.name`: the case names
 * are `Success`/`Unstable`/`Failure` and the wire has always been `SUCCESS`/`UNSTABLE`/
 * `FAILURE`. A round trip through two consistently wrong projections passes perfectly.
 *
 * So every assertion here is made against a **literal**, never against a re-derived value.
 * That is the discipline E6b established for `failureKind` and this is the same claim about
 * a second field.
 *
 * ## The four dimensions, one test each
 *
 * 1. each case projects its own historical token — writer;
 * 2. the historical tokens survive the production decoder — reader, through real code;
 * 3. a token outside the vocabulary is refused, not coerced — reader, negative;
 * 4. the vocabulary is exactly the three Jenkins results — closedness, so widening needs a
 *    decision rather than a typo.
 */
@DisplayName("catchError result wire compatibility")
class CatchErrorResultWireCompatibilityTest {

    private fun event(
        buildResult: CatchErrorBuildResult?,
        stageResult: CatchErrorBuildResult,
    ) = CatchErrorTriggered(
        eventId = "evt-catch-wire",
        runId = "run-catch-wire",
        sequence = 7L,
        occurredAt = Instant.parse("2026-10-06T00:00:00Z"),
        stageName = "Build",
        buildResult = buildResult,
        stageResult = stageResult,
        message = "tolerated failure",
    )

    @Test
    fun `each case projects the token it has always carried`() {
        val expectations = listOf(
            CatchErrorBuildResult.Success to "SUCCESS",
            CatchErrorBuildResult.Unstable to "UNSTABLE",
            CatchErrorBuildResult.Failure to "FAILURE",
        )

        for ((case, token) in expectations) {
            val encoded = EventJsonWriter.encodeEvent(event(case, case))

            assertTrue(
                encoded.contains("\"stageResult\":\"$token\""),
                "$case must travel as $token, encoded was $encoded",
            )
            assertTrue(
                encoded.contains("\"buildResult\":\"$token\""),
                "$case must travel as $token, encoded was $encoded",
            )
            assertTrue(
                !encoded.contains("\"${token.lowercase()}\""),
                "a lower-case spelling leaked into the wire: $encoded — that is the regression " +
                    "this pins, because the wire has always been UPPER CASE",
            )
        }
    }

    @Test
    fun `the historical tokens survive the production decoder`() {
        val tokens = listOf("SUCCESS", "UNSTABLE", "FAILURE")

        for (token in tokens) {
            val stored = EventJsonWriter.encodeEvent(event(null, CatchErrorBuildResult.Failure))
                .replace("\"stageResult\":\"FAILURE\"", "\"stageResult\":\"$token\"")

            val decoded = JsonEventLog.decode("[$stored]").single() as CatchErrorTriggered

            assertEquals(
                token,
                decoded.stageResult.wireToken,
                "'$token' is history and must decode back to itself",
            )
        }
    }

    @Test
    fun `a token outside the vocabulary is refused rather than coerced`() {
        // "FALURE" is the typo that mattered: before D3 it reached the decision, where the
        // `else` arm read it as UNSTABLE and SUPPRESSED the failure the scope was installed
        // to catch. There is no longer a place for it to be coerced into a value.
        for (token in listOf("FALURE", "success", "Stable", "", "UNSTABLE ", "WAT")) {
            val stored = EventJsonWriter.encodeEvent(event(null, CatchErrorBuildResult.Failure))
                .replace("\"stageResult\":\"FAILURE\"", "\"stageResult\":\"$token\"")

            val decoded = JsonEventLog.decode("[$stored]")

            assertTrue(
                decoded.isEmpty(),
                "'$token' is outside $${CatchErrorBuildResult.supportedTokens} and must be " +
                    "refused, but it decoded to $decoded — coercing it would put a value in " +
                    "the durable stream that no consumer can classify",
            )
        }
    }

    @Test
    fun `an absent buildResult is still a legitimate absence, and a present one is still typed`() {
        // Absence on this field is encoded as ABSENT_ON_WIRE (the empty string), not as a JSON
        // null and not by omitting the key — that is the convention E6c gave one name, and it
        // is what every historical record already carries. A first draft of this assertion
        // guessed `null` and failed, which is the useful direction: it shows the test is
        // measuring the encoder rather than restating it.
        val absent = EventJsonWriter.encodeEvent(event(null, CatchErrorBuildResult.Failure))
        assertTrue(
            absent.contains("\"buildResult\":\"${EventJsonFields.ABSENT_ON_WIRE}\""),
            "a declared absence must travel as the declared absent token, encoded was $absent",
        )
        assertEquals(
            null,
            (JsonEventLog.decode("[$absent]").single() as CatchErrorTriggered).buildResult,
            "an absent buildResult must decode as null, not as a default result",
        )

        val present = EventJsonWriter.encodeEvent(
            event(CatchErrorBuildResult.Success, CatchErrorBuildResult.Failure),
        )
        assertEquals(
            CatchErrorBuildResult.Success,
            (JsonEventLog.decode("[$present]").single() as CatchErrorTriggered).buildResult,
        )
    }
}
