package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The operation a step publishes, on real bytes.
 *
 * ## The guarantee
 *
 * A step that runs inside a parallel branch publishes the operation it runs as, and the row
 * carries that operation: a reader of the durable log finds the identity of the step, not a
 * step name that has to be taken apart to learn it.
 *
 * A step OUTSIDE a branch publishes nothing, and its row is the row it was before the field
 * existed: the field is not there, not empty. A row that carries no new information does not
 * change shape, so the rows a reader already has keep reading the same way and a reader that
 * knows nothing about this field keeps understanding every row it reads.
 *
 * Pure: no database, no clock, no filesystem.
 */
class StepOperationIdWireTest {

    /**
     * The operation of a step of the first branch of a `parallel`.
     */
    private val operation = "run-42-s0-0-bp2-b0:branch-0:core.sh"

    /**
     * The row a build that published no operation wrote.
     */
    private val legacyRow = (
        """{"eventId":"e-1","runId":"run-42","sequence":7,"kind":"StepStarted",""" +
            """"occurredAt":"2026-01-01T00:00:00Z","stageIndex":1,"stepIndex":2,""" +
            """"stepName":"build/sh-2","stepType":"sh"}"""
        )

    @Test
    @DisplayName("a step that publishes an operation carries it in the row")
    fun aStepThatPublishesAnOperationCarriesItInTheRow() {
        val encoded = JsonEventLog.encodeOne(stepStarted(operation))

        assertTrue(
            encoded.contains(",\"opId\":\"$operation\""),
            "the row does not carry the operation: $encoded",
        )
        assertEquals(
            operation,
            (JsonEventLog.decode("[$encoded]").single() as StepStarted).opId,
            "the operation survives the durable log",
        )
    }

    @Test
    @DisplayName("a step that publishes no operation writes the row it wrote before")
    fun aStepThatPublishesNoOperationWritesTheRowItWroteBefore() {
        val encoded = JsonEventLog.encodeOne(stepStarted(null))

        assertEquals(
            legacyRow,
            encoded,
            "a row that carries no new information does not change shape",
        )
        assertNull(
            (JsonEventLog.decode("[$encoded]").single() as StepStarted).opId,
            "the row reads as a step that publishes no operation",
        )
    }

    @Test
    @DisplayName("a row written before the operation existed reads as a step that publishes none")
    fun aRowWrittenBeforeTheOperationExistedReadsAsAStepThatPublishesNone() {
        val decoded = JsonEventLog.decode("[$legacyRow]").single() as StepStarted

        assertNull(decoded.opId, "the row reads as a step that publishes no operation")
    }

    @Test
    @DisplayName("the terminal and the failure carry the operation too")
    fun theTerminalAndTheFailureCarryTheOperationToo() {
        val finished = JsonEventLog.encodeOne(stepFinished(operation))
        val failed = JsonEventLog.encodeOne(stepFailed(operation))

        assertEquals(
            operation,
            (JsonEventLog.decode("[$finished]").single() as StepFinished).opId,
            "the terminal of a step carries the operation the step ran as",
        )
        assertEquals(
            operation,
            (JsonEventLog.decode("[$failed]").single() as StepFailed).opId,
            "the failure of a step carries the operation the step ran as",
        )
    }

    private fun stepStarted(opId: String?): StepStarted = StepStarted(
        eventId = "e-1",
        runId = "run-42",
        sequence = 7L,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        stageIndex = 1,
        stepIndex = 2,
        stepName = "build/sh-2",
        stepType = "sh",
        opId = opId,
    )

    private fun stepFinished(opId: String?): StepFinished = StepFinished(
        eventId = "e-2",
        runId = "run-42",
        sequence = 8L,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        stageIndex = 1,
        stepIndex = 2,
        stepName = "build/sh-2",
        stepType = "sh",
        outcome = "success",
        opId = opId,
    )

    private fun stepFailed(opId: String?): StepFailed = StepFailed(
        eventId = "e-3",
        runId = "run-42",
        sequence = 9L,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        stageIndex = 1,
        stepIndex = 2,
        stepName = "build/sh-2",
        stepType = "sh",
        failureKind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
        message = "shell exited with code 1",
        opId = opId,
    )
}
