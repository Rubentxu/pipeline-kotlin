package dev.rubentxu.pipeline.v2.runtime.profile

/**
 * The kinds of work a pipeline run spends time on. The enumeration is closed
 * on purpose: a cost that cannot be named cannot be reported, and a cost that
 * is named but never measured is reported as absent, never as zero.
 */
sealed interface RuntimeCost {
    val id: String

    /** Turning script source into a compiled, evaluable program. */
    data object Compile : RuntimeCost {
        override val id: String get() = "compile"
    }

    /** Deciding the stage and step graph after compilation. */
    data object Plan : RuntimeCost {
        override val id: String get() = "plan"
    }

    /** Executing steps, from each step's start to its terminal event. */
    data object StepDispatch : RuntimeCost {
        override val id: String get() = "step-dispatch"
    }

    /** Committing events to the durable store. */
    data object EventCommit : RuntimeCost {
        override val id: String get() = "event-commit"
    }

    /** Committing output bytes to the durable output plane. */
    data object OutputCommit : RuntimeCost {
        override val id: String get() = "output-commit"
    }

    /** Time an observer spent waiting for new run evidence. */
    data object FollowDelay : RuntimeCost {
        override val id: String get() = "follow-delay"
    }

    /** Re-establishing a run's position after a resume or replay. */
    data object Recovery : RuntimeCost {
        override val id: String get() = "recovery"
    }

    /**
     * The catalogue every report is built from.
     *
     * [all] is the closed set of costs the summarizer measures: a report
     * lists exactly these, so a cost that nothing was measured for still
     * appears — absent rather than missing.
     */
    companion object {
        /** Declaration order; every report lists exactly these costs. */
        val all: List<RuntimeCost> = listOf(
            Compile, Plan, StepDispatch, EventCommit, OutputCommit, FollowDelay, Recovery,
        )
    }
}

/**
 * Why a cost carries no duration. Each reason is a different fact, and none of
 * them means zero milliseconds: "not measured" and "measured as zero" answer
 * different questions, and only the second may print a number.
 */
sealed interface UnavailableReason {
    /** The run produced no evidence from which a duration could be derived. */
    data object NoDurationEvidence : UnavailableReason

    /** This build captures no evidence for the cost at all. */
    data object NotCapturedInThisBuild : UnavailableReason

    /** The recorded interval ends before it starts; the timestamps disagree. */
    data class InvertedInterval(val startAtMs: Long, val endAtMs: Long) : UnavailableReason

    /** The samples were taken with clocks that cannot be compared. */
    data class ClockIncompatible(val domains: Set<String>) : UnavailableReason
}

/**
 * A closed interval measured with one clock. The type is total: an inverted
 * pair is a value the summarizer refuses, not an exception or a silent
 * negative duration.
 */
data class MeasuredDuration(
    val startAtMs: Long,
    val endAtMs: Long,
    val clockDomain: String,
) {
    val inverted: Boolean get() = endAtMs < startAtMs
    val millis: Long get() = endAtMs - startAtMs
}

/**
 * Bounds on an interval whose exact extent is unknown. Used when work started
 * and no terminal event ever arrived: the start is a fact, the end is only
 * known to be at least the last observed event.
 */
data class IntervalEstimate(
    val lowerAtMs: Long,
    val upperAtMs: Long,
    val source: String,
) {
    val inverted: Boolean get() = upperAtMs < lowerAtMs
    val windowMillis: Long get() = upperAtMs - lowerAtMs
}

/**
 * Raw evidence for one cost, before any decision is made about it.
 */
sealed interface CostSamples {
    /** Zero or more measured intervals of the same kind of work. */
    data class Intervals(val intervals: List<MeasuredDuration>) : CostSamples

    /** Work that opened but never closed, bounded by the last observed event. */
    data class Estimate(val estimate: IntervalEstimate) : CostSamples

    /** No evidence of any kind reached the summarizer. */
    data object None : CostSamples
}

/**
 * What the run itself says about its own lifecycle, independent of any cost.
 */
sealed interface RunEvidence {
    /** The run started and finished; [window] spans both events. */
    data class Finished(val outcome: String, val window: MeasuredDuration) : RunEvidence

    /** The run started and never finished; [window] is bounded by the last observed event. */
    data class Open(val window: IntervalEstimate) : RunEvidence

    /** Neither boundary was observed. */
    data object NoRunBounds : RunEvidence
}

/**
 * Why a profile is partial rather than complete. A partial profile is a
 * truthful report with named holes; completeness is only claimed when the run
 * succeeded and every captured interval closed coherently.
 */
sealed interface PartialReason {
    val id: String

    /**
     * The run started and never reached a terminal event.
     *
     * The run's own window is open, so no completeness can be claimed for a
     * run whose end was never observed; the costs are reported on the
     * evidence there is.
     */
    data object RunNotFinished : PartialReason {
        override val id: String get() = "run-not-finished"
    }

    /**
     * The run finished, with an outcome other than `success`.
     *
     * The end of the run was observed, so the costs may well be measured;
     * what the profile cannot be is complete, because a run that did not
     * succeed never reached some of the work. The outcome is the run's own
     * word for how it ended.
     */
    data class RunDidNotSucceed(val outcome: String) : PartialReason {
        override val id: String get() = "run-did-not-succeed"
    }

    /**
     * Neither the start nor the end of the run was observed.
     *
     * With no run window there is no interval to compare against, so every
     * cost stands on its own evidence and the profile is partial by
     * construction.
     */
    data object NoRunBounds : PartialReason {
        override val id: String get() = "no-run-bounds"
    }

    /**
     * The cost opened an interval that never closed.
     *
     * The work is reported as bounded rather than measured, and the hole is
     * named so a reader can tell an open interval from an absent one.
     */
    data class DanglingInterval(val cost: RuntimeCost) : PartialReason {
        override val id: String get() = "dangling-interval"
    }

    /**
     * An interval of the cost ended before it started.
     *
     * The timestamps disagree, so that interval carries no duration: the
     * cost keeps the intervals that do agree and the report says one was
     * dropped, rather than printing a negative number.
     */
    data class RefusedInvertedInterval(val cost: RuntimeCost) : PartialReason {
        override val id: String get() = "refused-inverted-interval"
    }

    /**
     * The cost's samples were taken with clocks that cannot be compared.
     *
     * A stretch of time across two clock domains is not a stretch of time, so
     * the whole set of samples for that cost is refused as one, and the
     * domains are named so a reader can see which clocks were mixed.
     */
    data class ClockIncompatible(val cost: RuntimeCost, val domains: Set<String>) : PartialReason {
        override val id: String get() = "clock-incompatible"
    }
}

/** One cost's row in the report, after the summarizer's decision. */
data class ResolvedCost(val cost: RuntimeCost, val evidence: ResolvedEvidence)

/**
 * What the summarizer concluded about one cost.
 *
 * Every case answers the same question — how long the cost took — in a way
 * the report can print: a single measurement, a series of them, a bound, or a
 * named refusal. A cost never carries a duration that was not observed.
 */
sealed interface ResolvedEvidence {
    /** Exactly one interval was measured. */
    data class Measured(val duration: MeasuredDuration) : ResolvedEvidence

    /**
     * Several intervals of the same cost, all on one clock. The median is an
     * observed sample — the lower of the two middle values when the count is
     * even — so no number in the report is synthesized rather than seen.
     */
    data class Series(
        val count: Int,
        val totalMs: Long,
        val medianMs: Long,
        val clockDomain: String,
    ) : ResolvedEvidence

    /** Bounds on work that never closed; the run is partial by construction. */
    data class Estimated(val estimate: IntervalEstimate) : ResolvedEvidence

    /**
     * The cost carries no duration, and the reason says why.
     *
     * The refusal is never a zero: "not measured" and "measured as zero"
     * answer different questions, and only the second may print a number.
     */
    data class Unavailable(val reason: UnavailableReason) : ResolvedEvidence
}

/**
 * The summarizer's input: the run's own lifecycle evidence plus raw samples
 * per cost. Everything here is observed data; every judgment lives in
 * [summarize].
 */
data class CostInput(val run: RunEvidence, val costs: Map<RuntimeCost, CostSamples>)

/**
 * How much of the profile is actually measured. A run that ended cleanly
 * and a profile whose costs were observed are two different facts: a
 * finished run can leave most costs with no evidence, and calling that
 * profile "complete" tells the reader the measurements are whole when only
 * the run is. Bounded costs are counted apart from measured ones — an
 * estimate is honest, but it is not a measurement.
 */
data class MeasurementCoverage(
    val measured: List<RuntimeCost>,
    val bounded: List<RuntimeCost>,
    val withoutEvidence: List<RuntimeCost>,
) {
    val total: Int get() = measured.size + bounded.size + withoutEvidence.size
}

/** The report: every declared cost appears exactly once, measured or not. */
sealed interface ProfileOutcome {
    val run: RunEvidence
    val costs: List<ResolvedCost>
    val coverage: MeasurementCoverage

    /** The run ended successfully with no refused evidence; the coverage says how much was measured. */
    data class Complete(
        override val run: RunEvidence.Finished,
        override val costs: List<ResolvedCost>,
        override val coverage: MeasurementCoverage,
    ) : ProfileOutcome

    /** Something about the run or the evidence is incomplete; the reasons name what. */
    data class Partial(
        override val run: RunEvidence,
        override val costs: List<ResolvedCost>,
        val reasons: List<PartialReason>,
        override val coverage: MeasurementCoverage,
    ) : ProfileOutcome
}

/**
 * Turn raw evidence into a report, refusing everything that cannot be added up
 * honestly. Pure: no clock, no store, no I/O. The rules, in order:
 *
 *  - an absent cost reports `NoDurationEvidence`, never zero;
 *  - samples on different clocks are refused as a whole for that cost;
 *  - inverted intervals are dropped and named, never turned into negative
 *    durations, and a cost whose every interval is inverted is unavailable;
 *  - a valid zero-length interval is a measurement of zero, not an absence;
 *  - an open interval is reported as bounded, and makes the profile partial;
 *  - completeness requires a successful, finished run and no refused evidence.
 */
fun summarize(input: CostInput): ProfileOutcome {
    val reasons = mutableListOf<PartialReason>()
    val costs = RuntimeCost.all.map { cost ->
        ResolvedCost(cost, resolve(input.costs[cost] ?: CostSamples.None, cost, reasons))
    }
    val coverage = MeasurementCoverage(
        measured = costs.filter { it.evidence is ResolvedEvidence.Measured || it.evidence is ResolvedEvidence.Series }
            .map { it.cost },
        bounded = costs.filter { it.evidence is ResolvedEvidence.Estimated }.map { it.cost },
        withoutEvidence = costs.filter { it.evidence is ResolvedEvidence.Unavailable }.map { it.cost },
    )
    when (val run = input.run) {
        is RunEvidence.Finished ->
            if (run.outcome != "success") reasons.add(PartialReason.RunDidNotSucceed(run.outcome))

        is RunEvidence.Open -> reasons.add(PartialReason.RunNotFinished)
        RunEvidence.NoRunBounds -> reasons.add(PartialReason.NoRunBounds)
    }
    return if (input.run is RunEvidence.Finished &&
        reasons.isEmpty()
    ) {
        ProfileOutcome.Complete(input.run, costs, coverage)
    } else {
        ProfileOutcome.Partial(input.run, costs, reasons.toList(), coverage)
    }
}

private fun resolve(samples: CostSamples, cost: RuntimeCost, reasons: MutableList<PartialReason>): ResolvedEvidence = when (samples) {
    CostSamples.None -> ResolvedEvidence.Unavailable(UnavailableReason.NoDurationEvidence)

    is CostSamples.Estimate -> {
        val estimate = samples.estimate
        if (estimate.inverted) {
            reasons.add(PartialReason.RefusedInvertedInterval(cost))
            ResolvedEvidence.Unavailable(UnavailableReason.InvertedInterval(estimate.lowerAtMs, estimate.upperAtMs))
        } else {
            reasons.add(PartialReason.DanglingInterval(cost))
            ResolvedEvidence.Estimated(estimate)
        }
    }

    is CostSamples.Intervals -> resolveIntervals(samples.intervals, cost, reasons)
}

private fun resolveIntervals(intervals: List<MeasuredDuration>, cost: RuntimeCost, reasons: MutableList<PartialReason>): ResolvedEvidence {
    if (intervals.isEmpty()) {
        return ResolvedEvidence.Unavailable(UnavailableReason.NoDurationEvidence)
    }
    val domains = intervals.map { it.clockDomain }.toSet()
    if (domains.size > 1) {
        reasons.add(PartialReason.ClockIncompatible(cost, domains))
        return ResolvedEvidence.Unavailable(UnavailableReason.ClockIncompatible(domains))
    }
    val valid = intervals.filterNot { it.inverted }
    if (valid.size < intervals.size) reasons.add(PartialReason.RefusedInvertedInterval(cost))
    if (valid.isEmpty()) {
        val first = intervals.first()
        return ResolvedEvidence.Unavailable(UnavailableReason.InvertedInterval(first.startAtMs, first.endAtMs))
    }
    val domain = domains.first()
    if (valid.size == 1) return ResolvedEvidence.Measured(valid.single())
    val sorted = valid.map { it.millis }.sorted()
    return ResolvedEvidence.Series(
        count = valid.size,
        totalMs = sorted.sum(),
        medianMs = sorted[(valid.size - 1) / 2],
        clockDomain = domain,
    )
}
