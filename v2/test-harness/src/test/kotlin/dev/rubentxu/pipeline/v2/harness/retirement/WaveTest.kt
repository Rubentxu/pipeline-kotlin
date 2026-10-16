package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaveTest {

    private val sha = "a".repeat(64)
    private fun rec(
        runnerHint: RunnerHint = RunnerHint.V2_GRADLE,
        kindHint: KindHint = KindHint.OTHER_TEST_SOURCE,
        observedProcessApi: String = "",
        releaseScaleTag: Boolean = false,
    ) = TestRecord(
        path = "v2/x/src/test/kotlin/SampleTest.kt",
        kindHint = kindHint,
        runnerHint = runnerHint,
        observedProcessApi = observedProcessApi,
        testAnnotated = 1,
        disabledAnnotations = 0,
        releaseScaleTag = releaseScaleTag,
        contentSha256 = sha,
    )

    @Test
    fun `V1 runner is Wave D`() {
        assertEquals(Wave.D, Wave.forRecord(rec(runnerHint = RunnerHint.LEGACY_NOT_DEFAULT_V2)))
    }

    @Test
    fun `ProcessBuilder observed is Wave C`() {
        assertEquals(Wave.C, Wave.forRecord(rec(observedProcessApi = "ProcessBuilder(")))
    }

    @Test
    fun `AppBinSupport observed is Wave C`() {
        assertEquals(Wave.C, Wave.forRecord(rec(observedProcessApi = "AppBinSupport")))
    }

    @Test
    fun `OwnedSubprocess observed is Wave C`() {
        assertEquals(Wave.C, Wave.forRecord(rec(observedProcessApi = "OwnedSubprocess")))
    }

    @Test
    fun `release-scale tag is Wave B`() {
        assertEquals(Wave.B, Wave.forRecord(rec(releaseScaleTag = true)))
    }

    @Test
    fun `explicit UAT E2E name is Wave B`() {
        assertEquals(Wave.B, Wave.forRecord(rec(kindHint = KindHint.EXPLICIT_UAT_E2E)))
    }

    @Test
    fun `default is Wave A`() {
        assertEquals(Wave.A, Wave.forRecord(rec()))
    }

    @Test
    fun `V1 takes precedence over process API`() {
        // V1 with ProcessBuilder is still Wave D (the lower-fidelity
        // authority wins; the certifier is not relevant for V1).
        assertEquals(
            Wave.D,
            Wave.forRecord(
                rec(runnerHint = RunnerHint.LEGACY_NOT_DEFAULT_V2, observedProcessApi = "ProcessBuilder("),
            ),
        )
    }

    @Test
    fun `process API takes precedence over release-scale tag`() {
        assertEquals(
            Wave.C,
            Wave.forRecord(rec(observedProcessApi = "ProcessBuilder(", releaseScaleTag = true)),
        )
    }
}

class DeletionGuardWaveTest {

    private val sha = "b".repeat(64)
    private fun rec(
        runnerHint: RunnerHint = RunnerHint.V2_GRADLE,
        kindHint: KindHint = KindHint.OTHER_TEST_SOURCE,
        observedProcessApi: String = "",
        releaseScaleTag: Boolean = false,
    ) = TestRecord(
        path = "v2/x/src/test/kotlin/SampleTest.kt",
        kindHint = kindHint,
        runnerHint = runnerHint,
        observedProcessApi = observedProcessApi,
        testAnnotated = 1,
        disabledAnnotations = 0,
        releaseScaleTag = releaseScaleTag,
        contentSha256 = sha,
    )

    @Test
    fun `Wave A retires with any non-blank authority`() {
        val record = rec()
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "ci-runner")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave B retires with any non-blank authority`() {
        val record = rec(releaseScaleTag = true)
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "ci-runner")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave C demands a sandbox-grade authority`() {
        val record = rec(observedProcessApi = "ProcessBuilder(")
        val bad = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "ci-runner")
        val outcome = DeletionGuard.check(bad, record, retirementAuthorised = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    @Test
    fun `Wave C accepts sandbox authority`() {
        val record = rec(observedProcessApi = "ProcessBuilder(")
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "sandbox")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave C accepts release-harness authority`() {
        val record = rec(observedProcessApi = "AppBinSupport")
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "release-harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave C accepts harness authority`() {
        val record = rec(observedProcessApi = "OwnedSubprocess")
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave D refuses when retirementAuthorisedD is false`() {
        val record = rec(runnerHint = RunnerHint.LEGACY_NOT_DEFAULT_V2)
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "sandbox")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true, retirementAuthorisedD = false)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    @Test
    fun `Wave D authorises when retirementAuthorisedD is true`() {
        val record = rec(runnerHint = RunnerHint.LEGACY_NOT_DEFAULT_V2)
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "sandbox")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = false, retirementAuthorisedD = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave C refuses when retirementAuthorised is false (regardless of D)`() {
        val record = rec(observedProcessApi = "ProcessBuilder(")
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "sandbox")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = false, retirementAuthorisedD = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }
}
