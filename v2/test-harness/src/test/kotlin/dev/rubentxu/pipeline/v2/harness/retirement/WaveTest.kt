package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaveTest {

    private val sha = "a".repeat(64)
    private fun rec(
        runnerHint: RunnerHint = RunnerHint.V2_GRADLE,
        kindHint: KindHint = KindHint.OTHER_TEST_SOURCE,
        observedProcessApis: List<ObservedProcessApi> = emptyList(),
        releaseScaleTag: Boolean = false,
    ) = TestRecord(
        path = "v2/x/src/test/kotlin/SampleTest.kt",
        kindHint = kindHint,
        runnerHint = runnerHint,
        observedProcessApis = observedProcessApis,
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
        assertEquals(Wave.C, Wave.forRecord(rec(observedProcessApis = listOf(ObservedProcessApi.ProcessBuilder))))
    }

    @Test
    fun `AppBinSupport observed is Wave C`() {
        assertEquals(Wave.C, Wave.forRecord(rec(observedProcessApis = listOf(ObservedProcessApi.AppBinSupport))))
    }

    @Test
    fun `OwnedSubprocess observed is Wave C`() {
        assertEquals(Wave.C, Wave.forRecord(rec(observedProcessApis = listOf(ObservedProcessApi.OwnedSubprocess))))
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
                rec(
                    runnerHint = RunnerHint.LEGACY_NOT_DEFAULT_V2,
                    observedProcessApis = listOf(ObservedProcessApi.ProcessBuilder),
                ),
            ),
        )
    }

    @Test
    fun `process API takes precedence over release-scale tag`() {
        assertEquals(
            Wave.C,
            Wave.forRecord(
                rec(
                    observedProcessApis = listOf(ObservedProcessApi.ProcessBuilder),
                    releaseScaleTag = true,
                ),
            ),
        )
    }
}

class DeletionGuardWaveTest {

    private val sha = "b".repeat(64)
    private fun rec(
        runnerHint: RunnerHint = RunnerHint.V2_GRADLE,
        kindHint: KindHint = KindHint.OTHER_TEST_SOURCE,
        observedProcessApis: List<ObservedProcessApi> = emptyList(),
        releaseScaleTag: Boolean = false,
    ) = TestRecord(
        path = "v2/x/src/test/kotlin/SampleTest.kt",
        kindHint = kindHint,
        runnerHint = runnerHint,
        observedProcessApis = observedProcessApis,
        testAnnotated = 1,
        disabledAnnotations = 0,
        releaseScaleTag = releaseScaleTag,
        contentSha256 = sha,
    )

    @Test
    fun `Wave A retires with any non-blank authority`() {
        val record = rec()
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "ci-runner")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave B retires with any non-blank authority`() {
        val record = rec(releaseScaleTag = true)
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "ci-runner")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave C demands a sandbox-grade authority`() {
        val record = rec(observedProcessApis = listOf(ObservedProcessApi.ProcessBuilder))
        val bad = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "ci-runner")
        val outcome = DeletionGuard.check(bad, record, retirementAuthorised = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    @Test
    fun `Wave C accepts sandbox authority`() {
        val record = rec(observedProcessApis = listOf(ObservedProcessApi.ProcessBuilder))
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "sandbox")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave C accepts release-harness authority`() {
        val record = rec(observedProcessApis = listOf(ObservedProcessApi.AppBinSupport))
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "release-harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave C accepts harness authority`() {
        val record = rec(observedProcessApis = listOf(ObservedProcessApi.OwnedSubprocess))
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave D refuses when retirementAuthorisedD is false`() {
        val record = rec(runnerHint = RunnerHint.LEGACY_NOT_DEFAULT_V2)
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "sandbox")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true, retirementAuthorisedD = false)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    @Test
    fun `Wave D authorises when retirementAuthorisedD is true`() {
        val record = rec(runnerHint = RunnerHint.LEGACY_NOT_DEFAULT_V2)
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "sandbox")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = false, retirementAuthorisedD = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `Wave C refuses when retirementAuthorised is false (regardless of D)`() {
        val record = rec(observedProcessApis = listOf(ObservedProcessApi.ProcessBuilder))
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = "b".repeat(64), authority = "sandbox")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = false, retirementAuthorisedD = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    // ---- the two-digest invariant (BQ5 audit fix) ----
    // `proofDigest` is the substitute evidence's hash and is NOT
    // expected to equal the file's `contentSha256`. The guard
    // checks `record.contentSha256 == originalFileSha256` (file
    // identity) and never compares `proofDigest` to the file's
    // hash. The previous code conflated the two identities and
    // refused every legitimate retirement.

    @Test
    fun `proof digest may differ from file sha (substitute evidence is a separate identity)`() {
        val record = rec()
        // file sha = sha, proof digest = "b" * 64 (deliberately different)
        val d = DeletionDecision.RedundantWithProof(
            originalFileSha256 = sha,
            proofDigest = "b".repeat(64),
            authority = "ci-runner",
        )
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `originalFileSha256 must match the record contentSha256`() {
        val record = rec()
        val d = DeletionDecision.RedundantWithProof(
            originalFileSha256 = "f".repeat(64), // stale proof stamped on a different file
            proofDigest = sha,
            authority = "ci-runner",
        )
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
        assertTrue(
            (outcome as GuardOutcome.Refused).reason.contains("originalFileSha256"),
            "refusal must name the mismatched field, got ${outcome.reason}",
        )
    }
}
