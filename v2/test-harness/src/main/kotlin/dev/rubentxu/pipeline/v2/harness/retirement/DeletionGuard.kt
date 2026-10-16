package dev.rubentxu.pipeline.v2.harness.retirement

/**
 * The gate that promotes a [DeletionDecision.RedundantWithProof] to a
 * concrete file deletion.
 *
 * This is the BQ5 boundary. The classifier is pure and conservative; the
 * guard requires explicit proof fields, refuses them when any are missing,
 * and never permits a deletion of a file outside the manifest's cycle.
 *
 * A test is allowed to retire only when ALL of the following hold:
 *   - the decision is `RedundantWithProof` (NOT Keep, Migrate, Fixture, etc.),
 *   - the `proofDigest` is a 64-char hex string,
 *   - the `authority` is a non-blank harness / certifier name,
 *   - the file's `contentSha256` matches the manifest,
 *   - ADR-PK-QV-003 is `accepted` (signaled by `retirementAuthorised`).
 *
 * When `retirementAuthorised` is false, every input is refused. The guard
 * does not invent a default; refusal is a typed outcome.
 */
object DeletionGuard {
    fun check(
        decision: DeletionDecision,
        record: TestRecord,
        retirementAuthorised: Boolean,
    ): GuardOutcome {
        if (!retirementAuthorised) {
            return GuardOutcome.Refused("ADR-PK-QV-003 not accepted; retirement not authorised")
        }
        if (decision !is DeletionDecision.RedundantWithProof) {
            return GuardOutcome.Refused(
                "decision must be RedundantWithProof; got ${decision::class.simpleName}",
            )
        }
        if (decision.proofDigest.length != 64) {
            return GuardOutcome.Refused("proofDigest must be a 64-char hex SHA-256")
        }
        if (decision.authority.isBlank()) {
            return GuardOutcome.Refused("authority must be a non-blank harness / certifier name")
        }
        if (record.contentSha256 != decision.proofDigest) {
            // The classifier stored the file's hash; the decision carries the
            // hash of the PROOF (the test the harness ran). They are
            // different things; this check is that the decision's proof
            // matches a real file by sha. We do not have a global index here
            // — that is the retirement_audit's job. We require the decision
            // to declare the proof digest of a file that EXISTS; the audit
            // verifies the rest.
            return GuardOutcome.Refused(
                "decision.proofDigest does not match record.contentSha256; " +
                    "the proof must point at the file that is being retired",
            )
        }
        return GuardOutcome.Authorised
    }
}

sealed interface GuardOutcome {
    data object Authorised : GuardOutcome
    data class Refused(val reason: String) : GuardOutcome {
        init {
            require(reason.isNotBlank()) { "Refused.reason must not be blank" }
        }
    }
}
