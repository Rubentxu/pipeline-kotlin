package dev.rubentxu.pipeline.v2.runtime.recover

/**
 * Knobs for [RuntimeRecoverPort.recover].
 * The default mirrors the existing production policy: NOT a dry run. A consumer
 * that wants to ask "what would happen?" sets `dryRun = true`; the port computes
 * the decision without writing and returns the typed outcome.
 * `lagReportIntervalMs` is a reporting-only threshold; the port reports observed
 * Lag at this interval. NOT a refusal trigger. Mirrors /
*/
data class RecoverOptions(
    /** When true, the port computes the decision without writing. */
    val dryRun: Boolean = false,
    /**
     * Reporting-only threshold; the port reports observed lag at this interval.
     * NOT a refusal trigger. Defaults to 1 second.
*/
    val lagReportIntervalMs: Long = 1_000L,
) {
    init {
        require(lagReportIntervalMs >= 0) {
            "lagReportIntervalMs must be non-negative, got $lagReportIntervalMs"
        }
    }

    /**
     * The shipped policy for a recovery.
     *
     * The default is a real recovery, not a rehearsal: a caller that wants
     * to see what a recovery would decide without touching the run builds
     * its own options with `dryRun = true` rather than asking for a
     * different default.
     */
    companion object {
        /** The default options: not a dry run. */
        val Default: RecoverOptions = RecoverOptions()
    }
}
