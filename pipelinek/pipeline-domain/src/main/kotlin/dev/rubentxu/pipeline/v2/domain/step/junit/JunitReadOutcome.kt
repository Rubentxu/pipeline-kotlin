package dev.rubentxu.pipeline.v2.domain.step.junit

/**
 * Closed ADT for the outcome of a single `core.junit` read.
 *
 * Two cases, both legitimate:
 *
 * - [Passed] — totals report zero failures and zero errors. The
 *   pipeline author MAY treat this as success without further
 *   inspection.
 * - [Failed] — totals report at least one failure or error AND the
 *   parser captured the failing case details. The Step itself
 *   completes (the typed FAIL is the side channel); the pipeline
 *   author decides whether to abort.
 *
 * This is NOT a `Boolean` and NOT a `String` mode. It is a closed
 * ADT because the two cases carry different typed payloads (the
 * failing-cases list) and conflating them via a flag would lose
 * information at the call site.
 *
 * Frozen decision: the Step's [dev.rubentxu.pipeline.v2.domain.CommonExecutionResult.success]
 * is TRUE for BOTH cases. The handler ran; the typed outcome is the
 * side channel. The pipeline author writes `if (report.outcome is Failed) ...`
 * to react.
 */
sealed interface JunitReadOutcome {

    /** Zero failures and zero errors across all suites. */
    data class Passed(val totals: JunitTotals) : JunitReadOutcome

    /**
     * At least one failure or error across the suites.
     *
     * @property totals       the totals (failures + errors > 0)
     * @property failingCases the failing case details captured by the parser
     */
    data class Failed(
        val totals: JunitTotals,
        val failingCases: List<FailingCase>,
    ) : JunitReadOutcome
}
