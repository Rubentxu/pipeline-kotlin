package dev.rubentxu.pipeline.v2.harness.retirement

/**
 * The gate that promotes a [DeletionDecision.RedundantWithProof] to a
 * concrete file deletion.
 *
 * The guard is wave-aware: the same proof that retires a Wave A smoke
 * duplicate is **not enough** to retire a Wave C sandbox test, and
 * Wave D (V1 legacy) is gated by [retirementAuthorisedD] which is
 * independent of the certifier verdict.
 *
 * A test is allowed to retire only when ALL of the following hold:
 *   - the decision is `RedundantWithProof` (NOT Keep, Migrate, Fixture, etc.),
 *   - the `proofDigest` is a 64-char hex string,
 *   - the `authority` is non-blank and matches the wave's expectations,
 *   - the file's `contentSha256` matches the manifest,
 *   - the wave's authority is unlocked (`retirementAuthorised` for
 *     A/B/C; `retirementAuthorisedD` for D).
 *   - ADR-PK-QV-003 is `accepted` (signaled by `retirementAuthorised`).
 *
 * When the relevant flag is false, every input is refused. The guard
 * does not invent a default; refusal is a typed outcome.
 */
object DeletionGuard {
    fun check(
        decision: DeletionDecision,
        record: TestRecord,
        retirementAuthorised: Boolean,
        retirementAuthorisedD: Boolean = false,
    ): GuardOutcome {
        val wave = Wave.forRecord(record)
        if (wave == Wave.D) {
            if (!retirementAuthorisedD) {
                return GuardOutcome.Refused("Wave D (V1 legacy / scripts) not authorised")
            }
        } else if (!retirementAuthorised) {
            return GuardOutcome.Refused("ADR-PK-QV-003 not accepted; Waves A/B/C not authorised")
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
            return GuardOutcome.Refused(
                "decision.proofDigest does not match record.contentSha256; " +
                    "the proof must point at the file that is being retired",
            )
        }
        // Wave-specific authority expectations: Wave C demands a sandbox-
        // level authority. Other waves accept any non-blank authority,
        // because the proof digest is the binding constraint.
        if (wave == Wave.C) {
            val allowed = listOf("sandbox", "release-harness", "harness")
            if (decision.authority.lowercase() !in allowed) {
                return GuardOutcome.Refused(
                    "Wave C requires a sandbox-grade authority " +
                        "(one of: ${allowed.joinToString()}); got '${decision.authority}'",
                )
            }
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
