package dev.rubentxu.pipeline.v2.runtime.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The summarizer turns raw timing evidence into a report, and the dangerous
 * failures are all silent ones: an absent measurement read as zero, a broken
 * clock read as a negative duration, two unrelated clocks added together.
 * Each test below pins one of those refusals, and the last ones pin when the
 * report is allowed to call itself complete at all.
 */
class RuntimeCostProfileTest {

    private fun measured(start: Long, end: Long, domain: String = "run-event-occurred-at") =
        MeasuredDuration(start, end, domain)

    private fun successfulRun() = RunEvidence.Finished("success", measured(0, 100))

    private fun costOf(outcome: ProfileOutcome, cost: RuntimeCost): ResolvedEvidence =
        outcome.costs.first { it.cost == cost }.evidence

    @Test
    fun `an absent cost is unavailable, never zero, and does not make the profile partial`() {
        val outcome = summarize(CostInput(successfulRun(), emptyMap()))

        assertTrue(outcome is ProfileOutcome.Complete)
        assertEquals(
            ResolvedEvidence.Unavailable(UnavailableReason.NoDurationEvidence),
            costOf(outcome, RuntimeCost.Compile),
        )
        assertEquals(RuntimeCost.all.size, outcome.costs.size)
    }

    @Test
    fun `a zero length interval is a measurement of zero, not an absence`() {
        val outcome = summarize(
            CostInput(
                successfulRun(),
                mapOf(RuntimeCost.Compile to CostSamples.Intervals(listOf(measured(50, 50)))),
            ),
        )

        val evidence = costOf(outcome, RuntimeCost.Compile) as ResolvedEvidence.Measured
        assertEquals(0L, evidence.duration.millis)
        assertTrue(outcome is ProfileOutcome.Complete)
    }

    @Test
    fun `an inverted interval is refused and named, never a negative duration`() {
        val outcome = summarize(
            CostInput(
                successfulRun(),
                mapOf(RuntimeCost.Compile to CostSamples.Intervals(listOf(measured(200, 100)))),
            ),
        )

        assertEquals(
            ResolvedEvidence.Unavailable(UnavailableReason.InvertedInterval(200, 100)),
            costOf(outcome, RuntimeCost.Compile),
        )
        assertTrue(outcome is ProfileOutcome.Partial)
        assertTrue(
            (outcome as ProfileOutcome.Partial).reasons.contains(PartialReason.RefusedInvertedInterval(RuntimeCost.Compile)),
        )
    }

    @Test
    fun `intervals from different clocks are never added together`() {
        val outcome = summarize(
            CostInput(
                successfulRun(),
                mapOf(
                    RuntimeCost.StepDispatch to CostSamples.Intervals(
                        listOf(measured(0, 10, "run-event-occurred-at"), measured(0, 10, "jfr-thread-cpu")),
                    ),
                ),
            ),
        )

        assertEquals(
            ResolvedEvidence.Unavailable(UnavailableReason.ClockIncompatible(setOf("run-event-occurred-at", "jfr-thread-cpu"))),
            costOf(outcome, RuntimeCost.StepDispatch),
        )
        assertTrue((outcome as ProfileOutcome.Partial).reasons.any { it is PartialReason.ClockIncompatible }
        )
    }

    @Test
    fun `a series median is an observed sample, not a synthesized average`() {
        val outcome = summarize(
            CostInput(
                successfulRun(),
                mapOf(
                    RuntimeCost.StepDispatch to CostSamples.Intervals(
                        listOf(measured(0, 40), measured(100, 110), measured(200, 230), measured(300, 310)),
                    ),
                ),
            ),
        )

        val evidence = costOf(outcome, RuntimeCost.StepDispatch) as ResolvedEvidence.Series
        assertEquals(4, evidence.count)
        assertEquals(90L, evidence.totalMs)
        // Sorted durations [10, 10, 30, 40]: the lower middle, an observed value.
        assertEquals(10L, evidence.medianMs)
    }

    @Test
    fun `a run that did not succeed yields a partial profile, not a silent success`() {
        val outcome = summarize(
            CostInput(
                RunEvidence.Finished("failure", measured(0, 100)),
                mapOf(RuntimeCost.Compile to CostSamples.Intervals(listOf(measured(0, 30)))),
            ),
        )

        assertTrue(outcome is ProfileOutcome.Partial)
        assertEquals(
            PartialReason.RunDidNotSucceed("failure"),
            (outcome as ProfileOutcome.Partial).reasons.single(),
        )
    }

    @Test
    fun `a run that never finished is partial with the run still open`() {
        val outcome = summarize(
            CostInput(
                RunEvidence.Open(IntervalEstimate(0, 80, "bounded-by-last-observed-event")),
                emptyMap(),
            ),
        )

        assertTrue(outcome is ProfileOutcome.Partial)
        assertEquals(
            PartialReason.RunNotFinished,
            (outcome as ProfileOutcome.Partial).reasons.single(),
        )
    }
}
