package dev.rubentxu.pipeline.v2.runtime.recover

/**
 * M2 — knobs for [RuntimeRecoverPort.recover].
 *
 * The default mirrors the existing production policy: NOT a dry run. A consumer
 * that wants to ask "what would happen?" sets `dryRun = true`; the port computes
 * the decision without writing and returns the typed outcome.
 *
 * `lagReportIntervalMs` is a reporting-only threshold; the port reports observed
 * lag at this interval. NOT a refusal trigger. Mirrors M1 §2.2 / §3.3.
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

    companion object {
        /** The default options: not a dry run. */
        val Default: RecoverOptions = RecoverOptions()
    }
}
