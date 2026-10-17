package dev.rubentxu.pipeline.v2.harness.util

/**
 * Shared digest format validation.
 *
 * BQ4 v19 (DRY consolidation, 2026-10-17): the
 * 40-char-lowercase-hex SHA-1 and 64-char-lowercase-hex
 * SHA-256 format requirements are the same across every
 * carrier in the test-harness that consumes a digest
 * (CycleManifest.sourceSha, PromotionEvidence.boundCommit,
 * EvidenceManifest.sourceCommit, JsonOracleLedger.commit,
 * CycleCli --source-sha, GateCli <expected-commit>,
 * HarnessVerdict.Verified.*, FailureFingerprint.inputDigest,
 * DeletionDecision.RedundantWithProof.*, TestRecord.contentSha256).
 *
 * Before this consolidation, three files had their own
 * `private fun requireSha1/256(fieldName: String, value: String)`
 * helpers (`CycleCli`, `GateCli`, `HarnessVerdict`) with
 * identical logic. The fourth (`HarnessVerdictReader`)
 * has a different shape (returns a `FieldResult` typed
 * invalid/valid; takes a `Map<String, String>` lookup)
 * and is NOT consolidated — the API is intentionally
 * different because the reader collects errors, not
 * throws.
 *
 * The format requirement (length + lowercase hex) is
 * the single source of truth here. A future change
 * to the format (e.g. allow uppercase, allow non-hex
 * with a warning) updates one file, not four.
 */
object DigestFormat {

    /**
     * Require [value] to be a 40-char lowercase hex SHA-1.
     * Throws [IllegalArgumentException] with a precise
     * message naming [fieldName] and the format
     * requirement.
     */
    fun requireSha1(fieldName: String, value: String) {
        require(value.length == 40) {
            "$fieldName must be a 40-char hex SHA-1; got ${value.length} chars"
        }
        require(value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$fieldName must be lowercase hex; got '$value'"
        }
    }

    /**
     * Require [value] to be a 64-char lowercase hex SHA-256.
     * Throws [IllegalArgumentException] with a precise
     * message naming [fieldName] and the format
     * requirement.
     */
    fun requireSha256(fieldName: String, value: String) {
        require(value.length == 64) {
            "$fieldName must be a 64-char hex SHA-256; got ${value.length} chars"
        }
        require(value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$fieldName must be lowercase hex; got '$value'"
        }
    }
}
