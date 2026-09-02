package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingStepDispatcherTest {

    private fun step(id: String) = StepDescriptor(id = id, type = "sh", configRef = "$id.config")

    @Test
    fun `records dispatched step ids in order`() {
        val dispatcher = RecordingStepDispatcher.successOnly()

        dispatcher.dispatch(step("build"), StepExecutionContext(RunId("r1")))
        dispatcher.dispatch(step("test"), StepExecutionContext(RunId("r1")))
        dispatcher.dispatch(step("deploy"), StepExecutionContext(RunId("r1")))

        assertEquals(listOf("build", "test", "deploy"), dispatcher.dispatchedStepIds)
    }

    @Test
    fun `records attempt numbers from the execution context`() {
        val dispatcher = RecordingStepDispatcher.successOnly()

        dispatcher.dispatch(step("build"), StepExecutionContext(RunId("r1"), attempt = 0))
        dispatcher.dispatch(step("build"), StepExecutionContext(RunId("r1"), attempt = 1))

        assertEquals(listOf("build" to 0, "build" to 1), dispatcher.dispatchedSteps)
    }

    @Test
    fun `defaults to Success for steps without configured outcomes`() {
        val dispatcher = RecordingStepDispatcher.successOnly()

        val outcome = dispatcher.dispatch(step("anything"), StepExecutionContext(RunId("r1")))

        assertEquals(StepOutcome.Success, outcome)
    }

    @Test
    fun `returns the configured outcome for a step id`() {
        val failure = StepOutcome.Failure(
            PipelineFailure(FailureKind.SCRIPT, "build broke")
        )
        val dispatcher = RecordingStepDispatcher(mapOf("build" to listOf(failure)))

        val outcome = dispatcher.dispatch(step("build"), StepExecutionContext(RunId("r1")))

        assertEquals(failure, outcome)
    }

    @Test
    fun `configured outcome list is consumed in order with the last element repeating`() {
        val first = StepOutcome.Failure(
            PipelineFailure(FailureKind.SCRIPT, "transient")
        )
        val dispatcher = RecordingStepDispatcher(mapOf("flaky" to listOf(first, StepOutcome.Success)))

        val attempt0 = dispatcher.dispatch(step("flaky"), StepExecutionContext(RunId("r1"), attempt = 0))
        val attempt1 = dispatcher.dispatch(step("flaky"), StepExecutionContext(RunId("r1"), attempt = 1))
        val attempt2 = dispatcher.dispatch(step("flaky"), StepExecutionContext(RunId("r1"), attempt = 2))

        assertEquals(first, attempt0)
        assertEquals(StepOutcome.Success, attempt1)
        assertEquals(StepOutcome.Success, attempt2, "last configured outcome repeats")
    }

    @Test
    fun `dispatchCount tracks per-step invocation counts`() {
        val dispatcher = RecordingStepDispatcher.successOnly()

        dispatcher.dispatch(step("build"), StepExecutionContext(RunId("r1")))
        dispatcher.dispatch(step("build"), StepExecutionContext(RunId("r1")))
        dispatcher.dispatch(step("test"), StepExecutionContext(RunId("r1")))

        assertEquals(2, dispatcher.dispatchCount("build"))
        assertEquals(1, dispatcher.dispatchCount("test"))
        assertEquals(0, dispatcher.dispatchCount("never-called"))
    }

    @Test
    fun `exposed call log is a snapshot that does not mutate on later dispatches`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        dispatcher.dispatch(step("build"), StepExecutionContext(RunId("r1")))
        val snapshot = dispatcher.dispatchedSteps

        dispatcher.dispatch(step("test"), StepExecutionContext(RunId("r1")))

        assertEquals(1, snapshot.size, "an earlier snapshot must not change")
        assertEquals(2, dispatcher.dispatchedSteps.size)
    }

    @Test
    fun `backing outcome map is defensively copied at construction`() {
        val mutable = mutableMapOf<String, List<StepOutcome>>()
        val dispatcher = RecordingStepDispatcher(mutable)
        mutable["late"] = listOf(
            StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "late"))
        )

        val outcome = dispatcher.dispatch(step("late"), StepExecutionContext(RunId("r1")))

        assertEquals(StepOutcome.Success, outcome, "late-inserted outcomes must not be visible")
    }
}
