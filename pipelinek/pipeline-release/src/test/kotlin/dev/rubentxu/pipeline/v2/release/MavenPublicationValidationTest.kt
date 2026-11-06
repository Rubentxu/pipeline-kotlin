package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether a request may publish a Maven bundle, and what the refusal names
 * when it may not.
 *
 * Pure: no filesystem, no clock, no subprocess, no network. Every case below
 * is a set of values, and the decision is the one the publication door will
 * take.
 */
class MavenPublicationValidationTest {

    private val candidateDigest = "a".repeat(64)
    private val otherDigest = "b".repeat(64)
    private val candidate = CandidateId.fromDigest(candidateDigest)
    private val other = CandidateId.fromDigest(otherDigest)
    private val version = ProductVersion.parseOrThrow("0.93.0")
    private val previousVersion = ProductVersion.parseOrThrow("0.92.0")
    private val manifestDigest = "c".repeat(64)
    private val bundleDigest = "d".repeat(64)
    private val sdkZipDigest = "e".repeat(64)

    private val pom = MavenArtifactDigest(
        path = "dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.pom",
        kind = MavenArtifactKind.POM,
        sha256 = "1".repeat(64),
    )
    private val jar = MavenArtifactDigest(
        path = "dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.jar",
        kind = MavenArtifactKind.JAR,
        sha256 = "2".repeat(64),
    )

    private fun manifest(
        candidateId: CandidateId = candidate,
        productVersion: ProductVersion = version,
        artifacts: List<MavenArtifactDigest> = listOf(pom, jar),
        bundleSha256: String = bundleDigest,
    ): MavenPublicationManifest = MavenPublicationManifest(
        candidateId = candidateId,
        productVersion = productVersion,
        sdkZip = MavenFileDigest("pipelinek-0.93.0.sdk.zip", sdkZipDigest),
        artifacts = artifacts,
        bundleSha256 = bundleSha256,
    )

    private fun request(
        candidateId: CandidateId = candidate,
        productVersion: ProductVersion = version,
        manifestSha256: String = manifestDigest,
    ): MavenPublicationRequest = MavenPublicationRequest(
        candidateId = candidateId,
        productVersion = productVersion,
        manifestName = "maven-publication-manifest.json",
        manifestSha256 = manifestSha256,
    )

    private fun certifiedPart(part: CertificationPart): CertifiedPart = CertifiedPart(
        part = part,
        battery = CertifiedBattery(id = "${part.name.lowercase()}-battery", version = "1", scenarioCount = 1),
        status = CertificationPartStatus.CERTIFIED,
        runs = listOf(ScenarioExecution(scenario = "${part.name.lowercase()}-0", verdict = SCENARIO_VERDICT_PASSED)),
    )

    private fun certification(
        candidateId: CandidateId = candidate,
        parts: List<CertifiedPart> = listOf(
            certifiedPart(CertificationPart.CANARIES),
            certifiedPart(CertificationPart.SCENARIOS),
            CertifiedPart(
                part = CertificationPart.REVALIDATION,
                battery = null,
                status = CertificationPartStatus.NOT_APPLICABLE,
                runs = emptyList(),
            ),
        ),
    ): PromotionCertification = PromotionCertification(
        candidateId = candidateId,
        decision = CertificationDecision.CERTIFIED,
        parts = parts,
    )

    private fun observed(
        bundleArtifacts: List<MavenArtifactDigest> = listOf(pom, jar),
        bundleSha256: String = bundleDigest,
        published: MavenPublishedVersion = MavenPublishedVersion.Absent,
        certifiedContracts: List<MavenArtifactDigest> = listOf(pom),
        manifestSha256: String = manifestDigest,
    ): MavenObservedPublication = MavenObservedPublication(
        bundleArtifacts = bundleArtifacts,
        bundleSha256 = bundleSha256,
        published = published,
        certifiedContracts = certifiedContracts,
        manifestSha256 = manifestSha256,
    )

    private fun violationsOf(
        request: MavenPublicationRequest = request(),
        manifest: MavenPublicationManifest = manifest(),
        certification: PromotionCertification? = certification(),
        observed: MavenObservedPublication = observed(),
    ): List<MavenPublicationViolation> {
        val verdict = assertInstanceOf(
            MavenPublicationVerdict.Rejected::class.java,
            evaluateMavenPublication(request, manifest, certification, observed),
        )
        return verdict.violations
    }

    @Test
    fun `a request that matches every fact is admitted`() {
        val verdict = evaluateMavenPublication(
            request(),
            manifest(),
            certification(),
            observed(),
        )
        assertInstanceOf(MavenPublicationVerdict.Admitted::class.java, verdict)
    }

    @Test
    fun `a request for a version that is not the candidate's names the version`() {
        val codes = violationsOf(request = request(productVersion = previousVersion)).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.VERSION_IS_ANOTHER), "got $codes")
    }

    @Test
    fun `a request without a certification is refused`() {
        val codes = violationsOf(certification = null).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.CERTIFICATION_ABSENT), "got $codes")
    }

    @Test
    fun `a certification of another candidate is refused by name`() {
        val codes = violationsOf(certification = certification(candidateId = other)).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.CERTIFICATION_IS_ANOTHER), "got $codes")
    }

    @Test
    fun `a partial certification is refused and the part that is missing is named`() {
        val violations = violationsOf(
            certification = certification(
                parts = listOf(certifiedPart(CertificationPart.CANARIES)),
            ),
        )
        val codes = violations.map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.CERTIFICATION_DOES_NOT_ADMIT), "got $codes")

        val detail = violations.first {
            it.code == MavenPublicationViolationCode.CERTIFICATION_DOES_NOT_ADMIT
        }.detail
        assertTrue(detail.contains("scenarios"), "the refusal must name the part: $detail")
    }

    @Test
    fun `a manifest of another candidate is refused`() {
        val codes = violationsOf(manifest = manifest(candidateId = other)).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.CANDIDATE_IS_ANOTHER), "got $codes")
    }

    @Test
    fun `a manifest that is not the one the request names is refused`() {
        val codes = violationsOf(observed = observed(manifestSha256 = "f".repeat(64))).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.MANIFEST_DIGEST_DISAGREES), "got $codes")
    }

    @Test
    fun `contracts that are not the candidate's are refused`() {
        val codes = violationsOf(observed = observed(certifiedContracts = emptyList())).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.CONTRACT_DISAGREES), "got $codes")
    }

    @Test
    fun `bytes that are not the ones the manifest describes are refused`() {
        val codes = violationsOf(observed = observed(bundleArtifacts = listOf(pom))).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.ARTIFACT_DISAGREES), "got $codes")
    }

    @Test
    fun `a bundle that was rebuilt after the manifest was written is refused`() {
        val codes = violationsOf(observed = observed(bundleSha256 = "0".repeat(64))).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.BUNDLE_DISAGREES), "got $codes")
    }

    @Test
    fun `a version already published with these bytes is not published again`() {
        val codes = violationsOf(
            observed = observed(published = MavenPublishedVersion.Present(listOf(pom, jar))),
        ).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.VERSION_ALREADY_PUBLISHED), "got $codes")
    }

    @Test
    fun `a version already published with other bytes is refused as a conflict`() {
        val codes = violationsOf(
            observed = observed(
                published = MavenPublishedVersion.Present(
                    listOf(pom.copy(sha256 = "9".repeat(64)), jar),
                ),
            ),
        ).map { it.code }
        assertTrue(
            codes.contains(MavenPublicationViolationCode.VERSION_PUBLISHED_WITH_OTHER_BYTES),
            "got $codes",
        )
    }

    @Test
    fun `every refusal is named and the diagnostic says what to do`() {
        val verdict = assertInstanceOf(
            MavenPublicationVerdict.Rejected::class.java,
            evaluateMavenPublication(
                request(productVersion = previousVersion),
                manifest(candidateId = other),
                null,
                observed(
                    bundleArtifacts = emptyList(),
                    bundleSha256 = "0".repeat(64),
                    published = MavenPublishedVersion.Present(emptyList()),
                    certifiedContracts = emptyList(),
                ),
            ),
        )
        val rendered = verdict.render()
        assertTrue(rendered.contains("REJECTED"), rendered)
        assertTrue(rendered.contains("MUST NOT publish"), rendered)
        assertEquals(7, verdict.violations.size)
    }
}
