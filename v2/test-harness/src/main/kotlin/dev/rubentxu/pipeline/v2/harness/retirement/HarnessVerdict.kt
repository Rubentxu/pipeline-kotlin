package dev.rubentxu.pipeline.v2.harness.retirement

/**
 * Typed receipt of a `pipelinek-release-harness` verdict for a single
 * test file. The harness emits one of these per UAT it has exercised
 * against an installed candidate; the producer side reads them to
 * decide whether a `MigrateExternalPending` row can be promoted to
 * `RedundantWithProof` and retired.
 *
 * The class is closed. Adding a verdict shape requires a new ADR
 * because the lifecycle of the retirement path depends on the
 * verifier.
 */
sealed interface HarnessVerdict {
    /**
     * The path the verdict refers to. Every verdict shape carries
     * a path; the applier compares it to the producer's record
     * before any other field is checked.
     */
    val path: String

    /**
     * The harness executed the same scenario as the original UAT on
     * the same candidate and the result was comparable (verdict
     * `VERIFIED`).
     *
     * @param path the test file path the verdict refers to
     *   (producer-side absolute or repo-relative path).
     * @param contentSha256 SHA-256 of the test file body the harness
     *   observed. It MUST match the producer-side `contentSha256`
     *   of the same record, or the applier will refuse to apply.
     * @param proofDigest SHA-256 of the substitute evidence emitted
     *   by the harness (typically a fingerprint of its NDJSON line).
     *   It is the value that will be stored in
     *   `DeletionDecision.RedundantWithProof.proofDigest`.
     * @param authority the harness name (e.g. `pipelinek-release-harness`).
     * @param candidateSha256 the immutable candidate the verdict was
     *   produced for (must match the RC tag the producer wants to ship).
     * @param failureFingerprint `(input, symptom, type, cause)` per
     *   `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4.1(c). The producer
     *   binds equivalence by exact match on the last RED execution
     *   of the original UAT.
     */
    data class Verified(
        override val path: String,
        val contentSha256: String,
        val proofDigest: String,
        val authority: String,
        val candidateSha256: String,
        val failureFingerprint: FailureFingerprint,
    ) : HarnessVerdict {
        init {
            // BQ4 v6 (strict digest carrier, 2026-10-17):
            // the previous shape had no init validation
            // on the three SHA-256 fields. The reader's
            // `requireSha256` validated at parse time, but
            // the carrier was defenseless against a direct
            // construction (a test, a future consumer, or
            // a refactor that bypasses the reader). A
            // direct `Verified(contentSha256 = "0", ...)`
            // was accepted as a "verified" verdict. The
            // carrier now mirrors the reader's format
            // requirement (64-char lowercase hex SHA-256);
            // a blank or short digest is refused at
            // construction with a precise message that
            // names the field and the format.
            requireSha256("contentSha256", contentSha256)
            requireSha256("proofDigest", proofDigest)
            requireSha256("candidateSha256", candidateSha256)
            require(authority.isNotBlank()) {
                "Verified.authority must not be blank"
            }
        }
    }

    /**
     * The harness ran the scenario and the result was NOT comparable:
     * the substitute did not reproduce the original defect, or the
     * substitute is not in the gate. The applier MUST NOT promote.
     */
    data class Blocked(
        override val path: String,
        val reason: String,
    ) : HarnessVerdict {
        init {
            require(reason.isNotBlank()) { "Blocked.reason must not be blank" }
        }
    }

    /**
     * The harness has not yet emitted a verdict for this path; the
     * row stays `MigrateExternalPending`. This is the default
     * observation before the harness runs.
     */
    data class Pending(override val path: String) : HarnessVerdict {
        // BQ4 v14 (retirement carrier self-protect,
        // 2026-10-17): a blank `path` would be the
        // producer's record of "no verdict yet" for a
        // test that has no name — a fabrication. The
        // carrier refuses blanks at construction.
        init { require(path.isNotBlank()) { "Pending.path must not be blank" } }
    }
}

/**
 * The fingerprint of the defect a UAT was originally written to
 * detect. The producer and the harness MUST agree on the same
 * `(inputDigest, symptom, type, cause)` quadruple for the
 * retirement path to unlock.
 *
 * `inputDigest` is a SHA-256 hex of the test's input snapshot;
 * `symptom` is the short observable behaviour (e.g. "StepFailed:
 * no secret in Output Plane"); `type` is the failure class
 * (e.g. `OutputLeak`, `ReplayDup`); `cause` is the
 * causalBoundary the test owns.
 */
data class FailureFingerprint(
    val inputDigest: String,
    val symptom: String,
    val type: String,
    val cause: String,
) {
    init {
        // BQ4 v6 (strict digest carrier, 2026-10-17):
        // the previous shape checked length but NOT hex
        // characters. An uppercase digest or a non-hex
        // string of length 64 was accepted as a
        // "SHA-256" and compared by string equality
        // against the producer's lowercase digest,
        // silently never matching. The carrier now
        // mirrors the reader's format requirement
        // (lowercase hex), so a direct construction is
        // self-protecting.
        requireSha256("inputDigest", inputDigest)
        require(symptom.isNotBlank()) { "symptom must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
        require(cause.isNotBlank()) { "cause must not be blank" }
    }
}

/**
 * BQ4 v6 (strict digest carrier helper, 2026-10-17):
 * the SHA-256 format requirement (64-char lowercase hex)
 * is the same across every digest field on the
 * retirement carrier. One helper, one source of truth,
 * so the reader's [HarnessVerdictReader.requireSha256]
 * and the carrier's init cannot drift.
 */
private fun requireSha256(fieldName: String, value: String) {
    require(value.length == 64) {
        "$fieldName must be a 64-char hex SHA-256; got ${value.length} chars"
    }
    require(value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$fieldName must be lowercase hex"
    }
}

/**
 * The reason the harness-receipt applier refused a promotion. Typed
 * so the deletion tool can print a precise cause without parsing
 * free-form text.
 */
sealed interface VerdictApplication {
    data object NotApplicable : VerdictApplication
    // BQ4 v14 (retirement carrier self-protect,
    // 2026-10-17): `proofDigest` is the SHA-256 hex of
    // the substitute evidence emitted by the harness.
    // The strict-validate mirrors the Verified.contentDigest
    // / proofDigest format requirement; a blank or
    // non-hex value is refused at construction.
    data class Promoted(val proofDigest: String) : VerdictApplication {
        init {
            require(proofDigest.length == 64 && proofDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Promoted.proofDigest must be a 64-char lowercase hex SHA-256"
            }
        }
    }
    sealed interface Refused : VerdictApplication {
        val reason: String
        data class PathMismatch(override val reason: String) : Refused {
            init { require(reason.isNotBlank()) { "PathMismatch.reason must not be blank" } }
        }
        data class ShaMismatch(override val reason: String) : Refused {
            init { require(reason.isNotBlank()) { "ShaMismatch.reason must not be blank" } }
        }
        data class FingerprintMissing(override val reason: String) : Refused {
            init { require(reason.isNotBlank()) { "FingerprintMissing.reason must not be blank" } }
        }
        data class AlreadyDecided(override val reason: String) : Refused {
            init { require(reason.isNotBlank()) { "AlreadyDecided.reason must not be blank" } }
        }
    }
}
