package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
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
    private val module = MavenArtifactDigest(
        path = "dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.module",
        kind = MavenArtifactKind.MODULE,
        sha256 = "3".repeat(64),
    )

    /**
     * A file the bundle carries and the manifest does not declare: the module
     * metadata of the same coordinate, under bytes of its own.
     *
     * It is the "unexpected" side of the comparison, and it is a REAL file of
     * the bundle rather than a fiction, so the refusal that names it is the
     * one a publisher would read.
     */
    private val extraModule = MavenArtifactDigest(
        path = "dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.module",
        kind = MavenArtifactKind.MODULE,
        sha256 = "4".repeat(64),
    )

    private fun manifest(
        candidateId: CandidateId = candidate,
        productVersion: ProductVersion = version,
        artifacts: List<MavenArtifactDigest> = listOf(pom, jar),
        bundleSha256: String = bundleDigest,
        schemaVersion: String = MavenPublicationManifest.SCHEMA_VERSION,
    ): MavenPublicationManifest = MavenPublicationManifest(
        schemaVersion = schemaVersion,
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
                reason = "the candidate fixes no reported defect",
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

    /**
     * The detail of ONE violation, so a row can read what the refusal SAYS
     * and not only which fact it reports.
     *
     * The code is the fact and the detail is the diagnosis, and a channel
     * whose diagnosis is empty is a channel that tells a publisher that
     * something is wrong without telling it what.
     */
    private fun detailOf(
        code: MavenPublicationViolationCode,
        request: MavenPublicationRequest = request(),
        manifest: MavenPublicationManifest = manifest(),
        observed: MavenObservedPublication = observed(),
    ): String = violationsOf(request = request, manifest = manifest, observed = observed)
        .first { it.code == code }
        .detail

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
    fun `a bundle that carries the module metadata is published with it declared`() {
        val verdict = evaluateMavenPublication(
            request(),
            manifest(artifacts = listOf(pom, jar, module)),
            certification(),
            observed(
                bundleArtifacts = listOf(pom, jar, module),
                certifiedContracts = listOf(pom, module),
            ),
        )
        assertInstanceOf(MavenPublicationVerdict.Admitted::class.java, verdict)
    }

    @Test
    fun `a bundle whose module metadata is not the candidate's is refused`() {
        val codes = violationsOf(
            manifest = manifest(artifacts = listOf(pom, jar, module)),
            observed = observed(
                bundleArtifacts = listOf(pom, jar, module),
                certifiedContracts = listOf(pom, module.copy(sha256 = "4".repeat(64))),
            ),
        ).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.CONTRACT_DISAGREES), "got $codes")
    }

    @Test
    fun `a manifest that declares a module metadata the candidate does not carry is refused`() {
        val codes = violationsOf(
            manifest = manifest(artifacts = listOf(pom, jar, module)),
            observed = observed(
                bundleArtifacts = listOf(pom, jar, module),
                certifiedContracts = listOf(pom),
            ),
        ).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.CONTRACT_DISAGREES), "got $codes")
    }

    @Test
    fun `a bundle that does not carry the declared module metadata is refused`() {
        val codes = violationsOf(
            manifest = manifest(artifacts = listOf(pom, jar, module)),
            observed = observed(
                bundleArtifacts = listOf(pom, jar),
                certifiedContracts = listOf(pom, module),
            ),
        ).map { it.code }
        assertTrue(codes.contains(MavenPublicationViolationCode.ARTIFACT_DISAGREES), "got $codes")
    }

    @Test
    fun `a manifest of another shape is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            manifest(schemaVersion = "pipelinek-maven-publication-manifest-1")
        }
        assertTrue(
            refusal.message?.contains("pipelinek-maven-publication-manifest-1") == true,
            refusal.message,
        )
    }

    // The message of this refusal is pinned CHARACTER BY CHARACTER, and that
    // is deliberate: the message is the only thing this diagnostic produces,
    // so a pin on the whole of it is what proves that a change in the way the
    // message is built changes nothing a reader can see. The row that refuses
    // a path of NO kind pins the other configuration of the same message, and
    // between them the two configurations are the only ones that can reach
    // it: a path of a kind the artifact declares never builds a message.
    @Test
    fun `an artifact whose kind contradicts its path is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MavenArtifactDigest(
                path = "dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.module",
                kind = MavenArtifactKind.JAR,
                sha256 = "3".repeat(64),
            )
        }
        assertTrue(refusal.message?.contains("MODULE") == true, refusal.message)
        assertTrue(
            refusal.message?.contains("is a MODULE and the artifact declares JAR") == true,
            refusal.message,
        )
        assertEquals(
            "the path 'dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.module' " +
                "is a MODULE and the artifact declares JAR. The kind of a file is what its name " +
                "says it is, and a manifest that calls a file something else describes bytes that " +
                "nobody can compare with the measurement of the bundle.",
            refusal.message,
        )
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
    fun `the request names no transport and carries nothing else`() {
        val encoded = MavenPublicationCodec.encode(request())

        val keys = Regex("\"([a-z0-9_]+)\":").findAll(encoded).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "schema_version",
                "candidate_id",
                "product_version",
                "manifest_name",
                "manifest_sha256",
            ),
            keys,
        )

        val lowered = encoded.lowercase()
        listOf("github", "issues", "pull", "actions", "http", "inbox").forEach { forbidden ->
            assertTrue(!lowered.contains(forbidden), "the request names a transport: $forbidden")
        }
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

    // ── the invariants of the documents ───────────────────────────────────────
    //
    // A document that can be built in a shape the channel cannot honour is a
    // hole in the channel: the decision that reads it would be deciding about
    // bytes that cannot exist. Each row below fixes ONE invariant, and the
    // refusal is checked by WHAT IT SAYS, because a refusal that does not name
    // the broken invariant is a refusal the operator cannot act on.

    @Test
    fun `a manifest that publishes nothing is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            manifest(artifacts = emptyList())
        }
        assertTrue(refusal.message?.contains("artifacts is empty") == true, refusal.message)
    }

    @Test
    fun `two artifacts of one manifest cannot carry the same path`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            manifest(artifacts = listOf(pom, pom))
        }
        assertTrue(refusal.message?.contains("same path") == true, refusal.message)
    }

    @Test
    fun `a bundle digest that is not a SHA-256 is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            manifest(bundleSha256 = "not-a-sha256")
        }
        assertTrue(refusal.message?.contains("bundle_sha256") == true, refusal.message)
    }

    @Test
    fun `a request that names no manifest is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MavenPublicationRequest(
                candidateId = candidate,
                productVersion = version,
                manifestName = " ",
                manifestSha256 = manifestDigest,
            )
        }
        assertTrue(refusal.message?.contains("manifest_name is blank") == true, refusal.message)
    }

    @Test
    fun `a request whose manifest digest is not a SHA-256 is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MavenPublicationRequest(
                candidateId = candidate,
                productVersion = version,
                manifestName = "maven-publication-manifest.json",
                manifestSha256 = "not-a-sha256",
            )
        }
        assertTrue(refusal.message?.contains("manifest_sha256") == true, refusal.message)
    }

    @Test
    fun `an artifact without a path is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MavenArtifactDigest(path = " ", kind = MavenArtifactKind.JAR, sha256 = "1".repeat(64))
        }
        assertTrue(refusal.message?.contains("path is blank") == true, refusal.message)
    }

    @Test
    fun `an artifact whose digest is not a SHA-256 is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MavenArtifactDigest(
                path = "dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.jar",
                kind = MavenArtifactKind.JAR,
                sha256 = "not-a-sha256",
            )
        }
        assertTrue(refusal.message?.contains("sha256 must be") == true, refusal.message)
    }

    @Test
    fun `an artifact whose path is of no kind is refused by name`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MavenArtifactDigest(
                path = "dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.txt",
                kind = MavenArtifactKind.JAR,
                sha256 = "1".repeat(64),
            )
        }
        assertTrue(refusal.message?.contains("file of no kind") == true, refusal.message)
        assertTrue(
            refusal.message?.contains("is a file of no kind and the artifact declares JAR") == true,
            refusal.message,
        )
        assertEquals(
            "the path 'dev/rubentxu/pipeline/v2/pipeline-domain/0.93.0/pipeline-domain-0.93.0.txt' " +
                "is a file of no kind and the artifact declares JAR. The kind of a file is what " +
                "its name says it is, and a manifest that calls a file something else describes " +
                "bytes that nobody can compare with the measurement of the bundle.",
            refusal.message,
        )
    }

    @Test
    fun `a document says the shape it is`() {
        assertEquals(MavenPublicationManifest.SCHEMA_VERSION, manifest().schemaVersion)
        assertEquals(MavenPublicationRequest.SCHEMA_VERSION, request().schemaVersion)
    }

    // ── the diagnostic that names what differs ────────────────────────────────
    //
    // The comparison is by path and by digest, and it names what is missing,
    // what is extra and what changed. Each row fixes ONE partition of that
    // comparison: a diagnostic that names the missing file when nothing is
    // missing is a diagnostic that sends the publisher to the wrong place.

    @Test
    fun `a difference that is only a missing file names it and nothing else`() {
        val detail = detailOf(
            MavenPublicationViolationCode.ARTIFACT_DISAGREES,
            observed = observed(bundleArtifacts = listOf(pom)),
        )
        assertTrue(detail.contains("missing ${jar.path}"), detail)
        assertTrue(!detail.contains("unexpected"), detail)
        assertTrue(!detail.contains("changed"), detail)
        assertTrue(!detail.contains(";"), detail)
    }

    @Test
    fun `a difference that is only an unexpected file names it and nothing else`() {
        val detail = detailOf(
            MavenPublicationViolationCode.ARTIFACT_DISAGREES,
            observed = observed(bundleArtifacts = listOf(pom, jar, extraModule)),
        )
        assertTrue(detail.contains("unexpected ${extraModule.path}"), detail)
        assertTrue(!detail.contains("missing"), detail)
        assertTrue(!detail.contains("changed"), detail)
        assertTrue(!detail.contains(";"), detail)
    }

    @Test
    fun `a difference that is a change names both digests`() {
        val changed = pom.copy(sha256 = "7".repeat(64))
        val detail = detailOf(
            MavenPublicationViolationCode.ARTIFACT_DISAGREES,
            observed = observed(bundleArtifacts = listOf(changed, jar)),
        )
        assertTrue(detail.contains("changed ${pom.path}"), detail)
        assertTrue(detail.contains(pom.sha256), detail)
        assertTrue(detail.contains(changed.sha256), detail)
        assertTrue(!detail.contains("missing"), detail)
        assertTrue(!detail.contains("unexpected"), detail)
        assertTrue(!detail.contains(";"), detail)
    }

    @Test
    fun `a difference with two parts separates them`() {
        val detail = detailOf(
            MavenPublicationViolationCode.ARTIFACT_DISAGREES,
            observed = observed(bundleArtifacts = listOf(pom, extraModule)),
        )
        assertTrue(detail.contains("missing ${jar.path}"), detail)
        assertTrue(detail.contains("unexpected ${extraModule.path}"), detail)
        assertTrue(detail.contains("; "), detail)
    }

    @Test
    fun `a difference that is missing and changed separates them`() {
        val changed = pom.copy(sha256 = "7".repeat(64))
        val detail = detailOf(
            MavenPublicationViolationCode.ARTIFACT_DISAGREES,
            observed = observed(bundleArtifacts = listOf(changed)),
        )
        assertTrue(detail.contains("missing ${jar.path}"), detail)
        assertTrue(detail.contains("changed ${pom.path}"), detail)
        assertTrue(detail.contains("; "), detail)
    }

    // ── the diagnostics of the decision ──────────────────────────────────────

    @Test
    fun `an admitted publication renders what it publishes`() {
        val verdict = assertInstanceOf(
            MavenPublicationVerdict.Admitted::class.java,
            evaluateMavenPublication(request(), manifest(), certification(), observed()),
        )
        val rendered = verdict.render()
        assertTrue(rendered.contains("ADMITTED"), rendered)
        assertTrue(rendered.contains(candidate.canonical), rendered)
        assertTrue(rendered.contains(version.value), rendered)
        assertTrue(rendered.contains("2 artifact(s)"), rendered)
        assertTrue(rendered.contains(bundleDigest), rendered)
    }

    @Test
    fun `a certification of another candidate is told apart from one that does not admit`() {
        val detail = violationsOf(certification = certification(candidateId = other))
            .first { it.code == MavenPublicationViolationCode.CERTIFICATION_IS_ANOTHER }
            .detail
        assertTrue(detail.contains("names another candidate"), detail)
    }

    @Test
    fun `a refusal that names a manifest names the one the request names`() {
        val detail = detailOf(
            MavenPublicationViolationCode.MANIFEST_DIGEST_DISAGREES,
            observed = observed(manifestSha256 = "f".repeat(64)),
        )
        assertTrue(detail.contains("maven-publication-manifest.json"), detail)
    }
}
