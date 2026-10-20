package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.SagaOutcome
import dev.rubentxu.pipeline.v2.events.CompensationArmed
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.time.Instant

/**
 * How the verdict is read off the Event Plane, and what happens when the plane cannot be read.
 *
 * Every test here is a case the running pipeline does NOT produce on purpose: a plane with no
 * ending, a word from a newer build, a Saga that armed and never said how it ended. Those are
 * the cases where a reader is tempted to guess, and the whole point of the projection is that
 * it does not.
 */
class RunPresentationProjectionTest {

    private fun runFinished(outcome: String, runId: String = RUN): RunFinished = RunFinished(
        eventId = "event-run-finished",
        runId = runId,
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        outcome = outcome,
        diagnostics = emptyList(),
    )

    private fun stepFailed(
        message: String = "the action ship failed",
        kind: FailureKind = FailureKind.SCRIPT,
        runId: String = RUN,
        stepName: String = "ship",
    ): StepFailed = StepFailed(
        eventId = "event-step-failed",
        runId = runId,
        sequence = 2L,
        occurredAt = Instant.EPOCH,
        stepIndex = 1,
        stepName = stepName,
        stepType = "sh",
        failureKind = kind,
        message = message,
    )

    private fun stepStarted(stepName: String, runId: String = RUN): StepStarted = StepStarted(
        eventId = "event-step-started",
        runId = runId,
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        stageIndex = 0,
        stepIndex = 0,
        stepName = stepName,
        stepType = "sh",
    )

    private fun sagaTerminal(
        sagaId: String = "checkout",
        state: String = "SETTLED",
        reason: String? = null,
        runId: String = RUN,
    ): SagaTerminal = SagaTerminal(
        eventId = "event-saga-terminal",
        runId = runId,
        sequence = 3L,
        occurredAt = Instant.EPOCH,
        sagaId = sagaId,
        state = state,
        reason = reason,
        compensatedActions = emptyList(),
    )

    private fun compensationArmed(sagaId: String = "checkout", runId: String = RUN): CompensationArmed = CompensationArmed(
        eventId = "event-compensation-armed",
        runId = runId,
        sequence = 0L,
        occurredAt = Instant.EPOCH,
        sagaId = sagaId,
        actionId = "reserve",
        compensationId = "release",
        planFingerprint = "fingerprint",
        intentVersion = 1,
        resourceRef = "stock/42",
        compensationOpId = "saga-v1|compensate|release",
        leaseEpoch = 1L,
    )

    private fun read(vararg events: DomainEvent) = RunPresentationProjection.read(RUN, events.toList())

    @Nested
    inner class TheRunsOwnOutcome {

        @Test
        fun `a plane with no ending is not a success`() {
            // The absence of an ending is not evidence of a good one. It is evidence of nothing,
            // and the honest reading of "we do not know how it ended" is "it did not end well".
            val presentation = read(sagaTerminal())

            assertEquals(RunOutcome.Aborted, presentation.outcome)
            assertFalse(presentation.isFinished)
        }

        @Test
        fun `the outcome word names the outcome`() {
            assertEquals(RunOutcome.Success, read(runFinished("success")).outcome)
            assertEquals(RunOutcome.Unstable, read(runFinished("unstable")).outcome)
            assertEquals(RunOutcome.Aborted, read(runFinished("aborted")).outcome)
        }

        @Test
        fun `a failure is read with the detail of the step that failed`() {
            // The terminal carries the WORD "failure" and not the failure, so the detail has to
            // come from the plane. This is the same "first failure wins" the reducer follows.
            val presentation = read(runFinished("failure"), stepFailed(message = "exit 1"))

            val failure = assertInstanceOf<RunOutcome.Failure>(presentation.outcome)
            assertEquals(FailureKind.SCRIPT, failure.failure.kind)
            assertEquals("exit 1", failure.failure.message)
        }

        @Test
        fun `a failure with no failed step in the plane is an engine failure`() {
            // The run says it failed and the plane says why not. There is no other explanation,
            // so it is reported as the engine failure it is.
            val presentation = read(runFinished("failure"))

            val failure = assertInstanceOf<RunOutcome.Failure>(presentation.outcome)
            assertEquals(FailureKind.ENGINE, failure.failure.kind)
        }

        @Test
        fun `the failure of the run is the outer one, not the body inside it`() {
            // MEASURED on the real plane of the saga scenario: a failing plugin step announces
            // TWO failures — the failure of the body it ran, and its own. The run's outcome is
            // the OUTER one, because the inner one belongs to a step nested inside the plugin's
            // body and not to a step of the run.
            val presentation = read(
                runFinished("failure"),
                stepStarted("checkout/registryblock-body-0"),
                stepStarted("checkout/registryblock-body-0/sh-0"),
                stepFailed(
                    stepName = "checkout/registryblock-body-0/sh-0",
                    message = "shell exited with code 1",
                ),
                stepFailed(
                    stepName = "checkout/registryblock-body-0",
                    kind = FailureKind.PLUGIN,
                    message = "the action ship failed: shell exited with code 1",
                ),
            )

            val failure = assertInstanceOf<RunOutcome.Failure>(presentation.outcome)
            assertEquals(FailureKind.PLUGIN, failure.failure.kind)
            assertEquals("the action ship failed: shell exited with code 1", failure.failure.message)
        }

        @Test
        fun `an outcome word nobody knows is a failure, not a success`() {
            // The dangerous direction: a word from a newer build, read as a good run.
            val presentation = read(runFinished("mostly-fine"))

            val failure = assertInstanceOf<RunOutcome.Failure>(presentation.outcome)
            assertEquals(FailureKind.ENGINE, failure.failure.kind)
            assertTrue(failure.failure.message.contains("mostly-fine"))
        }

        @Test
        fun `the last ending of the run is the current one`() {
            // A resumed run reuses the run id and announces its ending again.
            val presentation = read(runFinished("failure"), runFinished("success"))

            assertEquals(RunOutcome.Success, presentation.outcome)
        }
    }

    @Nested
    inner class WhatTheSagasLeftBehind {

        @Test
        fun `a settled Saga is settled`() {
            val presentation = read(runFinished("failure"), sagaTerminal(state = "SETTLED"))

            assertEquals(listOf(SagaOutcome.Settled("checkout")), presentation.sagas)
            assertFalse(presentation.needsIntervention)
        }

        @Test
        fun `a Saga that needs a human names the reason`() {
            val presentation = read(
                runFinished("failure"),
                sagaTerminal(state = "NEEDS_INTERVENTION", reason = "COMPENSATION_UNCERTAIN"),
            )

            assertEquals(
                listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.COMPENSATION_UNCERTAIN)),
                presentation.sagas,
            )
            assertTrue(presentation.needsIntervention)
        }

        @Test
        fun `a state nobody knows is never read as a settled Saga`() {
            // The one mistake this vocabulary exists to prevent: a Saga that cannot be read
            // read as a Saga that closed.
            val presentation = read(runFinished("failure"), sagaTerminal(state = "ALL_GOOD"))

            assertEquals(
                listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.TERMINAL_UNREADABLE)),
                presentation.sagas,
            )
            assertTrue(presentation.needsIntervention)
        }

        @Test
        fun `a reason nobody knows still means a human`() {
            val presentation = read(
                runFinished("failure"),
                sagaTerminal(state = "NEEDS_INTERVENTION", reason = "SOMETHING_NEW"),
            )

            assertEquals(
                listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.TERMINAL_UNREADABLE)),
                presentation.sagas,
            )
        }

        @Test
        fun `a terminal that contradicts itself is not read as settled`() {
            val settledWithAReason = read(runFinished("failure"), sagaTerminal(state = "SETTLED", reason = "UNKNOWN_EFFECT"))
            val interventionWithoutAReason = read(runFinished("failure"), sagaTerminal(state = "NEEDS_INTERVENTION"))

            assertEquals(
                listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.TERMINAL_UNREADABLE)),
                settledWithAReason.sagas,
            )
            assertEquals(
                listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.TERMINAL_UNREADABLE)),
                interventionWithoutAReason.sagas,
            )
        }

        @Test
        fun `a Saga that armed and never announced is still open`() {
            // The obligation is owed and nobody said how it ended. "Nothing is owed" would be
            // a guess, and it is the guess that loses a resource.
            val presentation = read(runFinished("failure"), compensationArmed())

            assertEquals(
                listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.TERMINAL_UNREADABLE)),
                presentation.sagas,
            )
            assertTrue(presentation.needsIntervention)
            assertFalse(presentation.isFinished)
        }

        @Test
        fun `the last announcement of a Saga is its current state`() {
            // A Saga that needed a human and later reconciled is settled; the newest
            // announcement is the one that says where it stands now.
            val presentation = read(
                runFinished("failure"),
                sagaTerminal(state = "NEEDS_INTERVENTION", reason = "UNKNOWN_EFFECT"),
                sagaTerminal(state = "SETTLED"),
            )

            assertEquals(listOf(SagaOutcome.Settled("checkout")), presentation.sagas)
            assertFalse(presentation.needsIntervention)
        }

        @Test
        fun `each Saga of the run is read on its own`() {
            val presentation = read(
                runFinished("failure"),
                sagaTerminal(sagaId = "checkout", state = "SETTLED"),
                sagaTerminal(sagaId = "shipping", state = "NEEDS_INTERVENTION", reason = "COMPENSATION_FAILED"),
            )

            assertEquals(2, presentation.sagas.size)
            assertTrue(presentation.needsIntervention)
        }
    }

    @Nested
    inner class ThePlaneItself {

        @Test
        fun `the events of another run are ignored`() {
            val presentation = RunPresentationProjection.read(
                RUN,
                listOf(
                    runFinished("failure", runId = "other-run"),
                    sagaTerminal(runId = "other-run"),
                    runFinished("success"),
                ),
            )

            assertEquals(RunOutcome.Success, presentation.outcome)
            assertTrue(presentation.sagas.isEmpty())
        }

        @Test
        fun `a compensated Saga does not make the failed run green`() {
            // The invariant that gives a Saga its meaning: the failure was undone, and the
            // failure is still a failure.
            val presentation = read(
                runFinished("failure"),
                stepFailed(),
                sagaTerminal(state = "SETTLED"),
            )

            val failure = assertInstanceOf<RunOutcome.Failure>(presentation.outcome)
            assertEquals("the action ship failed", failure.failure.message)
            assertTrue(presentation.isFinished)
            assertFalse(presentation.needsIntervention)
        }

        @Test
        fun `a run with a Saga pending is not finished, however green it was`() {
            val presentation = read(
                runFinished("success"),
                sagaTerminal(state = "NEEDS_INTERVENTION", reason = "UNKNOWN_EFFECT"),
            )

            assertEquals(RunOutcome.Success, presentation.outcome)
            assertFalse(presentation.isFinished)
            assertTrue(presentation.needsIntervention)
        }

        @Test
        fun `the failure of a run is the one the plane describes`() {
            val presentation = read(
                runFinished("failure"),
                stepFailed(message = "the write-ahead for reserve was refused", kind = FailureKind.PLUGIN),
            )

            val failure = assertInstanceOf<RunOutcome.Failure>(presentation.outcome)
            assertEquals(
                PipelineFailure(kind = FailureKind.PLUGIN, message = "the write-ahead for reserve was refused"),
                failure.failure,
            )
        }
    }

    private companion object {
        const val RUN = "run-1"
    }
}
