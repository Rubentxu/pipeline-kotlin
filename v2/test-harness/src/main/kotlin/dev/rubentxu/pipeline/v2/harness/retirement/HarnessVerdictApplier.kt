package dev.rubentxu.pipeline.v2.harness.retirement

/**
 * Pure applier: takes a [HarnessVerdict] and a [TestRecord] and
 * returns the new [DeletionDecision] for the row, or a typed
 * [VerdictApplication.Refused] explaining why no promotion was
 * performed.
 *
 * The function is the only place in the producer that consumes a
 * harness verdict. It is:
 *   - **total** (no exceptions on missing/malformed input),
 *   - **fail-closed** (a mismatch is a refusal, not a promotion),
 *   - **side-effect free** (no IO; the caller decides whether to
 *     persist the new decision),
 *   - **idempotent** (re-applying the same verdict yields the same
 *     decision).
 *
 * Promoting `MigrateExternalPending` to `RedundantWithProof` here
 * is the only producer-side state change that ADR-PK-QV-003
 * enables; everything else must be re-classified or remain as is.
 */
object HarnessVerdictApplier {

    /**
     * Apply [verdict] to the current state described by [decision]
     * and [record]. The `lastFailureFingerprint` is the producer's
     * recorded fingerprint of the last RED execution of the test;
     * the verdict's fingerprint must match it (or the applier
     * refuses for fingerprint mismatch).
     */
    fun apply(
        decision: DeletionDecision,
        record: TestRecord,
        verdict: HarnessVerdict,
        lastFailureFingerprint: FailureFingerprint?,
        candidateSha256: String,
    ): VerdictApplication {
        // The verdict's path is the contract; the producer must
        // agree on path identity before any other field is checked.
        if (verdict.path != record.path) {
            return VerdictApplication.Refused.PathMismatch(
                "verdict path '${verdict.path}' does not match record path '${record.path}'",
            )
        }
        return when (verdict) {
            is HarnessVerdict.Pending -> VerdictApplication.NotApplicable
            is HarnessVerdict.Blocked -> VerdictApplication.NotApplicable
            is HarnessVerdict.Verified -> applyVerified(
                decision, record, verdict, lastFailureFingerprint, candidateSha256,
            )
        }
    }

    private fun applyVerified(
        decision: DeletionDecision,
        record: TestRecord,
        verdict: HarnessVerdict.Verified,
        lastFailureFingerprint: FailureFingerprint?,
        candidateSha256: String,
    ): VerdictApplication {
        if (decision !is DeletionDecision.MigrateExternalPending) {
            return VerdictApplication.Refused.AlreadyDecided(
                "verdict targets a row already decided as " +
                    "${decision::class.simpleName}; no promotion needed",
            )
        }
        if (verdict.contentSha256 != record.contentSha256) {
            return VerdictApplication.Refused.ShaMismatch(
                "verdict contentSha256 does not match record contentSha256; " +
                    "the file the harness observed differs from the one the " +
                    "producer wants to retire",
            )
        }
        if (verdict.candidateSha256 != candidateSha256) {
            return VerdictApplication.Refused.PathMismatch(
                "verdict candidateSha256 '${verdict.candidateSha256}' " +
                    "does not match the candidate the producer wants to ship " +
                    "'${candidateSha256}'",
            )
        }
        if (lastFailureFingerprint == null) {
            return VerdictApplication.Refused.FingerprintMissing(
                "producer has no recorded fingerprint for the last RED " +
                    "execution of '${record.path}'; the harness's fingerprint " +
                    "cannot be cross-checked, refusing the promotion",
            )
        }
        if (verdict.failureFingerprint != lastFailureFingerprint) {
            return VerdictApplication.Refused.FingerprintMissing(
                "verdict fingerprint ${verdict.failureFingerprint} does not " +
                    "match producer's recorded fingerprint $lastFailureFingerprint; " +
                    "the substitute is not proven equivalent to the same defect",
            )
        }
        if (verdict.proofDigest.length != 64) {
            return VerdictApplication.Refused.ShaMismatch(
                "verdict.proofDigest is not a 64-char hex SHA-256",
            )
        }
        return VerdictApplication.Promoted(verdict.proofDigest)
    }
}
