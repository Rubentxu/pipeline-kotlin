package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult.Unstable
import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompensationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.InputRequested
import dev.rubentxu.pipeline.v2.events.LockRequested
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.BUILD_RESULT
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.DETAIL
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.ID
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.LABEL
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.MESSAGE
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.OUTCOME
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.REMOTE_URI
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.REASON
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.STEP_NAME
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.STEP_TYPE
import dev.rubentxu.pipeline.v2.events.durable.OptionalEventField.SUBMITTER
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The two halves of the event wire format, pinned to each other.
 *
 * ## The gap this closes
 *
 * The writer and the decoder are two halves of ONE format, and a field added to
 * one side only compiles, runs, and silently decodes wrong. The value simply
 * vanishes on the way back, and a durable record that reads back without its
 * payload looks exactly like a record that never carried one — which is the
 * shape of "the run said success and the log says nothing".
 *
 * The [EventJsonFields] KDoc promised a fitness test for this and there was
 * none. Three of the eleven optional field names were pinned, by two tests
 * that had been written for their own reasons; the other eight were not. This
 * walks the WHOLE set, so the promise is kept by the thing that can fail.
 *
 * ## The three partitions
 *
 *  - **absent** — the field is not declared, and the wire says so with the
 *    empty string. What comes back is `null`, not an empty value: a reader
 *    that cannot tell "not declared" from "declared empty" is a reader that
 *    invents facts.
 *  - **present** — the value travels and comes back as the same value. The
 *    wire is checked for the NAME too, because a round trip alone cannot see a
 *    field that BOTH sides spell the same wrong way.
 *  - **a row written before the field existed** — the key is missing from the
 *    document altogether, which is how every historical record that predates a
 *    field looks. It reads as absent, exactly like the empty string does.
 *    History compatibility is not a nice-to-have here: the journal is durable
 *    and its oldest rows are the ones nobody can rewrite.
 *
 * ## Why the name is written out, and not read back from the authority
 *
 * The expected name is a LITERAL in the table below, not
 * `[OptionalEventField.wireName]`. An expectation derived from the code it
 * checks cannot fail: rename the field on both sides and the test still
 * passes, while every historical record on disk stops being readable. The
 * literal is the specification of a public wire format, and the enum is its
 * implementation; the test holds the first against the second.
 *
 * Pure and total: no database, no clock, no filesystem.
 */
class OptionalEventFieldRoundTripTest {

    /**
     * One optional field of the contract: the authority that declares it, the
     * name the contract gives it, and the event with the field absent and
     * with it present.
     */
    private data class Case(
        val field: OptionalEventField,
        val wireName: String,
        val absent: DomainEvent,
        val present: DomainEvent,
        val wireValue: String,
    )

    /**
     * The whole set of optional fields, one row per field of the contract.
     *
     * Every row is an event that really exists in the vocabulary, so a field
     * that this table does not carry is a field nobody has pinned.
     */
    private val cases: List<Case> = listOf(
        Case(OUTCOME, "outcome", stepFinished(null), stepFinished("failure"), "failure"),
        Case(REMOTE_URI, "remoteUri", agentResolved(null), agentResolved("ssh://runner-7"), "ssh://runner-7"),
        Case(STEP_NAME, "stepName", timeoutScheduled(null, null), timeoutScheduled("build/compile", null), "build/compile"),
        Case(STEP_TYPE, "stepType", timeoutScheduled(null, null), timeoutScheduled(null, "sh"), "sh"),
        Case(BUILD_RESULT, "buildResult", catchErrorTriggered(null, null), catchErrorTriggered(Unstable, null), "UNSTABLE"),
        Case(MESSAGE, "message", catchErrorTriggered(null, null), catchErrorTriggered(null, "the provider refused"), "the provider refused"),
        Case(LABEL, "label", milestoneReached(null), milestoneReached("checkpoint-2"), "checkpoint-2"),
        Case(REASON, "reason", lockRequested(null), lockRequested("another run holds it"), "another run holds it"),
        Case(SUBMITTER, "submitter", inputRequested(null, null), inputRequested("reviewer-1", null), "reviewer-1"),
        Case(ID, "id", inputRequested(null, null), inputRequested(null, "question-42"), "question-42"),
        Case(SUBMITTER, "submitter", inputProceed(null, null), inputProceed("reviewer-2", null), "reviewer-2"),
        Case(MESSAGE, "message", inputProceed(null, null), inputProceed(null, "approved"), "approved"),
        Case(SUBMITTER, "submitter", inputAborted(null, null), inputAborted("reviewer-3", null), "reviewer-3"),
        Case(MESSAGE, "message", inputAborted(null, null), inputAborted(null, "declined"), "declined"),
        Case(DETAIL, "detail", compensationFinished(null), compensationFinished("the provider refused"), "the provider refused"),
        Case(REASON, "reason", sagaTerminal(null), sagaTerminal("COMPENSATION_NOT_DISPATCHED"), "COMPENSATION_NOT_DISPATCHED"),
    )

    /**
     * The authority is CLOSED, so the table can be checked against it: an
     * optional field that no row covers is a field nobody has pinned, and a row
     * for a field the authority does not declare is a row testing nothing.
     */
    @Test
    fun `every optional field of the contract has a row`() {
        assertEquals(
            OptionalEventField.values().toSet(),
            cases.map { it.field }.toSet(),
        )
    }

    @TestFactory
    fun `the writer and the decoder agree on every optional field`(): List<DynamicTest> =
        cases.flatMap { case ->
            val name = "${case.present::class.simpleName}.${case.wireName}"
            listOf(
                dynamicTest("$name: an absent field travels as absent and comes back absent") {
                    val encoded = JsonEventLog.encodeOne(case.absent)

                    assertTrue(
                        encoded.contains(",\"${case.wireName}\":\"\""),
                        "the writer did not carry ${case.wireName} as the encoding of absence: $encoded",
                    )
                    assertEquals(case.absent, JsonEventLog.decode("[$encoded]").single())
                },
                dynamicTest("$name: a present field travels under its name and comes back") {
                    val encoded = JsonEventLog.encodeOne(case.present)

                    assertTrue(
                        encoded.contains(",\"${case.wireName}\":\"${case.wireValue}\""),
                        "the wire does not carry ${case.wireName}: $encoded",
                    )
                    assertEquals(case.present, JsonEventLog.decode("[$encoded]").single())
                },
                dynamicTest("$name: a row written before the field existed reads as absent") {
                    val encoded = JsonEventLog.encodeOne(case.absent)
                    val legacy = encoded.replace(",\"${case.wireName}\":\"\"", "")

                    assertNotEquals(
                        encoded,
                        legacy,
                        "the key was not removed, so this row would prove nothing",
                    )
                    assertEquals(case.absent, JsonEventLog.decode("[$legacy]").single())
                },
            )
        }

    private fun stepFinished(outcome: String?): StepFinished = StepFinished(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 7L,
        occurredAt = AT,
        stageIndex = 1,
        stepIndex = 2,
        stepName = "build/sh-2",
        stepType = "sh",
        outcome = outcome,
    )

    /**
     * `AgentResolved` is deprecated and has never had a producer, but it is part
     * of a published SDK surface, so its wire contract still has to hold: a
     * reader of an old record must find the field it was promised.
     */
    @Suppress("DEPRECATION")
    private fun agentResolved(remoteUri: String?): AgentResolved = AgentResolved(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 8L,
        occurredAt = AT,
        agentLabel = "linux-amd64",
        remoteUri = remoteUri,
    )

    private fun timeoutScheduled(stepName: String?, stepType: String?): TimeoutScheduled = TimeoutScheduled(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 9L,
        occurredAt = AT,
        timeoutSeconds = 600L,
        timeoutAction = "FAIL",
        stepName = stepName,
        stepType = stepType,
        stageIndex = 1,
        stepIndex = 2,
    )

    private fun catchErrorTriggered(
        buildResult: CatchErrorBuildResult?,
        message: String?,
    ): CatchErrorTriggered = CatchErrorTriggered(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 10L,
        occurredAt = AT,
        stageName = "build",
        buildResult = buildResult,
        stageResult = CatchErrorBuildResult.Failure,
        message = message,
    )

    private fun milestoneReached(label: String?): MilestoneReached = MilestoneReached(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 11L,
        occurredAt = AT,
        ordinal = 2,
        label = label,
    )

    private fun lockRequested(reason: String?): LockRequested = LockRequested(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 12L,
        occurredAt = AT,
        resource = "artifact",
        reason = reason,
        skipIfLocked = false,
    )

    private fun inputRequested(submitter: String?, id: String?): InputRequested = InputRequested(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 13L,
        occurredAt = AT,
        message = "publish?",
        submitter = submitter,
        id = id,
    )

    private fun inputProceed(submitter: String?, message: String?): InputProceed = InputProceed(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 14L,
        occurredAt = AT,
        submitter = submitter,
        message = message,
    )

    private fun inputAborted(submitter: String?, message: String?): InputAborted = InputAborted(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 15L,
        occurredAt = AT,
        submitter = submitter,
        message = message,
    )

    private fun compensationFinished(detail: String?): CompensationFinished = CompensationFinished(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 16L,
        occurredAt = AT,
        sagaId = "checkout",
        actionId = "charge",
        compensationId = "refund",
        slot = "compensate-charge",
        outcome = "FAILED",
        detail = detail,
    )

    private fun sagaTerminal(reason: String?): SagaTerminal = SagaTerminal(
        eventId = EVENT_ID,
        runId = RUN_ID,
        sequence = 17L,
        occurredAt = AT,
        sagaId = "checkout",
        state = "NEEDS_INTERVENTION",
        reason = reason,
        compensatedActions = listOf("charge"),
    )

    private companion object {
        const val EVENT_ID = "e-1"
        const val RUN_ID = "run-42"
        val AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
