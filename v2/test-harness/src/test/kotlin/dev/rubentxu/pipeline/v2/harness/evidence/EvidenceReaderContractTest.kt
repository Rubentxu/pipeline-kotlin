package dev.rubentxu.pipeline.v2.harness.evidence

import dev.rubentxu.pipeline.v2.harness.catalog.ContractId
import dev.rubentxu.pipeline.v2.harness.provenance.CommitRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for [EvidenceReader]. Each test is one of the four AAT-BQ1
 * mutations (EMPTY, ALL_SKIPPED, FAILURES, ERRORS) plus the provenance
 * (STALE_EVIDENCE) and suite-mismatch (WRONG_SUITE) paths.
 */
class EvidenceReaderContractTest {

    private val observedCommit = CommitRef("0123456789abcdef0123456789abcdef01234567")
    private val expected = ExpectedSuite(
        contract = ContractId("contract.x"),
        suiteFqcn = "owner.ContractTest",
        expectedCommit = observedCommit,
        invocation = "build-1",
    )

    @Test
    fun `AAT-BQ1-01 empty report is Refused EMPTY`() {
        val observation = ReportObservation(
            suiteFqcn = "owner.ContractTest",
            tests = 0,
            skipped = 0,
            failures = 0,
            errors = 0,
            reportedCommit = observedCommit,
        )
        val decision = EvidenceReader.adjudicate(observation, expected)
        assertEquals(ReportDecision.Refused(ReportDecision.Cause.EMPTY), decision)
    }

    @Test
    fun `AAT-BQ1-02 all-skipped report is Refused ALL_SKIPPED`() {
        val observation = ReportObservation(
            suiteFqcn = "owner.ContractTest",
            tests = 12,
            skipped = 12,
            failures = 0,
            errors = 0,
            reportedCommit = observedCommit,
        )
        val decision = EvidenceReader.adjudicate(observation, expected)
        assertEquals(ReportDecision.Refused(ReportDecision.Cause.ALL_SKIPPED), decision)
    }

    @Test
    fun `AAT-BQ1-04 stale commit is Refused STALE_EVIDENCE`() {
        val observation = ReportObservation(
            suiteFqcn = "owner.ContractTest",
            tests = 4,
            skipped = 0,
            failures = 0,
            errors = 0,
            reportedCommit = CommitRef("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        )
        val decision = EvidenceReader.adjudicate(observation, expected)
        assertEquals(ReportDecision.Refused(ReportDecision.Cause.STALE_EVIDENCE), decision)
    }

    @Test
    fun `wrong suite is Refused WRONG_SUITE`() {
        val observation = ReportObservation(
            suiteFqcn = "owner.OtherTest",
            tests = 4,
            skipped = 0,
            failures = 0,
            errors = 0,
            reportedCommit = observedCommit,
        )
        val decision = EvidenceReader.adjudicate(observation, expected)
        assertEquals(ReportDecision.Refused(ReportDecision.Cause.WRONG_SUITE), decision)
    }

    @Test
    fun `failures are Refused FAILURES`() {
        val observation = ReportObservation(
            suiteFqcn = "owner.ContractTest",
            tests = 4,
            skipped = 0,
            failures = 1,
            errors = 0,
            reportedCommit = observedCommit,
        )
        val decision = EvidenceReader.adjudicate(observation, expected)
        assertEquals(ReportDecision.Refused(ReportDecision.Cause.FAILURES), decision)
    }

    @Test
    fun `errors are Refused ERRORS`() {
        val observation = ReportObservation(
            suiteFqcn = "owner.ContractTest",
            tests = 4,
            skipped = 0,
            failures = 0,
            errors = 1,
            reportedCommit = observedCommit,
        )
        val decision = EvidenceReader.adjudicate(observation, expected)
        assertEquals(ReportDecision.Refused(ReportDecision.Cause.ERRORS), decision)
    }

    @Test
    fun `clean report is Valid`() {
        val observation = ReportObservation(
            suiteFqcn = "owner.ContractTest",
            tests = 10,
            skipped = 1,
            failures = 0,
            errors = 0,
            reportedCommit = observedCommit,
        )
        val decision = EvidenceReader.adjudicate(observation, expected)
        assertTrue(decision is ReportDecision.Valid) { "expected Valid, got $decision" }
        val valid = decision as ReportDecision.Valid
        assertEquals(9, valid.executed)
        assertEquals(1, valid.skipped)
    }

    @Test
    fun `null reported commit accepts the expected commit`() {
        val observation = ReportObservation(
            suiteFqcn = "owner.ContractTest",
            tests = 3,
            skipped = 0,
            failures = 0,
            errors = 0,
            reportedCommit = null,
        )
        val decision = EvidenceReader.adjudicate(observation, expected)
        assertTrue(decision is ReportDecision.Valid) { "expected Valid, got $decision" }
    }
}
