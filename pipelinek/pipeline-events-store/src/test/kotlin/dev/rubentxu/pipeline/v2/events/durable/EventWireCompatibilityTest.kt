package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.WsCleaned
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The durable wire, read from rows that a previous version wrote, and refused where it has to be.
 *
 * ## What these rows are
 *
 * The payloads below are written BY HAND, in the wire format the product has always used, and
 * they are not produced by the codec under test. A codec that only understood what it writes
 * itself would pass every test that compares it with itself and would still lose the history
 * it is meant to keep.
 *
 * ## What they hold
 *
 *  - a row written before a field existed, which does not carry it;
 *  - a row that names a field inside another field's VALUE, which used to move the reading of
 *    the named field to the wrong place;
 *  - a row whose list holds a bracket, which used to truncate the list at the bracket;
 *  - a row with a mandatory field missing, which is refused instead of being read with an
 *    invented value.
 *
 * Pure and total: no database, no clock, no filesystem.
 */
class EventWireCompatibilityTest {

    /**
     * A row of the previous version, read by this one.
     *
     * The row is a `StepFinished` whose `outcome` was not declared: the field is absent, and
     * the row still says what happened to the step.
     */
    @Test
    fun `a row written before the verdict was published reads as a step with no verdict`() {
        val row = """
            {"eventId":"e-1","runId":"run-42","sequence":7,"kind":"StepFinished",
             "occurredAt":"2026-01-01T00:00:00Z","stageIndex":1,"stepIndex":2,
             "stepName":"build/sh-2","stepType":"sh"}
        """.trimIndent().replace("\n", "")

        val event = JsonEventLog.decode("[$row]").single() as StepFinished

        assertEquals("StepFinished", event.kind)
        assertNull(event.outcome, "a row without the verdict must not carry one")
        assertEquals(1, event.stageIndex)
    }

    /**
     * A row whose index was not declared travels as `-1`, and comes back as "not declared".
     *
     * The convention exists so a reader can tell "no stage" from "stage 0": a failure in the
     * first stage is a different fact from a failure that does not name its stage.
     */
    @Test
    fun `a row whose index was not declared reads as an event that names no index`() {
        val row = """
            {"eventId":"e-1","runId":"run-42","sequence":7,"kind":"StepFailed",
             "occurredAt":"2026-01-01T00:00:00Z","stageIndex":-1,"stepIndex":2,
             "stepName":"build/sh-2","stepType":"sh","failureKind":"SCRIPT",
             "message":"exit 1"}
        """.trimIndent().replace("\n", "")

        val event = JsonEventLog.decode("[$row]").single() as StepFailed

        assertNull(event.stageIndex, "an index of -1 means the event names no index")
        assertEquals(2, event.stepIndex)
    }

    /**
     * A classification this build does not know is read as "not classified", and the row is kept.
     *
     * The row's own KIND is what says the row is from a future this runtime cannot read, and that
     * is refused as version skew. A word INSIDE a row of a kind this runtime knows is a different
     * matter: the row says what happened, and refusing it would lose the run it belongs to over a
     * word the failure vocabulary already has a name for.
     */
    @Test
    fun `a failure kind this build does not know reads as unclassified`() {
        val row = """
            {"eventId":"e-1","runId":"run-42","sequence":7,"kind":"StepFailed",
             "occurredAt":"2026-01-01T00:00:00Z","stageIndex":1,"stepIndex":2,
             "stepName":"build/sh-2","stepType":"sh","failureKind":"FROM_THE_FUTURE",
             "message":"exit 1"}
        """.trimIndent().replace("\n", "")

        val event = JsonEventLog.decode("[$row]").single() as StepFailed

        assertEquals(
            FailureKind.UNKNOWN,
            event.failureKind,
            "a word nobody can classify must not be read as a classification this build knows",
        )
        assertEquals("exit 1", event.message, "the row is kept, not refused")
    }

    /**
     * A value that names another field does not move that field's reading.
     *
     * The old reader located a field by searching the TEXT for its name, so a value that
     * happened to contain another field's name was read as that field. A step whose name
     * quotes the word `outcome` was read as a step whose verdict was that quote.
     */
    @Test
    fun `a value that names another field does not move that field's reading`() {
        val event = StepFinished(
            eventId = "e-1",
            runId = "run-42",
            sequence = 7L,
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            stageIndex = 1,
            stepIndex = 2,
            stepName = """the step printed "outcome" in its log""",
            stepType = "sh",
            outcome = "failure",
        )

        val encoded = JsonEventLog.encodeOne(event)

        assertEquals(event, JsonEventLog.decode("[$encoded]").single())
    }

    /**
     * A value that holds what has to be escaped comes back as it was.
     *
     * The retired writer escaped the quotes, the backslashes and the newlines of a value, and it
     * did it by hand. The writer that replaced it delegates the escaping to the JSON writer of the
     * serialization runtime, and this says that the two agree on the bytes: a value that holds a
     * quote, a backslash, a newline and an accent comes back exactly as it went in.
     */
    @Test
    fun `a value that holds what has to be escaped comes back as it was`() {
        val awkward = "quote\" backslash\\ newline\n tab\t accentñ"
        val event = StepFinished(
            eventId = "e-1",
            runId = "run-42",
            sequence = 7L,
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            stageIndex = 1,
            stepIndex = 2,
            stepName = awkward,
            stepType = "sh",
            outcome = "failure",
        )

        val encoded = JsonEventLog.encodeOne(event)

        assertTrue(
            encoded.contains("""\n""") && encoded.contains("\\\""),
            "the row must carry the value escaped, or it is not JSON: $encoded",
        )
        assertEquals(event, JsonEventLog.decode("[$encoded]").single())
    }

    /**
     * A list whose element holds a bracket is read whole.
     *
     * The old reader cut the list at the first bracket, so an element that held one truncated
     * the list and the rest of the patterns were never seen.
     */
    @Test
    fun `a list whose element holds a bracket is read whole`() {
        val event = WsCleaned(
            eventId = "e-1",
            runId = "run-42",
            sequence = 7L,
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedFiles = 3,
            deletedDirs = 1,
            patterns = listOf("build/**", "tmp[1]", "out"),
            sha256 = "abc",
        )

        val encoded = JsonEventLog.encodeOne(event)

        assertEquals(event, JsonEventLog.decode("[$encoded]").single())
    }

    /**
     * A row with a mandatory field missing is refused, and the refusal names the field.
     *
     * The old reader read a missing index as `0` and a missing name as `""`. A durable record
     * that reads back as something nobody wrote is worse than a durable record that is
     * refused, because the refusal is visible and the invention is not.
     */
    @Test
    fun `a row with a mandatory field missing is refused and the refusal names the field`() {
        val row = """
            {"eventId":"e-1","runId":"run-42","sequence":7,"kind":"StepFinished",
             "occurredAt":"2026-01-01T00:00:00Z","stepIndex":2,
             "stepName":"build/sh-2","stepType":"sh"}
        """.trimIndent().replace("\n", "")

        val outcome = EventJsonCodec.decode(row)

        assertTrue(
            outcome is EventDecodeOutcome.Failed,
            "a row without its stage index must be refused, and it was read as $outcome",
        )
        val cause = (outcome as EventDecodeOutcome.Failed).cause
        assertTrue(
            cause is EventDecodeFailure.UnreadableField && cause.field == "stageIndex",
            "the refusal must name the field that is missing, and it said $cause",
        )
    }

    /**
     * A row whose kind this binary does not know is refused as version skew.
     *
     * It is not read as some kind it does know: a row from the future is a fact this runtime
     * cannot interpret, and reading it as a fact it can would be worse than refusing it.
     */
    @Test
    fun `a kind this binary does not know is refused as version skew`() {
        val row = """
            {"eventId":"e-1","runId":"run-42","sequence":7,"kind":"StageReconfigured",
             "occurredAt":"2026-01-01T00:00:00Z","stageIndex":1}
        """.trimIndent().replace("\n", "")

        val outcome = EventJsonCodec.decode(row)

        assertTrue(
            outcome is EventDecodeOutcome.Failed &&
                (outcome as EventDecodeOutcome.Failed).cause is EventDecodeFailure.UnknownEventKind,
            "a kind nobody declared must be refused as skew, and it was read as $outcome",
        )
    }

    /**
     * A row whose payload is another kind's is refused.
     *
     * The kind is the word the row travels under, and the payload is what the row carries.
     * A payload that does not answer to the word it travels under is a row that is not what it
     * announced itself to be, and it is refused rather than read with invented values.
     */
    @Test
    fun `a row whose payload does not answer to its kind is refused`() {
        val row = """
            {"eventId":"e-1","runId":"run-42","sequence":7,"kind":"StageStarted",
             "occurredAt":"2026-01-01T00:00:00Z","stepIndex":2,
             "stepName":"build/sh-2","stepType":"sh"}
        """.trimIndent().replace("\n", "")

        val outcome = EventJsonCodec.decode(row)

        assertTrue(
            outcome is EventDecodeOutcome.Failed,
            "a payload that does not answer to its kind must be refused, and it was read as $outcome",
        )
    }
}
