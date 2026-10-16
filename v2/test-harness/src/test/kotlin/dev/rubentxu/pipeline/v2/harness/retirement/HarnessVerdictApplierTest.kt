package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HarnessVerdictApplierTest {

    private val sha = "a".repeat(64)
    private val otherSha = "b".repeat(64)
    private val candidateSha = "c".repeat(64)
    private val fingerprint = FailureFingerprint(
        inputDigest = sha,
        symptom = "OutputLeak",
        type = "OutputLeak",
        cause = "redaction ring-full spin",
    )

    private fun rec(
        path: String = "v2/pipeline-application/src/test/.../UatLocal008SecretLeakTest.kt",
        contentSha256: String = sha,
    ) = TestRecord(
        path = path,
        kindHint = KindHint.EXPLICIT_UAT_E2E,
        runnerHint = RunnerHint.V2_GRADLE,
        observedProcessApi = "",
        testAnnotated = 1,
        disabledAnnotations = 0,
        releaseScaleTag = false,
        contentSha256 = contentSha256,
    )

    private fun verified(
        path: String = rec().path,
        contentSha256: String = sha,
        proofDigest: String = otherSha,
        candidateSha256: String = candidateSha,
        fp: FailureFingerprint = fingerprint,
    ) = HarnessVerdict.Verified(
        path = path,
        contentSha256 = contentSha256,
        proofDigest = proofDigest,
        authority = "pipelinek-release-harness",
        candidateSha256 = candidateSha256,
        failureFingerprint = fp,
    )

    @Test
    fun `Pending verdict leaves MigrateExternalPending unchanged`() {
        val r = rec()
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            HarnessVerdict.Pending(r.path),
            fingerprint,
            candidateSha,
        )
        assertEquals(VerdictApplication.NotApplicable, out)
    }

    @Test
    fun `Blocked verdict leaves MigrateExternalPending unchanged`() {
        val r = rec()
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            HarnessVerdict.Blocked(r.path, "substitute failed to reproduce"),
            fingerprint,
            candidateSha,
        )
        assertEquals(VerdictApplication.NotApplicable, out)
    }

    @Test
    fun `Verified with matching fingerprint promotes to RedundantWithProof`() {
        val r = rec()
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            verified(),
            fingerprint,
            candidateSha,
        )
        assertTrue(out is VerdictApplication.Promoted) { "got $out" }
        assertEquals(otherSha, (out as VerdictApplication.Promoted).proofDigest)
    }

    @Test
    fun `Verified with mismatched path is refused`() {
        val r = rec()
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            verified(path = "v2/.../OtherTest.kt"),
            fingerprint,
            candidateSha,
        )
        assertTrue(out is VerdictApplication.Refused.PathMismatch) { "got $out" }
    }

    @Test
    fun `Verified with mismatched contentSha256 is refused`() {
        val r = rec(contentSha256 = sha)
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            verified(contentSha256 = otherSha),
            fingerprint,
            candidateSha,
        )
        assertTrue(out is VerdictApplication.Refused.ShaMismatch) { "got $out" }
    }

    @Test
    fun `Verified with mismatched candidateSha256 is refused`() {
        val r = rec()
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            verified(candidateSha256 = "d".repeat(64)),
            fingerprint,
            candidateSha,
        )
        assertTrue(out is VerdictApplication.Refused.PathMismatch) { "got $out" }
    }

    @Test
    fun `Verified with no recorded fingerprint is refused`() {
        val r = rec()
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            verified(),
            lastFailureFingerprint = null,
            candidateSha256 = candidateSha,
        )
        assertTrue(out is VerdictApplication.Refused.FingerprintMissing) { "got $out" }
    }

    @Test
    fun `Verified with mismatched fingerprint is refused`() {
        val r = rec()
        val otherFp = FailureFingerprint(
            inputDigest = sha,
            symptom = "ReplayDup",
            type = "ReplayDup",
            cause = "tailState bare recovered",
        )
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            verified(fp = otherFp),
            fingerprint,
            candidateSha,
        )
        assertTrue(out is VerdictApplication.Refused.FingerprintMissing) { "got $out" }
    }

    @Test
    fun `Verified with malformed proofDigest is refused`() {
        val r = rec()
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending,
            r,
            verified(proofDigest = "too-short"),
            fingerprint,
            candidateSha,
        )
        assertTrue(out is VerdictApplication.Refused.ShaMismatch) { "got $out" }
    }

    @Test
    fun `Verified applied to a non-MigrateExternalPending decision is refused as AlreadyDecided`() {
        val r = rec()
        val out = HarnessVerdictApplier.apply(
            DeletionDecision.KeepCoreContract,
            r,
            verified(),
            fingerprint,
            candidateSha,
        )
        assertTrue(out is VerdictApplication.Refused.AlreadyDecided) { "got $out" }
    }

    @Test
    fun `applying the same Verified twice is idempotent`() {
        val r = rec()
        val first = HarnessVerdictApplier.apply(
            DeletionDecision.MigrateExternalPending, r, verified(), fingerprint, candidateSha256 = candidateSha,
        )
        assertTrue(first is VerdictApplication.Promoted) { "first call should promote; got $first" }
        // Once promoted to RedundantWithProof, a second verdict against the
        // already-decided row must NOT re-promote: it is already decided.
        val second = HarnessVerdictApplier.apply(
            first.toDecision(), r, verified(), fingerprint, candidateSha256 = candidateSha,
        )
        assertTrue(second is VerdictApplication.Refused.AlreadyDecided) { "got $second" }
    }

    private fun VerdictApplication.toDecision(): DeletionDecision = when (this) {
        is VerdictApplication.Promoted -> DeletionDecision.RedundantWithProof(
            originalFileSha256 = sha, // the record's contentSha256 == verdict.contentSha256
            proofDigest = proofDigest, // the substitute evidence hash from the harness
            authority = "pipelinek-release-harness",
        )
        is VerdictApplication.NotApplicable -> DeletionDecision.MigrateExternalPending
        is VerdictApplication.Refused -> DeletionDecision.MigrateExternalPending
    }
}
