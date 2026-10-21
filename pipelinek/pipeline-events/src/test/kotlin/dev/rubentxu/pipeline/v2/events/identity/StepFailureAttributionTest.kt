package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A failure says which stage it belongs to, and the envelope says so too.
 *
 * ## The guarantee
 *
 * A consumer of the event plane can attribute a failure to its stage **from the failure event
 * itself**. Before this, the failure named the step and not the stage, and the only way to
 * learn the stage was to join the failure with another event — a join by a step index, which
 * means a different step in each stage, so the join was ambiguous between stages.
 *
 * ## Why the envelope is part of it
 *
 * The subject of an envelope says what the event is ABOUT. A failure that names its stage is
 * about that step, and a failure that names none keeps the run as its subject rather than
 * guessing stage 0: a failure in the first stage and a failure whose stage nobody recorded are
 * two different facts, and only one of them is a stage.
 */
class StepFailureAttributionTest {

    @Test
    fun `a failure that names its stage is about that step`() {
        val subject = EnvelopeProjector.project(failed(stageIndex = 1)).subject

        assertEquals(ResourceKind.STEP, subject.kind)
        assertEquals(listOf("pipeline", "run", RUN, "stage", "1", "step", "0"), subject.segments)
    }

    @Test
    fun `a failure that names no stage keeps the run as its subject`() {
        // The absence of a stage is not stage 0. A row written before the stage was published
        // names none, and its subject says so: the run, which is the honest subject when the
        // step's identity is not fully known.
        val subject = EnvelopeProjector.project(failed(stageIndex = null)).subject

        assertEquals(ResourceKind.RUN, subject.kind)
        assertEquals(listOf("pipeline", "run", RUN), subject.segments)
    }

    @Test
    fun `a failure shares its subject with the terminal of the same step`() {
        // The point of naming the stage: a consumer that groups the plane by subject can put
        // the failure next to the step it belongs to, without reading a second event to find
        // out which one that was.
        val failure = EnvelopeProjector.project(failed(stageIndex = 1)).subject
        val terminal = EnvelopeProjector.project(
            StepFinished(
                eventId = "event-step-finished",
                runId = RUN,
                sequence = 5L,
                occurredAt = Instant.EPOCH,
                stageIndex = 1,
                stepIndex = 0,
                stepName = "ship",
                stepType = "sh",
                outcome = "failure",
            ),
        ).subject

        assertEquals(terminal, failure)
    }

    private fun failed(stageIndex: Int?, stepIndex: Int = 0) = StepFailed(
        eventId = "event-step-failed",
        runId = RUN,
        sequence = 4L,
        occurredAt = Instant.EPOCH,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        stepName = "ship",
        stepType = "sh",
        failureKind = FailureKind.SCRIPT,
        message = "shell exited with code 1",
    )

    private companion object {
        const val RUN = "run-under-test"
    }
}
