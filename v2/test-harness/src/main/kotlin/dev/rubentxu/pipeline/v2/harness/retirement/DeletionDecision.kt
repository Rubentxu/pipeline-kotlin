package dev.rubentxu.pipeline.v2.harness.retirement

/**
 * The decision for one test file. The seven cases are the BQ5
 * classification; adding one requires an ADR.
 *
 * The classifier NEVER produces `DELETE_READY` from heuristics alone.
 * `DELETE_READY` requires:
 *   - a `replacementContractId` that the catalogue knows,
 *   - a `proofDigest` of an equivalent test that passed against the same
 *     binary,
 *   - an `authority` that signed the proof (the harness name).
 *
 * The classifier only produces `KEEP_CORE_CONTRACT` /
 * `TEMPORARY_CYCLE` / `MIGRATE_EXTERNAL_PENDING` /
 * `REDUNDANT_WITH_PROOF` / `TEST_FIXTURE` / `LEGACY_NON_V2` /
 * `BLOCKED`. The `DELETE_READY` transition happens in a separate
 * function, [`DeletionGuard`], that requires the proof fields.
 */
sealed interface DeletionDecision {
    /** A contract lives here; the harness must keep it. */
    data object KeepCoreContract : DeletionDecision

    /** A cycle probe created in this iteration; retire on cycle close. */
    data object TemporaryCycle : DeletionDecision

    /** Belongs to the harness; do not delete from this repo. */
    data object MigrateExternalPending : DeletionDecision

    /**
     * Equivalent coverage exists; needs the substitute's proof
     * digest, the file identity the proof refers to, and the
     * signing authority to retire.
     *
     * The two digests are distinct identities:
     * - `originalFileSha256` is the SHA-256 of the test file the
     *   proof applies to. The guard checks that
     *   `record.contentSha256 == originalFileSha256` so a stale
     *   proof cannot be reused for a modified file.
     * - `proofDigest` is the SHA-256 of the substitute evidence
     *   the harness emitted (e.g. the fingerprint of the
     *   harness's NDJSON line). It is what links the
     *   retirement to the harness's verdict and is **not**
     *   expected to equal the file's hash.
     */
    data class RedundantWithProof(
        val originalFileSha256: String,
        val proofDigest: String,
        val authority: String,
    ) : DeletionDecision {
        init {
            // BQ4 v6 (strict digest carrier, 2026-10-17):
            // the previous shape checked length but NOT
            // hex characters. An uppercase digest or a
            // non-hex string of length 64 was accepted as
            // a "SHA-256" and compared by string equality
            // against the harness's lowercase digest,
            // silently never matching. The carrier now
            // mirrors the format requirement (64-char
            // lowercase hex) that the harness-emitted
            // digests follow and that the rest of the
            // retirement package already enforces
            // (HarnessVerdict.Verified, FailureFingerprint).
            require(originalFileSha256.length == 64 && originalFileSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
                "originalFileSha256 must be a 64-char lowercase hex SHA-256"
            }
            require(proofDigest.length == 64 && proofDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
                "proofDigest must be a 64-char lowercase hex SHA-256"
            }
            require(authority.isNotBlank()) {
                "authority must be a non-blank harness / certifier name"
            }
        }
    }

    /** A test fixture (no @Test methods); retire as part of a parent refactor. */
    data object TestFixture : DeletionDecision

    /** A test from the legacy V1 module; not part of the V2 contract. */
    data object LegacyNonV2 : DeletionDecision

    /** Cannot classify; the owner must look. */
    data class Blocked(val reason: String) : DeletionDecision {
        init {
            require(reason.isNotBlank()) { "Blocked.reason must not be blank" }
        }
    }
}
