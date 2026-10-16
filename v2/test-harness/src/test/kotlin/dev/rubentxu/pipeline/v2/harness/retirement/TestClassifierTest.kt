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
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = sha, authority = "harness")
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
        // The data-class init rejects the malformed proofDigest
        // before the guard is even called: a 64-char hex check
        // belongs in the carrier, not in the guard, so a
        // mis-typed decision cannot reach the runtime path.
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException::class.java,
        ) {
            DeletionDecision.RedundantWithProof(
                originalFileSha256 = sha,
                proofDigest = "short", // not 64 hex chars
                authority = "harness",
            )
        }
        assertTrue(
            (ex.message ?: "").contains("proofDigest"),
            "init must name the offending field, got ${ex.message}",
        )
    }

    @Test
    fun `refuses when originalFileSha256 does not match record contentSha256`() {
        // The previous test asserted "refuses when proofDigest does
        // not match contentSha256". That was the conflation this
        // audit fixes: proofDigest and contentSha256 are different
        // identities (substitute evidence vs. file body). The
        // correct invariant is: originalFileSha256 (the file
        // identity the proof refers to) must equal record.contentSha256
        // (the file the guard is about to retire). A proof stamped
        // on a different version of the file MUST refuse.
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
        val d = DeletionDecision.RedundantWithProof(
            originalFileSha256 = "f".repeat(64), // stale: different file
            proofDigest = "c".repeat(64),
            authority = "harness",
        )
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertTrue(outcome is GuardOutcome.Refused) { "got $outcome" }
        assertTrue(
            (outcome as GuardOutcome.Refused).reason.contains("originalFileSha256"),
            "refusal must name the mismatched field, got ${outcome.reason}",
        )
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
        val d = DeletionDecision.RedundantWithProof(originalFileSha256 = sha, proofDigest = sha, authority = "harness")
        val outcome = DeletionGuard.check(d, record, retirementAuthorised = true)
        assertEquals(GuardOutcome.Authorised, outcome)
    }

    @Test
    fun `permanent fitness gate is KeepCoreContract even with UAT heuristic`() {
        // UatLocal005BannedImportsTest is the only V2 UAT that
        // RESPONSIBILITY_MIGRATION_ROADMAP.md §2/§3 declares as
        // SE QUEDA (it is an architectural fitness: no JGit
        // imports, no hardcoded git binary paths). Even with the
        // kind_hint=EXPLICIT_UAT_E2E heuristic that would route
        // any other UAT to Blocked, this row MUST classify as
        // KeepCoreContract. Adding it to the permanent-fitness
        // set or removing the test requires a new ADR.
        val record = TestRecord(
            path = PERMANENT_FITNESS_PATHS.first(),
            kindHint = KindHint.EXPLICIT_UAT_E2E,
            runnerHint = RunnerHint.V2_GRADLE,
            observedProcessApi = "",
            testAnnotated = 2,
            disabledAnnotations = 0,
            releaseScaleTag = false,
            contentSha256 = sha,
            migratedPending = false,
        )
        assertEquals(DeletionDecision.KeepCoreContract, TestClassifier.classify(record))
    }

    @Test
    fun `permanent fitness gate wins over migrated-pending marker`() {
        // If a future change ever marks the banned-imports test
        // as @Disabled("migrated-pending") by mistake, the
        // permanent-fitness check MUST still win so the row
        // classifies as KeepCoreContract. The migration table
        // protects it.
        val record = TestRecord(
            path = PERMANENT_FITNESS_PATHS.first(),
            kindHint = KindHint.EXPLICIT_UAT_E2E,
            runnerHint = RunnerHint.V2_GRADLE,
            observedProcessApi = "",
            testAnnotated = 2,
            disabledAnnotations = 1,
            releaseScaleTag = false,
            contentSha256 = sha,
            migratedPending = true,
        )
        assertEquals(DeletionDecision.KeepCoreContract, TestClassifier.classify(record))
    }
}
