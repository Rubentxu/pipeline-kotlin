package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether the repository a consumer resolves from serves the bytes the
 * publication manifest declares.
 *
 * Pure: no filesystem, no clock, no network, no subprocess. Every case below
 * is a set of values, and the decision is the one the channel's consumer-side
 * check will take.
 */
class MavenResolutionValidationTest {

    private val candidate = CandidateId.fromDigest("a".repeat(64))
    private val version = ProductVersion.parseOrThrow("0.96.0")
    private val sdkZipDigest = "e".repeat(64)
    private val bundleDigest = "d".repeat(64)

    private val pom = MavenArtifactDigest(
        path = "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.pom",
        kind = MavenArtifactKind.POM,
        sha256 = "1".repeat(64),
    )
    private val jar = MavenArtifactDigest(
        path = "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.jar",
        kind = MavenArtifactKind.JAR,
        sha256 = "2".repeat(64),
    )

    private fun manifest(
        artifacts: List<MavenArtifactDigest> = listOf(pom, jar),
    ): MavenPublicationManifest = MavenPublicationManifest(
        candidateId = candidate,
        productVersion = version,
        sdkZip = MavenFileDigest("pipelinek-0.96.0.sdk.zip", sdkZipDigest),
        artifacts = artifacts,
        bundleSha256 = bundleDigest,
    )

    private fun resolved(
        artifacts: List<MavenArtifactDigest> = listOf(pom, jar),
    ): MavenResolvedVersion = MavenResolvedVersion.Resolved(artifacts)

    private fun violationsOf(
        manifest: MavenPublicationManifest = manifest(),
        resolved: MavenResolvedVersion = resolved(),
    ): List<MavenResolutionViolation> {
        val verdict = assertInstanceOf(
            MavenResolutionVerdict.Rejected::class.java,
            evaluateMavenResolution(manifest, resolved),
        )
        return verdict.violations
    }

    @Test
    fun `a repository that serves every artifact with the declared digest is verified`() {
        val verdict = evaluateMavenResolution(manifest(), resolved())
        assertInstanceOf(MavenResolutionVerdict.Verified::class.java, verdict)
    }

    @Test
    fun `a repository that serves none of the artifacts says the version is absent`() {
        val codes = violationsOf(resolved = MavenResolvedVersion.Absent).map { it.code }
        assertTrue(
            codes.contains(MavenResolutionViolationCode.VERSION_ABSENT),
            "got $codes",
        )
    }

    @Test
    fun `an artifact the repository does not serve is named by its path`() {
        val violations = violationsOf(resolved = resolved(artifacts = listOf(pom)))
        val absent = violations.first {
            it.code == MavenResolutionViolationCode.ARTIFACT_ABSENT
        }
        assertTrue(absent.detail.contains(jar.path), "the refusal must name the path: ${absent.detail}")
    }

    @Test
    fun `an artifact served with other bytes is named by both digests`() {
        val other = jar.copy(sha256 = "9".repeat(64))
        val violations = violationsOf(resolved = resolved(artifacts = listOf(pom, other)))
        val disagrees = violations.first {
            it.code == MavenResolutionViolationCode.ARTIFACT_DISAGREES
        }
        assertTrue(disagrees.detail.contains(jar.sha256), "the refusal must name the declared digest")
        assertTrue(disagrees.detail.contains(other.sha256), "the refusal must name the served digest")
    }

    @Test
    fun `every problem is named, not only the first`() {
        val violations = violationsOf(
            resolved = resolved(artifacts = listOf(pom.copy(sha256 = "9".repeat(64)))),
        )
        val codes = violations.map { it.code }
        assertTrue(
            codes.contains(MavenResolutionViolationCode.ARTIFACT_ABSENT),
            "got $codes",
        )
        assertTrue(
            codes.contains(MavenResolutionViolationCode.ARTIFACT_DISAGREES),
            "got $codes",
        )
    }

    @Test
    fun `the verdict says what was measured`() {
        val verified = evaluateMavenResolution(manifest(), resolved()).render()
        assertTrue(verified.contains("VERIFIED"), verified)
        assertTrue(verified.contains("0.96.0"), verified)
        assertTrue(verified.contains("2"), verified)

        val rejected = evaluateMavenResolution(
            manifest(),
            MavenResolvedVersion.Absent,
        ).render()
        assertTrue(rejected.contains("REJECTED"), rejected)
        assertTrue(rejected.contains("VERSION_ABSENT"), rejected)
    }

    @Test
    fun `a version with no artifact cannot be verified`() {
        val codes = violationsOf(
            manifest = manifest(artifacts = listOf(pom, jar)),
            resolved = MavenResolvedVersion.Resolved(emptyList()),
        ).map { it.code }
        assertEquals(2, codes.size, "got $codes")
    }
}
