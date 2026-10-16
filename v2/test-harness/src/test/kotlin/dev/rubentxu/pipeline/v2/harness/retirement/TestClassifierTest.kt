package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestClassifierTest {

    private val sha = "a".repeat(64)
    private fun rec(
        path: String = "v2/x/src/test/kotlin/SampleTest.kt",
        kindHint: KindHint = KindHint.OTHER_TEST_SOURCE,
        runnerHint: RunnerHint = RunnerHint.V2_GRADLE,
        observedProcessApi: String = "",
        testAnnotated: Int = 1,
        disabledAnnotations: Int = 0,
        releaseScaleTag: Boolean = false,
    ) = TestRecord(
        path = path,
        kindHint = kindHint,
        runnerHint = runnerHint,
        observedProcessApi = observedProcessApi,
        testAnnotated = testAnnotated,
        disabledAnnotations = disabledAnnotations,
        releaseScaleTag = releaseScaleTag,
        contentSha256 = sha,
    )

    @Test
    fun `support or abstract is TestFixture`() {
        val d = TestClassifier.classify(rec(kindHint = KindHint.SUPPORT_OR_ABSTRACT))
        assertEquals(DeletionDecision.TestFixture, d)
    }

    @Test
    fun `v1 runner is LegacyNonV2`() {
        val d = TestClassifier.classify(rec(runnerHint = RunnerHint.LEGACY_NOT_DEFAULT_V2))
        assertEquals(DeletionDecision.LegacyNonV2, d)
    }

    @Test
    fun `release scale tag is MigrateExternalPending`() {
        val d = TestClassifier.classify(rec(releaseScaleTag = true))
        assertEquals(DeletionDecision.MigrateExternalPending, d)
    }

    @Test
    fun `process API observed is MigrateExternalPending`() {
        val d = TestClassifier.classify(rec(observedProcessApi = "ProcessBuilder("))
        assertEquals(DeletionDecision.MigrateExternalPending, d)
    }

    @Test
    fun `explicit UAT with @Test is Blocked (needs harness verdict)`() {
        val d = TestClassifier.classify(rec(kindHint = KindHint.EXPLICIT_UAT_E2E))
        assertTrue(d is DeletionDecision.Blocked) { "got $d" }
    }

    @Test
    fun `explicit UAT with no @Test is TestFixture`() {
        val d = TestClassifier.classify(rec(kindHint = KindHint.EXPLICIT_UAT_E2E, testAnnotated = 0))
        assertEquals(DeletionDecision.TestFixture, d)
    }

    @Test
    fun `integration candidate is KeepCoreContract`() {
        val d = TestClassifier.classify(rec(kindHint = KindHint.INTEGRATION_CANDIDATE))
        assertEquals(DeletionDecision.KeepCoreContract, d)
    }

    @Test
    fun `plain test with @Test is KeepCoreContract`() {
        val d = TestClassifier.classify(rec(testAnnotated = 3))
        assertEquals(DeletionDecision.KeepCoreContract, d)
    }

    @Test
    fun `unknown default is Blocked`() {
        val d = TestClassifier.classify(rec(testAnnotated = 0, kindHint = KindHint.OTHER_TEST_SOURCE))
        assertTrue(d is DeletionDecision.Blocked) { "got $d" }
    }
}

class DeletionGuardTest {

    private val sha = "b".repeat(64)

    @Test
    fun `refuses when retirement is not authorised`() {
        val record = TestRecord(
            path = "v2/x/src/test/kotlin/SampleTest.kt",
            kindHint = KindHint.OTHER_TEST_SOURCE,
            runnerHint = RunnerHint.V2_GRADLE,
            observedProcessApi = "",
            testAnnotated = 1,
            disabledAnnotations = 0,
            releaseScaleTag = false,
            contentSha256 = sha,
        )
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = false)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    @Test
    fun `refuses non-RedundantWithProof decision`() {
        val record = TestRecord(
            path = "v2/x/src/test/kotlin/SampleTest.kt",
            kindHint = KindHint.OTHER_TEST_SOURCE,
            runnerHint = RunnerHint.V2_GRADLE,
            observedProcessApi = "",
            testAnnotated = 1,
            disabledAnnotations = 0,
            releaseScaleTag = false,
            contentSha256 = sha,
        )
        val outcome = DeletionGuard.check(DeletionDecision.KeepCoreContract, record, retirementAuthorised = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    @Test
    fun `refuses when proofDigest is not 64 hex chars`() {
        val record = TestRecord(
            path = "v2/x/src/test/kotlin/SampleTest.kt",
            kindHint = KindHint.OTHER_TEST_SOURCE,
            runnerHint = RunnerHint.V2_GRADLE,
            observedProcessApi = "",
            testAnnotated = 1,
            disabledAnnotations = 0,
            releaseScaleTag = false,
            contentSha256 = sha,
        )
        val d = DeletionDecision.RedundantWithProof(proofDigest = "short", authority = "harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    @Test
    fun `refuses when proofDigest does not match contentSha256`() {
        val record = TestRecord(
            path = "v2/x/src/test/kotlin/SampleTest.kt",
            kindHint = KindHint.OTHER_TEST_SOURCE,
            runnerHint = RunnerHint.V2_GRADLE,
            observedProcessApi = "",
            testAnnotated = 1,
            disabledAnnotations = 0,
            releaseScaleTag = false,
            contentSha256 = sha,
        )
        val d = DeletionDecision.RedundantWithProof(proofDigest = "c".repeat(64), authority = "harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
    }

    @Test
    fun `authorises when all fields are valid and retirementAuthorised`() {
        val record = TestRecord(
            path = "v2/x/src/test/kotlin/SampleTest.kt",
            kindHint = KindHint.OTHER_TEST_SOURCE,
            runnerHint = RunnerHint.V2_GRADLE,
            observedProcessApi = "",
            testAnnotated = 1,
            disabledAnnotations = 0,
            releaseScaleTag = false,
            contentSha256 = sha,
        )
        val d = DeletionDecision.RedundantWithProof(proofDigest = sha, authority = "harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }
}
