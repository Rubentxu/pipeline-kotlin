package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The subject of the envelope of a branch step.
 *
 * In a parallel stage two branches run steps with the same stage index and the same step
 * index, so a subject derived from the indices names BOTH branches and neither: a consumer
 * that resolves events by subject attributes one branch's rows to the other. The step's
 * events carry the durable identity of the operation the step runs as (`opId`), and that is
 * the segment the subject was missing.
 *
 * A step that is not in a branch carries no operation identity, and its envelope keeps the
 * subject it has always had: every consumer of the published envelope keeps reading those
 * rows the same way.
 */
class BranchStepSubjectTest {

    @Test
    @DisplayName("el sujeto del envelope de un paso de rama distingue a la rama hermana")
    fun `the envelope of a branch step names the branch, not only the step`() {
        val first = EnvelopeProjector.project(branchStepStarted(opId = OP_FIRST))
        val second = EnvelopeProjector.project(branchStepStarted(opId = OP_SECOND))

        assertNotEquals(
            first.subject.canonicalText(),
            second.subject.canonicalText(),
            "two sibling branches produced the same envelope subject",
        )
        assertEquals(ResourceKind.OPERATION, first.subject.kind)
        assertEquals(ResourceKind.OPERATION, second.subject.kind)
        assertEquals(
            ResourceRefs.operation(RUN, STAGE, STEP, ResourceRefs.operationKey(OP_FIRST)).canonicalText(),
            first.subject.canonicalText(),
        )
        assertEquals(
            ResourceRefs.operation(RUN, STAGE, STEP, ResourceRefs.operationKey(OP_SECOND)).canonicalText(),
            second.subject.canonicalText(),
        )
    }

    @Test
    @DisplayName("el sujeto de una rama real con dos puntos en su operación es el opId codificado")
    fun `a real branch operation with colons encodes into the subject`() {
        // The shape the product actually publishes for a parallel branch step: the durable
        // OpId names the branch frames with colons, and the subject carries the WHOLE
        // operation, encoded — never a trimmed piece of it.
        val realOpId = "$RUN-s$STAGE-$STEP-bp2-b1:branch-0:core.sh"
        val envelope = EnvelopeProjector.project(branchStepStarted(opId = realOpId))

        assertEquals(ResourceRefs.operation(RUN, STAGE, STEP, ResourceRefs.operationKey(realOpId)).canonicalText(), envelope.subject.canonicalText())
        assertEquals(
            realOpId,
            ResourceRefs.decodeOperationKey(envelope.subject.segments.last()),
            "the subject's op segment did not decode back to the operation the event published",
        )
    }

    @Test
    @DisplayName("el terminal y el fallo de una rama llevan el mismo sujeto que su arranque")
    fun `the terminal and the failure of a branch carry the same subject as its start`() {
        val started = EnvelopeProjector.project(branchStepStarted(opId = OP_FIRST))
        val finished = EnvelopeProjector.project(branchStepFinished(opId = OP_FIRST))
        val failed = EnvelopeProjector.project(branchStepFailed(opId = OP_FIRST))

        assertEquals(started.subject.canonicalText(), finished.subject.canonicalText())
        assertEquals(started.subject.canonicalText(), failed.subject.canonicalText())
    }

    @Test
    @DisplayName("un paso fuera de una rama conserva el sujeto que tiene hoy")
    fun `a step outside a branch keeps the subject it has today`() {
        val started = EnvelopeProjector.project(branchStepStarted(opId = null))
        val finished = EnvelopeProjector.project(branchStepFinished(opId = null))
        val failed = EnvelopeProjector.project(branchStepFailed(opId = null))

        assertEquals(ResourceRefs.step(RUN, STAGE, STEP), started.subject)
        assertEquals(ResourceRefs.step(RUN, STAGE, STEP), finished.subject)
        assertEquals(ResourceRefs.step(RUN, STAGE, STEP), failed.subject)
    }

    // --- the fixture ----------------------------------------------------------------

    private companion object {
        const val RUN = "run-under-subject"
        const val STAGE = 0
        const val STEP = 0
        const val OP_FIRST = "op-branch-1"
        const val OP_SECOND = "op-branch-2"
        val AT: Instant = Instant.ofEpochMilli(1_700_000_000_000L)
    }

    /**
     * Both branches of the stage run a step at the same coordinates; only the operation
     * identity they carry tells them apart.
     */
    private fun branchStepStarted(opId: String?) = StepStarted(
        eventId = "e-start",
        runId = RUN,
        sequence = 1L,
        occurredAt = AT,
        stageIndex = STAGE,
        stepIndex = STEP,
        stepName = "fan/branch-a-b/sh-0",
        stepType = "sh",
        opId = opId,
    )

    private fun branchStepFinished(opId: String?) = StepFinished(
        eventId = "e-finish",
        runId = RUN,
        sequence = 2L,
        occurredAt = AT,
        stageIndex = STAGE,
        stepIndex = STEP,
        stepName = "fan/branch-a-b/sh-0",
        stepType = "sh",
        outcome = StepOutcome.SUCCESS_TOKEN,
        opId = opId,
    )

    private fun branchStepFailed(opId: String?) = StepFailed(
        eventId = "e-fail",
        runId = RUN,
        sequence = 2L,
        occurredAt = AT,
        stageIndex = STAGE,
        stepIndex = STEP,
        stepName = "fan/branch-a-b/sh-0",
        stepType = "sh",
        failureKind = FailureKind.SCRIPT,
        message = "shell exited with code 1",
        opId = opId,
    )
}
