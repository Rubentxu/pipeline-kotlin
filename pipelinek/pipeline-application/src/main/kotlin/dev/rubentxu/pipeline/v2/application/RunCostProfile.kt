package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.runtime.profile.CostInput
import dev.rubentxu.pipeline.v2.runtime.profile.CostSamples
import dev.rubentxu.pipeline.v2.runtime.profile.IntervalEstimate
import dev.rubentxu.pipeline.v2.runtime.profile.MeasuredDuration
import dev.rubentxu.pipeline.v2.runtime.profile.ProfileOutcome
import dev.rubentxu.pipeline.v2.runtime.profile.ResolvedCost
import dev.rubentxu.pipeline.v2.runtime.profile.ResolvedEvidence
import dev.rubentxu.pipeline.v2.runtime.profile.UnavailableReason
import dev.rubentxu.pipeline.v2.runtime.profile.RunEvidence
import dev.rubentxu.pipeline.v2.runtime.profile.RuntimeCost

/**
 * Projects a run's persisted events into the raw input of the cost profile.
 *
 * Every duration here is a difference between two `occurredAt` stamps the run
 * already durably recorded, which is why profiling adds no work to the run
 * itself: the report reads what the run wrote anyway, and a run that produced
 * no evidence is reported as such rather than estimated.
 *
 * The fold is pure and single-pass. Started events open intervals; the
 * matching finished event closes one; anything left open at the end of the
 * stream is bounded by the last observed event, because that is the only
 * honest upper bound the stream carries.
 */
object RunCostProjection {

    /**
     * All events of one run are stamped on the run's recording clock, so all
     * derived durations share one domain and may be added together.
     */
    const val CLOCK_DOMAIN: String = "run-event-occurred-at"

    private const val OPEN_INTERVAL_SOURCE = "bounded-by-last-observed-event"

    fun project(events: List<DomainEvent>): CostInput {
        val state = EventFold()
        events.forEach(state::apply)
        return CostInput(runEvidence(state), costSamples(state))
    }

    /**
     * The single-pass fold's mutable accumulation. It lives and dies inside
     * [project]; nothing outside ever sees it mid-run.
     */
    private class EventFold {
        var runStartedAt: Long? = null
        var runFinishedAt: Long? = null
        var runOutcome: String? = null
        var compileStartedAt: Long? = null
        var lastObservedAt: Long? = null
        val compileIntervals = mutableListOf<MeasuredDuration>()
        val openSteps = ArrayDeque<OpenStep>()
        val stepIntervals = mutableListOf<MeasuredDuration>()

        fun apply(event: DomainEvent) {
            val at = event.occurredAt.toEpochMilli()
            lastObservedAt = at
            when (event) {
                is RunStarted -> if (runStartedAt == null) runStartedAt = at
                is RunFinished -> if (runFinishedAt == null) {
                    runFinishedAt = at
                    runOutcome = event.outcome
                }

                is CompilationStarted -> if (compileStartedAt == null) compileStartedAt = at
                is CompilationFinished -> {
                    val start = compileStartedAt
                    if (start != null) {
                        compileIntervals.add(measured(start, at))
                        compileStartedAt = null
                    }
                }

                is StepStarted -> openSteps.addLast(OpenStep(event.stageIndex, event.stepIndex, at))
                is StepFinished -> closeStep(event.stageIndex, event.stepIndex, at)
                is StepFailed -> closeStep(event.stageIndex, event.stepIndex, at)

                else -> {}
            }
        }

        /**
         * Close the open step the terminal event belongs to — only when the
         * event names one step unambiguously. A terminal without a stage
         * index matches by ordinal alone; with several open candidates that
         * decides nothing, so nothing is closed and the steps remain open:
         * their intervals surface as bounded estimates, which stay true, and
         * no measured duration is attributed to a step that may not be the
         * one that ended.
         */
        private fun closeStep(stageIndex: Int?, stepIndex: Int, endedAtMs: Long) {
            val candidates = openSteps.withIndex().filter { (_, open) ->
                open.stepIndex == stepIndex && (stageIndex == null || open.stageIndex == stageIndex)
            }
            when {
                candidates.size == 1 -> {
                    val open = openSteps.removeAt(candidates.single().index)
                    stepIntervals.add(measured(open.startedAtMs, endedAtMs))
                }
            }
        }
    }

    private data class OpenStep(val stageIndex: Int, val stepIndex: Int, val startedAtMs: Long)

    /** Completed intervals when every one closed; a bound when one is still open. */
    private fun completedOrBounded(intervals: List<MeasuredDuration>, openStartedAt: Long?, last: Long?): CostSamples = when {
        intervals.isNotEmpty() && openStartedAt == null -> CostSamples.Intervals(intervals)
        openStartedAt != null && last != null -> CostSamples.Estimate(
            IntervalEstimate(openStartedAt, last, OPEN_INTERVAL_SOURCE),
        )

        else -> if (intervals.isEmpty()) CostSamples.None else CostSamples.Intervals(intervals)
    }

    private fun costSamples(state: EventFold): Map<RuntimeCost, CostSamples> {
        val last = state.lastObservedAt
        val costs = mutableMapOf(
            RuntimeCost.Compile to completedOrBounded(state.compileIntervals, state.compileStartedAt, last),
            // Every open step is bounded by the same last event; each keeps its
            // own start, because overlapping steps are distinct facts.
            RuntimeCost.StepDispatch to completedOrBounded(
                state.stepIntervals,
                state.openSteps.firstOrNull()?.startedAtMs,
                last,
            ),
        )
        for (cost in RuntimeCost.all) costs.putIfAbsent(cost, CostSamples.None)
        return costs
    }

    private fun runEvidence(state: EventFold): RunEvidence {
        val last = state.lastObservedAt
        val startedAt = state.runStartedAt
        val finishedAt = state.runFinishedAt
        val outcome = state.runOutcome
        return when {
            startedAt != null && finishedAt != null && outcome != null ->
                RunEvidence.Finished(outcome, measured(startedAt, finishedAt))

            startedAt != null && last != null ->
                RunEvidence.Open(IntervalEstimate(startedAt, last, OPEN_INTERVAL_SOURCE))

            else -> RunEvidence.NoRunBounds
        }
    }

    private fun measured(startAtMs: Long, endAtMs: Long): MeasuredDuration =
        MeasuredDuration(startAtMs, endAtMs, CLOCK_DOMAIN)
}

/**
 * Renders the cost profile as stable operator-facing text. The report carries
 * identifiers, counts and milliseconds only: no script paths, no step names,
 * no transcripts — nothing a run's payload could leak through its own timing
 * report.
 */
object RunCostReport {

    fun textLines(runId: String, outcome: ProfileOutcome): List<String> {
        val head = when (outcome) {
            is ProfileOutcome.Complete -> "profile run=$runId outcome=complete"
            is ProfileOutcome.Partial -> {
                val reasons = outcome.reasons.joinToString(",") { it.id }
                "profile run=$runId outcome=partial reasons=$reasons"
            }
        }
        return listOf(head) + outcome.costs.map { cost -> "  ${row(cost)}" }
    }

    fun jsonLines(runId: String, outcome: ProfileOutcome): List<String> {
        val rows = outcome.costs.joinToString(",\n") { cost ->
            val evidence = when (val e = cost.evidence) {
                is ResolvedEvidence.Measured ->
                    """{"state":"measured","startMs":${e.duration.startAtMs},"endMs":${e.duration.endAtMs}}"""

                is ResolvedEvidence.Series ->
                    """{"state":"series","count":${e.count},"totalMs":${e.totalMs},"medianMs":${e.medianMs}}"""

                is ResolvedEvidence.Estimated ->
                    """{"state":"estimated","lowerMs":${e.estimate.lowerAtMs},"upperMs":${e.estimate.upperAtMs}}"""

                is ResolvedEvidence.Unavailable -> """{"state":"unavailable","reason":"${reason(e.reason)}"}"""
            }
            """    {"cost":"${cost.cost.id}","evidence":$evidence}"""
        }
        val result = when (outcome) {
            is ProfileOutcome.Complete -> "\"complete\""
            is ProfileOutcome.Partial ->
                "\"partial\",\"reasons\":[${outcome.reasons.joinToString(",") { "\"${it.id}\"" }}]"
        }
        return listOf(
            "{\"schema\":\"runtime.profile.v1\",\"runId\":\"$runId\",\"outcome\":$result,",
            " \"costs\":[",
            rows,
            " ]}",
        )
    }

    private fun row(cost: ResolvedCost): String {
        val body = when (val e = cost.evidence) {
            is ResolvedEvidence.Measured -> "measured ${e.duration.millis} ms"
            is ResolvedEvidence.Series -> "series n=${e.count} total=${e.totalMs} ms median=${e.medianMs} ms"
            is ResolvedEvidence.Estimated -> "bounded >=${e.estimate.windowMillis} ms (${e.estimate.source})"
            is ResolvedEvidence.Unavailable -> "unavailable ${reason(e.reason)}"
        }
        return "${cost.cost.id.padEnd(14)} $body"
    }

    private fun reason(reason: UnavailableReason): String = when (reason) {
        UnavailableReason.NoDurationEvidence -> "no-duration-evidence"
        UnavailableReason.NotCapturedInThisBuild -> "not-captured-in-this-build"
        is UnavailableReason.InvertedInterval -> "inverted-interval"
        is UnavailableReason.ClockIncompatible -> "clock-incompatible"
    }
}
