package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which digest disagreed is the difference between two root causes, and a
 * consumer that groups [HandoffVerdict.Rejected] violations by
 * [HandoffViolationCode] cannot tell them apart when one code covers both.
 * The guarantee under test is narrow: a wrong `artifact.sha256` reports a code
 * that names the ARTIFACT, and a wrong `candidate_id` reports one that names
 * the CANDIDATE. Nothing here asserts that either check fires in production —
 * that is a separate question about the caller, and [CandidateMaterializer]
 * supplies all three digests from one measurement, which is why this file
 * constructs its own independent `measuredZipSha256`.
 * Pure: [evaluateCandidateHandoff] takes values and returns a decision. No
 * filesystem, no clock, no subprocess.
*/
class CandidateHandoffDigestAttributionTest {

    private val version = ProductVersion.parseOrThrow("0.44.0")

    /** 64 hex, so `CandidateId.fromDigest` accepts it. */
    private val measured = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun handoff(
        candidateDigest: String = measured,
        artifactDigest: String = measured,
        manifestDigest: String = measured,
    ): Pair<CandidateHandoff, DistributionManifest> {
        val artifact = CandidateArtifact(
            name = "pipelinek-0.44.0.zip",
            sha256 = artifactDigest,
            size = 4096,
            archiveRoot = "pipelinek-0.44.0",
            implementationVersion = "0.44.0",
        )
        val candidate = CandidateHandoff(
            candidateId = CandidateId.fromDigest(candidateDigest),
            releaseTrain = version,
            candidateSequence = 1,
            productVersion = version,
            sourceCommit = "0".repeat(40),
            artifact = artifact,
            distributionManifest = CandidateFileRef(
                name = "pipelinek-distribution-manifest.json",
                sha256 = other,
                builtAt = "2026-10-19T00:00:00Z",
            ),
            sbom = null,
            sdk = null,
            bom = null,
        )
        val manifest = DistributionManifest(
            version = version,
            asset = DistributionAsset(
                name = "pipelinek-0.44.0.zip",
                archiveRoot = "pipelinek-0.44.0",
                size = 4096,
                sha256 = manifestDigest,
                implementationVersion = "0.44.0",
            ),
            source = DistributionSource(
                gitCommit = "0".repeat(40),
                candidateRef = null,
                toolchain = "gradle 8.14.5",
            ),
            sbom = null,
            sdk = null,
            bom = null,
            sha256sums = null,
        )
        return candidate to manifest
    }

    private fun codesOf(verdict: HandoffVerdict): List<String> {
        val rejected = assertInstanceOf(HandoffVerdict.Rejected::class.java, verdict)
        return rejected.violations.map { it.code.name }
    }

    @Test
    fun `an artifact digest that disagrees with the measured ZIP is named as the artifact`() {
        val (candidate, manifest) = handoff(
            candidateDigest = measured,
            artifactDigest = other,
            manifestDigest = other,
        )

        val codes = codesOf(evaluateCandidateHandoff(candidate, manifest, measured))

        assertTrue(
            "ARTIFACT_DIGEST_NOT_MEASURED_ZIP" in codes,
            "the wrong field was artifact.sha256, so the violation must name the artifact; " +
                "codes were $codes. CANDIDATE_ID_NOT_ARTIFACT_DIGEST here would send an " +
                "operator to edit a candidate_id that is already correct",
        )
        assertTrue(
            "CANDIDATE_ID_NOT_ARTIFACT_DIGEST" !in codes,
            "candidate_id DOES match the measured digest in this fixture, so its code must " +
                "not appear; codes were $codes",
        )
    }

    @Test
    fun `a wrong candidate id and a wrong artifact digest are two distinguishable violations`() {
        val (candidate, manifest) = handoff(
            candidateDigest = other,
            artifactDigest = other,
            manifestDigest = other,
        )

        val codes = codesOf(evaluateCandidateHandoff(candidate, manifest, measured))

        assertTrue(
            "CANDIDATE_ID_NOT_ARTIFACT_DIGEST" in codes,
            "candidate_id is wrong in this fixture, so its code must appear; codes were $codes",
        )
        assertTrue(
            "ARTIFACT_DIGEST_NOT_MEASURED_ZIP" in codes,
            "artifact.sha256 is ALSO wrong here, and it must not be folded into the " +
                "candidate-id code or the two root causes become one bucket; codes were $codes",
        )
        assertEquals(
            codes.distinct().size,
            codes.size,
            "each violation in this fixture names a different field, so each needs its own " +
                "code; a duplicate code would make the count meaningless: $codes",
        )
    }

    @Test
    fun `a self-consistent handoff measured against its own digest is admissible`() {
        val (candidate, manifest) = handoff()

        val verdict = evaluateCandidateHandoff(candidate, manifest, measured)

        assertInstanceOf(HandoffVerdict.Admissible::class.java, verdict)
    }
}
