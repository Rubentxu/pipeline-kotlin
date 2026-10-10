package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * P0.5b regression — the published-asset provenance law.
 *
 * This test pins the contract that fails closed on the three published-asset
 * defect shapes observed to date:
 *
 *  1. v0.40.0 / v0.43.0 version-laundering — an asset from one train is
 *     republished under another tag without rebuilding
 *     ([DISTRIBUTION_RELEASE_SPEC.md §7a](docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md)).
 *  2. v0.48.0-rc2 proveniencia incident (2026-10-10) — a Prerelease tag's
 *     peel is not the source commit of the asset currently published, and
 *     no `rebuildFromCommit` has been recorded to explain the override.
 *  3. A future shape: `rebuildFromCommit != sourceCommit` — the publisher
 *     rebuilt from a different commit than the manifest names, so the
 *     manifest is a lie about the bytes.
 *
 * The test is pure. No I/O, no GitHub, no `git`. Each fixture is a
 * (CandidateTagProvenanceFacts, expectedVerdict) tuple that runs against
 * [CandidateTagProvenance.evaluate] and asserts the verdict kind and key
 * reason phrases. The point is to make a refactor of the law visible: a
 * loosened guard will turn one of the `Refused` fixtures green, and the
 * test will fail with a precise reason.
 */
@DisplayName("P0.5b tag/source provenance regression — published-asset defects fail closed")
class CandidateTagProvenanceRegressionTest {

    @Nested
    @DisplayName("Clean — admissible published candidates")
    inner class CleanCases {

        @Test
        @DisplayName("tag peel equals source commit, no rebuild override, rebuild matches published")
        fun cleanAdmissibleCandidate() {
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                tagPeelCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                rebuildFromCommit = null,
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val verdict = CandidateTagProvenance.evaluate(facts)
            assertTrue(
                verdict is CandidateTagProvenanceVerdict.Clean,
                "expected Clean, got ${verdict::class.simpleName}: ${verdict.render()}",
            )
        }

        @Test
        @DisplayName("SHA-only publication — no tag, no override, rebuild matches published")
        fun cleanShaOnlyPublication() {
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                tagPeelCommit = null,
                rebuildFromCommit = null,
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val verdict = CandidateTagProvenance.evaluate(facts)
            assertTrue(
                verdict is CandidateTagProvenanceVerdict.Clean,
                "expected Clean for SHA-only publication, got ${verdict::class.simpleName}: " +
                    verdict.render(),
            )
        }

        @Test
        @DisplayName("tag peel differs from source commit but a recorded rebuildFromCommit explains it")
        fun cleanWithRebuildOverride() {
            // Hypothetical: the tag was created at commit A, the publisher then
            // rebuilt from commit B (the sourceCommit of the bytes), and recorded
            // B as the rebuild commit. This is admissible IF the rebuild SHA
            // matches the published SHA — that is what the third refusal case
            // checks.
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                tagPeelCommit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                rebuildFromCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val verdict = CandidateTagProvenance.evaluate(facts)
            assertTrue(
                verdict is CandidateTagProvenanceVerdict.Clean,
                "expected Clean when a rebuildFromCommit is recorded AND its SHA matches, " +
                    "got ${verdict::class.simpleName}: ${verdict.render()}",
            )
        }

        @Test
        @DisplayName("rebuild not measured (null) but tag and source agree — still Clean")
        fun cleanNoRebuildMeasurement() {
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                tagPeelCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                rebuildFromCommit = null,
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = null,
            )
            val verdict = CandidateTagProvenance.evaluate(facts)
            assertTrue(
                verdict is CandidateTagProvenanceVerdict.Clean,
                "expected Clean when no rebuild measurement exists but tag/source agree, " +
                    "got ${verdict::class.simpleName}: ${verdict.render()}",
            )
        }
    }

    @Nested
    @DisplayName("Refused — published-asset defects observed to date")
    inner class RefusedCases {

        @Test
        @DisplayName("v0.40.0 / v0.43.0 version-laundering: published SHA does not match rebuild")
        fun refusedVersionLaundering() {
            // The GA tag of a train is published with an asset whose SHA was
            // actually produced by a different train's candidate. The publisher
            // has the same source commit (or has not recorded a different one),
            // but the published ZIP is the asset from a different rebuild.
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                tagPeelCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                rebuildFromCommit = null,
                // The published asset is from v0.43.0-rc1 (different train).
                publishedSha256 = "b81687acf82d04e814908eadfaaaa39520fe976e772c40084a0125bba2005483",
                // A clean rebuild from the recorded source commit produces a
                // different artifact.
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val verdict = CandidateTagProvenance.evaluate(facts)
            assertTrue(
                verdict is CandidateTagProvenanceVerdict.Refused,
                "v0.40.0/v0.43.0 laundering must be refused, got ${verdict::class.simpleName}: " +
                    verdict.render(),
            )
            val reason = (verdict as CandidateTagProvenanceVerdict.Refused).reason
            assertTrue(
                reason.contains("does not match", ignoreCase = true) ||
                    reason.contains("laundering", ignoreCase = true),
                "refusal reason must explain the SHA mismatch / laundering shape, got: $reason",
            )
        }

        @Test
        @DisplayName("v0.48.0-rc2 proveniencia incident: tag peel != source, no rebuild override")
        fun refusedProvenienciaIncident() {
            // The v0.48.0-rc2 shape: the tag's peel is the pre-audit commit
            // 74c5331e, but the asset currently published was built from
            // 2e088f67 (the audit-fix commit). No rebuildFromCommit was
            // recorded to explain the override.
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "2e088f674b66924382e15fd1680eeade773d98de",
                tagPeelCommit = "74c5331e2c242658f6b4c9e83f896ce5f8a4fe98",
                rebuildFromCommit = null,
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val verdict = CandidateTagProvenance.evaluate(facts)
            assertTrue(
                verdict is CandidateTagProvenanceVerdict.Refused,
                "v0.48.0-rc2 proveniencia incident must be refused, got " +
                    "${verdict::class.simpleName}: ${verdict.render()}",
            )
            val reason = (verdict as CandidateTagProvenanceVerdict.Refused).reason
            assertTrue(
                reason.contains("proveniencia", ignoreCase = true) ||
                    reason.contains("peel", ignoreCase = true) ||
                    reason.contains("override", ignoreCase = true),
                "refusal reason must name the proveniencia / peel / override shape, got: $reason",
            )
        }

        @Test
        @DisplayName("rebuildFromCommit != sourceCommit: the manifest names a commit the bytes are not from")
        fun refusedRebuildSourceMismatch() {
            // The publisher rebuilt from commit X but the handoff names
            // commit Y as the source. The bytes are not what the manifest
            // claims; this is a lie of address.
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                tagPeelCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                rebuildFromCommit = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val verdict = CandidateTagProvenance.evaluate(facts)
            assertTrue(
                verdict is CandidateTagProvenanceVerdict.Refused,
                "rebuildFromCommit != sourceCommit must be refused, got " +
                    "${verdict::class.simpleName}: ${verdict.render()}",
            )
            val reason = (verdict as CandidateTagProvenanceVerdict.Refused).reason
            assertTrue(
                reason.contains("rebuilt", ignoreCase = true) ||
                    reason.contains("manifest", ignoreCase = true),
                "refusal reason must explain the rebuild/source mismatch, got: $reason",
            )
        }
    }

    @Nested
    @DisplayName("Unverifiable — version control cannot answer")
    inner class UnverifiableCases {

        @Test
        @DisplayName("source commit unknown — refuse the candidate before any other check")
        fun unverifiableSourceUnknown() {
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = SourceProvenance.PROVENANCE_UNKNOWN,
                tagPeelCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                rebuildFromCommit = null,
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val verdict = CandidateTagProvenance.evaluate(facts)
            assertTrue(
                verdict is CandidateTagProvenanceVerdict.Unverifiable,
                "unknown source commit must be Unverifiable, got ${verdict::class.simpleName}: " +
                    verdict.render(),
            )
        }
    }

    @Nested
    @DisplayName("Operator-facing diagnostic")
    inner class Diagnostics {

        @Test
        @DisplayName("Clean verdict carries all four fields in the rendered diagnostic")
        fun cleanDiagnosticCarriesFields() {
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                tagPeelCommit = "06854f6ff944ebeb1603ca5702eeb9c8659a0a19",
                rebuildFromCommit = null,
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val clean = CandidateTagProvenance.evaluate(facts)
            assertNotNull(clean as? CandidateTagProvenanceVerdict.Clean)
            val text = clean.render()
            assertTrue(text.contains("VERIFIED"))
            assertTrue(text.contains("06854f6f"))
            assertTrue(text.contains("4bec0844"))
        }

        @Test
        @DisplayName("Refused verdict renders a single-line reason for the build log")
        fun refusedDiagnosticIsSingleLine() {
            val facts = CandidateTagProvenanceFacts(
                sourceCommit = "2e088f674b66924382e15fd1680eeade773d98de",
                tagPeelCommit = "74c5331e2c242658f6b4c9e83f896ce5f8a4fe98",
                rebuildFromCommit = null,
                publishedSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
                rebuildSha256 = "4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6",
            )
            val refused = CandidateTagProvenance.evaluate(facts)
            assertNotNull(refused as? CandidateTagProvenanceVerdict.Refused)
            val text = refused.render()
            assertTrue(text.startsWith("tag/source provenance REFUSED:"))
            assertEquals(1, text.lines().size, "diagnostic must be single-line")
        }
    }
}
