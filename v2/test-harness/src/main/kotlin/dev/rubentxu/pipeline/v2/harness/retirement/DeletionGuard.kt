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
 * The two digests in `RedundantWithProof` are distinct identities
 * (see [DeletionDecision.RedundantWithProof] for the rationale):
 *   - `originalFileSha256` is the SHA-256 of the file being retired.
 *     The guard requires `record.contentSha256 == originalFileSha256`
 *     so a proof stamped on a different version of the file cannot
 *     retire the current one.
 *   - `proofDigest` is the SHA-256 of the substitute evidence the
 *     harness emitted. The guard checks it is a well-formed
 *     64-char hex (enforced by the data class `init`) but does
 *     NOT require it to equal the file's hash: the proof and
 *     the file are different things.
 *
 * A test is allowed to retire only when ALL of the following hold:
 *   - the decision is `RedundantWithProof` (NOT Keep, Migrate, Fixture, etc.),
 *   - the `originalFileSha256` matches the record's `contentSha256`
 *     (file identity is the same as the one the proof refers to),
 *   - the `proofDigest` is a 64-char hex string (well-formed),
 *   - the `authority` is non-blank and matches the wave's expectations,
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
        if (record.contentSha256 != decision.originalFileSha256) {
            return GuardOutcome.Refused(
                "decision.originalFileSha256 does not match record.contentSha256; " +
                    "the proof must refer to the file that is being retired, " +
                    "otherwise the file may have changed since the proof was issued",
            )
        }
        // Wave-specific authority expectations: Wave C demands a sandbox-
        // level authority. Other waves accept any non-blank authority.
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
