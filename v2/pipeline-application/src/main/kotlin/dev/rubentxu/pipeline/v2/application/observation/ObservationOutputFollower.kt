package dev.rubentxu.pipeline.v2.application.observation

/**
 * Drives the output lane of the read model for a consumer that wants it all: a bounded replay, and a
 * follow loop that knows when to stop.
 *
 * ## Two operations, one loop
 *
 * [replay] and [follow] are the same iteration. Replay runs it until the tail is final and returns;
 * follow runs it and waits between passes. Splitting them would mean two implementations of "read
 * what is pending", and the two would drift — which is how a `--follow` ends up promising more than
 * `observe` delivers.
 *
 * ## It polls, and that is deliberate
 *
 * There is no wakeup source here. `ObservationWakeup` exists and coalesces correctly (OBS-D3), but
 * nothing publishes one yet, and a follower built on a signal that does not exist would be a follower
 * that never runs. So this polls, which is exactly the property the wakeup law was written to allow:
 *
 * ```text
 * a dropped wakeup costs a wakeup, never an observation
 * ```
 *
 * The follower recovers by reading the durable authority from its own cursor, so it is correct
 * whether or not it was ever signalled. Adding a signal later is a LATENCY optimisation on top of a
 * loop that is already correct, which is the only safe order in which to add one.
 *
 * ## Bounded in both directions
 *
 * [frameLimit] bounds one read and [ObservationReplayLimit.records] bounds one call. Both exist
 * because `observe` runs against runs whose output nobody measured in advance, and an unbounded drain
 * is how following a 200 MB build log becomes memory pressure rather than observation.
 */
class ObservationOutputFollower(
    private val reader: ObservationOutputReader,
    private val frameLimit: Int,
    /**
     * Whether the durable EXECUTION authority says this run reached a terminal state.
     *
     * The output plane cannot answer it. A run that finished writing nothing has no stream to seal
     * and no frame to read, so from here it is indistinguishable from a run that has not started.
     * Defaulting to `false` keeps a caller that cannot answer in the SAFE direction — keep reading —
     * and a caller that can answer must say so rather than let silence stand in for the fact.
     */
    private val runFinished: (String) -> Boolean = { false },
) {

    init {
        require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }
    }

    /**
     * Reads everything pending right now, and says whether more can still arrive.
     *
     * @param query compiled once and applied per record; `null` selects every record. A query that
     *   rejects most records does NOT shorten the drain: filtering is read-side, so the index is
     *   still traversed to its end and only then does the bound on KEPT records apply. A reader
     *   counting frames it kept would be counting a view of the run, not the run.
     * @param afterOrdinal where to resume. `-1` reads the run from its first frame.
     */
    fun replay(
        runId: String,
        afterOrdinal: Long = -1L,
        query: CompiledObservationQuery? = null,
        limit: ObservationReplayLimit = ObservationReplayLimit.DEFAULT,
    ): ObservationReplayResult {
        var ordinal = afterOrdinal
        val kept = ArrayList<ObservationRecord.Output>()

        while (true) {
            val page = when (val read = reader.readOutput(runId, ordinal, frameLimit)) {
                // A refusal is NOT "nothing there". Reporting an empty result here would let a
                // broken store look like a run that produced no output, which is the same defect the
                // sealed result type was introduced to close.
                is ObservationOutputRead.Refused -> return ObservationReplayResult.Refused(read.reason)
                is ObservationOutputRead.Page -> read.page
            }

            for (record in page.records) {
                if (query != null && !query.accepts(record)) continue
                // The bound is checked HERE, before appending, rather than after the page. Checking
                // after would let a page of `frameLimit` records overshoot the caller's budget by up
                // to a whole window, which is the difference between a bounded read and a bounded
                // LOOK.
                if (kept.size >= limit.records) {
                    // Stopped mid-page, so frames remain. The resume must be the last KEPT record,
                    // not this page's end: records skipped by the filter sit between them and
                    // resuming past them would lose them for good.
                    return ObservationReplayResult.Complete(
                        records = kept,
                        lastOrdinal = kept.last().frame.ordinal,
                        truncated = true,
                        decision = FollowDecision.ReadAgain,
                    )
                }
                kept += record
            }

            // The other exit: the index had nothing more, so this drain is COMPLETE. A drain stopped
            // by the budget and a drain that reached the end are different answers, and reporting
            // them identically is the failure `truncated` exists to prevent.
            if (!page.moreFrames) {
                return ObservationReplayResult.Complete(
                    records = kept,
                    lastOrdinal = page.lastOrdinal,
                    truncated = false,
                    decision = followDecision(
                        moreFrames = false,
                        tailStates = reader.tailStatesOf(runId),
                        runFinished = runFinished(runId),
                    ),
                )
            }
            ordinal = page.lastOrdinal
        }
    }
}

/**
 * How much one replay call may pull.
 *
 * Named rather than defaulted to a bare `Int` so that a caller writing `limit = 10000` has to say
 * what the ten thousand are for.
 */
data class ObservationReplayLimit(val records: Int) {
    init {
        require(records > 0) { "records must be positive, got $records" }
    }

    companion object {
        /**
         * A page of a live console, not the run.
         *
         * This is the number that has NOT been measured yet, and saying so is the point: the user's
         * own rule is that a budget is fixed after measuring, and `observe` is the first caller that
         * will produce the measurement. It is deliberately visible in one place so that changing it
         * after measuring is a one-line, reviewable edit rather than a hunt through call sites.
         */
        val DEFAULT = ObservationReplayLimit(records = 256)
    }
}

/** What one replay produced. */
sealed interface ObservationReplayResult {

    /**
     * Everything pending was read.
     *
     * @property truncated whether [ObservationReplayLimit.records] stopped the read before the tail
     *   was final. A truncated result is a partial answer and the caller must be able to say so.
     * @property decision whether more can still arrive. `Finished` here means the OUTPUT lane is
     *   final; it says nothing about whether the run itself ended, which is the run plane's fact.
     */
    data class Complete(
        val records: List<ObservationRecord.Output>,
        val lastOrdinal: Long,
        val truncated: Boolean,
        val decision: FollowDecision,
    ) : ObservationReplayResult

    /** The store would not answer. The output is NOT absent; it is unread. */
    data class Refused(val reason: dev.rubentxu.pipeline.v2.output.OutputRefusal) :
        ObservationReplayResult
}
