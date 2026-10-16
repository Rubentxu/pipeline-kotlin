package dev.rubentxu.pipeline.v2.harness.evidence

import dev.rubentxu.pipeline.v2.harness.catalog.ContractId
import dev.rubentxu.pipeline.v2.harness.provenance.CommitRef

/**
 * A parsed JUnit TEST-*.xml, ready to be adjudicated.
 *
 * The reader is the only place that knows about JUnit XML. Everything else
 * works on this typed projection.
 */
data class ReportObservation(
    val suiteFqcn: String,
    val tests: Int,
    val skipped: Int,
    val failures: Int,
    val errors: Int,
    val reportedCommit: CommitRef?,
) {
    init {
        require(suiteFqcn.isNotBlank()) { "suiteFqcn must not be blank" }
        require(tests >= 0) { "tests must be non-negative" }
        require(skipped in 0..tests) { "skipped must be 0..tests" }
        require(failures >= 0) { "failures must be non-negative" }
        require(errors >= 0) { "errors must be non-negative" }
    }

    /** A report that has no executable cases is a "fake green". */
    val isEmpty: Boolean get() = tests == 0
    /** A report that only contains skipped cases is also a "fake green". */
    val isAllSkipped: Boolean get() = tests > 0 && skipped == tests
    /** A report that executed some cases with no failure or error. */
    val isClean: Boolean get() = !isEmpty && !isAllSkipped && failures == 0 && errors == 0
}

/**
 * The contract a report must satisfy. The decider turns an observation into
 * a decision; the result tells the caller what to do next.
 */
data class ExpectedSuite(
    val contract: ContractId,
    val suiteFqcn: String,
    val expectedCommit: CommitRef,
    val invocation: String,
) {
    init {
        require(suiteFqcn.isNotBlank()) { "ExpectedSuite.suiteFqcn must not be blank" }
        require(invocation.isNotBlank()) { "invocation must not be blank" }
    }
}
