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
import kotlin.reflect.KClass
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant

/**
 * The optional fields of the event contract, on real bytes.
 *
 * ## The guarantee
 *
 * A field that is not declared travels by its own convention, and the reader knows it:
 *
 *  - an optional STRING travels as the empty string, and comes back as "not declared";
 *  - an optional INDEX travels as `-1`, and comes back as "not declared";
 *  - a row written BEFORE the field existed does not carry it, and comes back the same way.
 *
 * The third case is the one that used to be impossible to guarantee: the reader demanded the
 * field, so a row from before the field existed could not be read at all. The reader now takes
 * the absence as the answer, and the test below holds every kind of the contract to it.
 *
 * ## Why the coverage is mechanical
 *
 * The set of fields that can be absent is not written down in this file: it is whatever the
 * event classes declare as nullable. The test walks the descriptors of the sealed hierarchy
 * and says, for every kind, that a field which CAN be absent also CAN be read as absent. A
 * field that is nullable without a default would fail that walk, and a row written before it
 * existed would be refused.
 *
 * Pure and total: no database, no clock, no filesystem.
 */
class OptionalEventFieldRoundTripTest {

    /**
     * One optional field of the contract: the name the contract gives it, and the event with
     * the field absent and with it present.
     */
    private data class Case(
        val wireName: String,
        val absent: DomainEvent,
        val present: DomainEvent,
        val wireValue: String,
    )

    /**
     * The whole set of optional fields, one row per field of the contract.
     *
     * Every row is an event that really exists in the vocabulary, so a field that this table
     * does not carry is a field nobody has pinned.
     */
    private val cases: List<Case> = listOf(
        Case("outcome", stepFinished(null), stepFinished("failure"), "failure"),
        Case("remoteUri", agentResolved(null), agentResolved("ssh://runner-7"), "ssh://runner-7"),
        Case("stepName", timeoutScheduled(null, null), timeoutScheduled("build/compile", null), "build/compile"),
        Case("stepType", timeoutScheduled(null, null), timeoutScheduled(null, "sh"), "sh"),
        Case("buildResult", catchErrorTriggered(null, null), catchErrorTriggered(Unstable, null), "UNSTABLE"),
        Case("message", catchErrorTriggered(null, null), catchErrorTriggered(null, "the provider refused"), "the provider refused"),
        Case("label", milestoneReached(null), milestoneReached("checkpoint-2"), "checkpoint-2"),
        Case("reason", lockRequested(null), lockRequested("another run holds it"), "another run holds it"),
        Case("submitter", inputRequested(null, null), inputRequested("reviewer-1", null), "reviewer-1"),
        Case("id", inputRequested(null, null), inputRequested(null, "question-42"), "question-42"),
        Case("submitter", inputProceed(null, null), inputProceed("reviewer-2", null), "reviewer-2"),
        Case("message", inputProceed(null, null), inputProceed(null, "approved"), "approved"),
        Case("submitter", inputAborted(null, null), inputAborted("reviewer-3", null), "reviewer-3"),
        Case("message", inputAborted(null, null), inputAborted(null, "declined"), "declined"),
        Case("detail", compensationFinished(null), compensationFinished("the provider refused"), "the provider refused"),
        Case("reason", sagaTerminal(null), sagaTerminal("COMPENSATION_NOT_DISPATCHED"), "COMPENSATION_NOT_DISPATCHED"),
    )

    /**
     * Every field that CAN be absent can also BE absent.
     *
     * A row written before a field existed does not carry it, so a reader that insisted on the
     * field would refuse a row that is perfectly readable. This walks the descriptors of every
     * kind and says that the nullable ones all carry a default.
     *
     * The descriptors come from the sealed hierarchy itself, one class at a time. The descriptor
     * of the hierarchy as a whole does NOT carry them: it is the shape of a POLYMORPHIC value
     * (a type and a value), and walking it would say nothing about any event.
     */
    @Test
    fun `every field that can be absent can be read as absent`() {
        val required = mutableListOf<String>()
        for (kind in DomainEvent::class.sealedSubclasses) {
            val descriptor = descriptorOf(kind)
            for (index in 0 until descriptor.elementsCount) {
                val element = descriptor.getElementDescriptor(index)
                if (element.isNullable && !descriptor.isElementOptional(index)) {
                    required += "${descriptor.serialName}.${descriptor.getElementName(index)}"
                }
            }
        }
        assertTrue(
            required.isEmpty(),
            "these fields are nullable without a default, so a row written before they existed " +
                "would be refused instead of read: $required",
        )
    }

    /**
     * The kinds of the vocabulary, and the kinds of the hierarchy.
     *
     * The two are ONE list, not two: a kind the hierarchy does not declare is a word the reader
     * cannot answer for, and a kind the vocabulary does not hold is a word the writer would
     * produce and no reader could decode.
     */
    @Test
    fun `every kind of the vocabulary is a kind of the hierarchy, and the other way round`() {
        val vocabulary = EventKind.entries.map { it.wire }.toSet()
        val hierarchy = DomainEvent::class.sealedSubclasses
            .map { descriptorOf(it).serialName }
            .toSet()

        assertEquals(
            vocabulary,
            hierarchy,
            "the vocabulary and the serializable hierarchy must name the same kinds",
        )
    }

    /**
     * The descriptor of ONE kind of the hierarchy, read from the hierarchy itself.
     *
     * The descriptor is asked for by the CLASS, not for by an instance: a kind nobody has produced
     * yet still has fields, and the rule about absent fields is about the shape, not about what
     * has been seen.
     */
    private fun descriptorOf(kind: KClass<out DomainEvent>): SerialDescriptor =
        serializer(kind.java).descriptor

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
