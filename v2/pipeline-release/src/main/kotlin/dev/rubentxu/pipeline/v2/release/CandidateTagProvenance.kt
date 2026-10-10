package dev.rubentxu.pipeline.v2.release

/**
 * P0.5b — published-asset provenance law.
 *
 * The defect this exists to prevent is the *published* version of what
 * [SourceProvenance] prevents *in-tree*. The in-tree half refuses a build whose
 * recorded source commit does not identify the bytes compiled. This file
 * refuses the published artifact whose tag/bytes association is broken —
 * either by republishing an asset without moving the tag (v0.40.0 / v0.43.0
 * version-laundering, see [docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md]
 * §7a) or by uploading bytes that were built from a different commit than the
 * tag's peel (the v0.48.0-rc2 proveniencia incident of 2026-10-10).
 *
 * The law: **the published asset's bytes must identify with the source commit
 * the handoff names; the tag (when present) must identify with the same source
 * commit, or the publisher must record a `rebuildFromCommit` that explains the
 * override.** Any other shape is a refusal, not a caveat.
 *
 * Like [SourceProvenance], this file is a pure decider. It takes observed
 * facts and returns a verdict; it performs no I/O. The build wiring is
 * responsible for assembling the facts (which usually come from the GitHub
 * release API + a Git rev-parse of the tag's peel) and propagating the verdict.
 *
 * The three refusal shapes, in priority order:
 *
 *  1. `rebuildFromCommit != sourceCommit` — the recorded source commit is
 *     not the commit the publisher claims to have rebuilt from. This is the
 *     v0.43.0 family arriving through a different door: the bytes were
 *     compiled from a different commit than the manifest names, so the
 *     manifest is a lie about the bytes. (See [SourceProvenance.evaluate]
 *     for the in-tree half of the same law.)
 *
 *  2. `rebuildFromCommit == sourceCommit` AND `rebuildSha256 != publishedSha256`
 *     — the publisher did rebuild from the recorded source commit, but the
 *     uploaded asset is not the artifact that rebuild produced. This is the
 *     republish-without-moving-the-tag laundering pattern (v0.40.0 / v0.43.0
 *     family, version-laundering).
 *
 *  3. `tagPeelCommit != null` AND `tagPeelCommit != sourceCommit` AND
 *     `rebuildFromCommit == null` — the tag's peel is not the source commit
 *     AND the publisher has not recorded a `rebuildFromCommit` to explain
 *     why. This is the v0.48.0-rc2 proveniencia-incident shape: the tag
 *     promises bytes that do not proceed from its peel, and the publisher
 *     did not even claim a separate rebuild commit.
 */
object CandidateTagProvenance {

    /**
     * Decide whether a published candidate's tag/bytes/source association is
     * admissible.
     */
    fun evaluate(facts: CandidateTagProvenanceFacts): CandidateTagProvenanceVerdict =
        when {
            facts.sourceCommit == SourceProvenance.PROVENANCE_UNKNOWN ->
                CandidateTagProvenanceVerdict.Unverifiable(
                    "the recorded source commit is unknown. A published candidate whose " +
                        "source commit is unreadable cannot be traced; the certifier has no " +
                        "commit to check out and no way to verify byte-for-byte identity."
                )

            facts.rebuildFromCommit != null && facts.rebuildFromCommit != facts.sourceCommit ->
                CandidateTagProvenanceVerdict.Refused(
                    "the publisher claims to have rebuilt from commit " +
                        "${facts.rebuildFromCommit} but the handoff names source commit " +
                        "${facts.sourceCommit}. A candidate that was rebuilt from a different " +
                        "commit than the manifest names is a lie of address and must not be " +
                        "published."
                )

            facts.publishedSha256.isNotBlank() &&
                facts.rebuildSha256 != null &&
                facts.rebuildSha256 != facts.publishedSha256 ->
                CandidateTagProvenanceVerdict.Refused(
                    "the published asset's SHA-256 (${facts.publishedSha256}) does not match " +
                        "the SHA-256 of a clean rebuild from the recorded source commit " +
                        "(${facts.rebuildSha256}). The published bytes are not the bytes the " +
                        "source commit produces. This is the v0.40.0 / v0.43.0 " +
                        "version-laundering pattern and is not admissible."
                )

            facts.tagPeelCommit != null &&
                facts.tagPeelCommit != facts.sourceCommit &&
                facts.rebuildFromCommit == null ->
                CandidateTagProvenanceVerdict.Refused(
                    "the published tag's peel (${facts.tagPeelCommit}) does not match the " +
                        "recorded source commit (${facts.sourceCommit}) and no " +
                        "rebuildFromCommit has been recorded to explain the override. This is " +
                        "the v0.48.0-rc2 proveniencia-incident shape: the tag promises bytes " +
                        "that do not proceed from its peel, and the publisher did not even " +
                        "claim a separate rebuild commit. Publish on a new tag whose peel is " +
                        "${facts.sourceCommit}, or remove the tag, or record the override " +
                        "explicitly. The current state is not admissible."
                )

            else ->
                CandidateTagProvenanceVerdict.Clean(
                    tagPeelCommit = facts.tagPeelCommit,
                    sourceCommit = facts.sourceCommit,
                    rebuildFromCommit = facts.rebuildFromCommit,
                    publishedSha256 = facts.publishedSha256,
                )
        }
}

/**
 * The observed facts about a published candidate's tag/bytes/source
 * association.
 *
 * All four fields are required for a full evaluation; the verdict is
 * well-defined on any subset because the refusal cases short-circuit, but a
 * `Clean` verdict with a non-null `rebuildSha256` that does not equal
 * `publishedSha256` would itself be a bug, so callers should pass real
 * measurements rather than placeholders.
 */
data class CandidateTagProvenanceFacts(
    /**
     * The source commit the candidate's `candidate-handoff.json` names. Must
     * be the lowercase hex form (`git rev-parse HEAD`), or
     * [SourceProvenance.PROVENANCE_UNKNOWN] if the publisher could not read
     * it.
     */
    val sourceCommit: String,
    /**
     * The peel of the published tag (`git rev-parse &lt;tag&gt;^{}`), or `null`
     * if the candidate is published without a tag (e.g. the SHA-only publication
     * path from release-model-v2 §4 option A).
     */
    val tagPeelCommit: String?,
    /**
     * The commit the publisher claims to have rebuilt the published asset
     * from, when the asset was produced by a separate build that is not the
     * commit the bytes are nominally "from". `null` means the publisher did
     * not record an override.
     */
    val rebuildFromCommit: String?,
    /**
     * The SHA-256 of the published asset (e.g. the ZIP uploaded to the
     * Prerelease). Empty / blank means the publisher did not record a digest.
     */
    val publishedSha256: String,
    /**
     * The SHA-256 of a clean rebuild from [sourceCommit] (or
     * [rebuildFromCommit] if that is what was actually built). `null` means
     * the publisher did not perform a reproducibility measurement.
     */
    val rebuildSha256: String?,
)

/**
 * Verdict of the published-asset provenance law.
 */
sealed interface CandidateTagProvenanceVerdict {

    /**
     * The published candidate's tag/bytes/source association is admissible.
     */
    data class Clean(
        val tagPeelCommit: String?,
        val sourceCommit: String,
        val rebuildFromCommit: String?,
        val publishedSha256: String,
    ) : CandidateTagProvenanceVerdict

    /**
     * The published candidate is refused. The publisher must either (a)
     * publish on a new tag whose peel is [sourceCommit], (b) remove the
     * existing tag, (c) record a `rebuildFromCommit` that explains the
     * override and produce a clean rebuild whose SHA matches the published
     * one, or (d) re-upload the asset that does match.
     */
    data class Refused(val reason: String) : CandidateTagProvenanceVerdict

    /**
     * Version control could not be consulted. Distinct from [Clean] on
     * purpose: an unanswerable question is not a passing one.
     */
    data class Unverifiable(val reason: String) : CandidateTagProvenanceVerdict

    /** Operator-facing diagnostic. */
    fun render(): String = when (this) {
        is Clean -> "tag/source provenance VERIFIED: " +
            "tagPeel=${tagPeelCommit ?: "(no tag)"}, " +
            "sourceCommit=$sourceCommit, " +
            "rebuildFromCommit=${rebuildFromCommit ?: "(no override)"}, " +
            "publishedSha256=$publishedSha256"
        is Refused -> "tag/source provenance REFUSED: $reason"
        is Unverifiable -> "tag/source provenance UNVERIFIABLE: $reason"
    }
}
