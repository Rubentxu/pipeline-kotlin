package dev.rubentxu.pipeline.v2.harness.evidence

/**
 * The verdict of the evidence decider. Closed: no "default" branch. Every
 * cause is named so the caller can decide whether to retry, refresh, or
 * fail.
 */
sealed interface ReportDecision {
    /** The report satisfies the contract: non-empty, not all skipped, no failure/error, right suite, right commit. */
    data class Valid(
        val executed: Int,
        val skipped: Int,
    ) : ReportDecision {
        init {
            require(executed > 0) { "Valid must have at least one executed case" }
            require(skipped in 0 until executed) { "skipped must be 0..executed-1" }
        }
    }

    /** The report does not satisfy the contract. The cause names the rule that failed. */
    data class Refused(val cause: Cause) : ReportDecision

    enum class Cause {
        /** Report had `tests=0`. A TEST-*.xml with no cases is not a real run. */
        EMPTY,

        /** Report had `tests=skipped`. A 100% skipped run is not a real run. */
        ALL_SKIPPED,

        /** Report had at least one failure. */
        FAILURES,

        /** Report had at least one error. */
        ERRORS,

        /** Report was corrupted (unparseable XML, missing testsuite, etc.). */
        CORRUPT,

        /** Report's suite FQCN did not match the expected contract. */
        WRONG_SUITE,

        /** Report's invocation token did not match the current run. */
        WRONG_INVOCATION,

        /** Report's commit reference did not match the expected one. */
        STALE_EVIDENCE,
    }
}
