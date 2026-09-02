package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RunOutcomeReducerTest {

    private val scriptFailure = PipelineFailure(FailureKind.SCRIPT, "script exited 1")
    private val networkFailure = PipelineFailure(FailureKind.NETWORK, "dns unreachable")

    @Test
    fun `empty list reduces to Success because nothing to do is not a failure`() {
        assertSame(RunOutcome.Success, RunOutcomeReducer.reduce(emptyList()))
    }

    @Test
    fun `all Success steps reduce to Success`() {
        val outcome = RunOutcomeReducer.reduce(
            listOf(StepOutcome.Success, StepOutcome.Success, StepOutcome.Success),
        )

        assertSame(RunOutcome.Success, outcome)
    }

    @Test
    fun `a single Unstable step reduces to Unstable`() {
        val outcome = RunOutcomeReducer.reduce(listOf(StepOutcome.Unstable))

        assertSame(RunOutcome.Unstable, outcome)
    }

    @Test
    fun `a single Failure step reduces to Failure carrying that failure`() {
        val outcome = RunOutcomeReducer.reduce(listOf(StepOutcome.Failure(scriptFailure)))

        assertEquals(RunOutcome.Failure(scriptFailure), outcome)
    }

    @Test
    fun `Failure wins over Unstable and Success regardless of order`() {
        val unstableFirst = RunOutcomeReducer.reduce(
            listOf(StepOutcome.Unstable, StepOutcome.Failure(scriptFailure)),
        )
        val failureLast = RunOutcomeReducer.reduce(
            listOf(StepOutcome.Success, StepOutcome.Unstable, StepOutcome.Failure(scriptFailure)),
        )

        assertEquals(RunOutcome.Failure(scriptFailure), unstableFirst)
        assertEquals(RunOutcome.Failure(scriptFailure), failureLast)
    }

    @Test
    fun `the first Failure wins and later failures are ignored`() {
        val outcome = RunOutcomeReducer.reduce(
            listOf(
                StepOutcome.Failure(scriptFailure),
                StepOutcome.Failure(networkFailure),
                StepOutcome.Success,
            ),
        )

        assertEquals(RunOutcome.Failure(scriptFailure), outcome)
    }

    @Test
    fun `Unstable wins over Success regardless of order`() {
        val unstableFirst = RunOutcomeReducer.reduce(
            listOf(StepOutcome.Unstable, StepOutcome.Success),
        )
        val successFirst = RunOutcomeReducer.reduce(
            listOf(StepOutcome.Success, StepOutcome.Success, StepOutcome.Unstable),
        )

        assertSame(RunOutcome.Unstable, unstableFirst)
        assertSame(RunOutcome.Unstable, successFirst)
    }

    @Test
    fun `multiple Unstable steps collapse to a single Unstable`() {
        val outcome = RunOutcomeReducer.reduce(
            listOf(StepOutcome.Unstable, StepOutcome.Unstable, StepOutcome.Success),
        )

        assertSame(RunOutcome.Unstable, outcome)
    }

    @Test
    fun `Aborted is never derived from steps — only set explicitly`() {
        val outcome = RunOutcomeReducer.reduce(
            listOf(StepOutcome.Failure(scriptFailure)),
        )

        // Reducer never produces Aborted; the orchestrator must.
        assertEquals(RunOutcome.Failure(scriptFailure), outcome)
    }

    @Test
    fun `reducer is pure — repeated calls with the same input yield the same result`() {
        val input = listOf(
            StepOutcome.Success,
            StepOutcome.Unstable,
            StepOutcome.Failure(scriptFailure),
        )

        val first = RunOutcomeReducer.reduce(input)
        val second = RunOutcomeReducer.reduce(input)
        val third = RunOutcomeReducer.reduce(input.toList())

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(RunOutcome.Failure(scriptFailure), first)
    }
}
