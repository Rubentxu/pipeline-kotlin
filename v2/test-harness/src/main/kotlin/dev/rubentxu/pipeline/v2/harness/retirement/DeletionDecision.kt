package dev.rubentxu.pipeline.v2.harness.retirement

/**
 * The decision for one test file. The seven cases are the addendum's
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

    /** Equivalent coverage exists; needs `proofDigest` + `authority` to retire. */
    data class RedundantWithProof(
        val proofDigest: String,
        val authority: String,
    ) : DeletionDecision

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
