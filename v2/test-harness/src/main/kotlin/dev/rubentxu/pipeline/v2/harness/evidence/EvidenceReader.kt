package dev.rubentxu.pipeline.v2.harness.evidence

import dev.rubentxu.pipeline.v2.harness.provenance.CommitRef

/**
 * Pure decider: a [ReportObservation] plus the [ExpectedSuite] it claims to
 * satisfy becomes a [ReportDecision]. The reader never touches the filesystem.
 *
 * The rules are exhaustive; the result is always either `Valid` or `Refused`
 * with a named cause. A "fake green" — empty or all-skipped — is refused with
 * a typed cause, not silently accepted.
 *
 * The order of evaluation is fixed:
 *  1. `EMPTY`  (tests=0)
 *  2. `ALL_SKIPPED`  (skipped==tests)
 *  3. `FAILURES`
 *  4. `ERRORS`
 *  5. `WRONG_SUITE`
 *  6. `STALE_EVIDENCE`
 *  7. otherwise `Valid`
 *
 * This ordering means a single test result can fail for more than one reason,
 * but the reader reports the first one in the list — that is the cause a
 * developer can act on first.
 */
object EvidenceReader {
    fun adjudicate(
        observation: ReportObservation,
        expected: ExpectedSuite,
    ): ReportDecision {
        if (observation.tests == 0) return ReportDecision.Refused(ReportDecision.Cause.EMPTY)
        if (observation.skipped == observation.tests) {
            return ReportDecision.Refused(ReportDecision.Cause.ALL_SKIPPED)
        }
        if (observation.failures > 0) return ReportDecision.Refused(ReportDecision.Cause.FAILURES)
        if (observation.errors > 0) return ReportDecision.Refused(ReportDecision.Cause.ERRORS)
        if (observation.suiteFqcn != expected.suiteFqcn) {
            return ReportDecision.Refused(ReportDecision.Cause.WRONG_SUITE)
        }
        if (observation.reportedCommit != null && observation.reportedCommit != expected.expectedCommit) {
            return ReportDecision.Refused(ReportDecision.Cause.STALE_EVIDENCE)
        }
        return ReportDecision.Valid(
            executed = observation.tests - observation.skipped,
            skipped = observation.skipped,
        )
    }
}
