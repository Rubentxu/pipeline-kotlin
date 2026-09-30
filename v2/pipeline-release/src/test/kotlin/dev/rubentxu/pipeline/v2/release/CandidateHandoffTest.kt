package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * P0.4 / P-UAT-04 — the candidate handoff is complete, and its identity is the
 * artifact digest rather than a name.
 *
 * The load-bearing case is the laundering candidate: a handoff that names
 * `0.44.0` in every metadata field while the bytes it points at carry
 * `0.43.0-rc1`. That is the v0.43.0 incident in producer-side form, and it
 * must be rejected at emission time.
 */
class CandidateHandoffTest {

    private val digest = "1".repeat(64)

    private fun handoff(
        version: String = "0.44.0",
        candidateId: String = digest,
        artifactSha: String = digest,
        archiveRoot: String? = null,
        assetName: String? = null,
        embedded: String? = version,
    ) = CandidateHandoff(
        candidateId = CandidateId.fromDigest(candidateId),
        releaseTrain = ProductVersion.parseOrThrow(version),
        candidateSequence = 3,
        productVersion = ProductVersion.parseOrThrow(version),
        sourceCommit = "2".repeat(40),
        artifact = CandidateArtifact(
            name = assetName ?: "pipelinek-$version.zip",
            sha256 = artifactSha,
            size = 12_345_678L,
            archiveRoot = archiveRoot ?: "pipelinek-$version",
            implementationVersion = embedded,
        ),
        distributionManifest = CandidateFileRef(
            "distribution-manifest.json",
            "3".repeat(64),
            "2026-09-30T09:00:00Z",
        ),
        sbom = CandidateFileRef("pipelinek-$version.sbom.json", "4".repeat(64), "2026-09-30T09:00:00Z"),
    )

    private fun manifest(version: String = "0.44.0", assetSha: String = digest) = DistributionManifest(
        version = ProductVersion.parseOrThrow(version),
        asset = DistributionAsset(
            name = "pipelinek-$version.zip",
            archiveRoot = "pipelinek-$version",
            size = 12_345_678L,
            sha256 = assetSha,
            implementationVersion = version,
        ),
        source = DistributionSource("2".repeat(40), "v$version", "gradle"),
        sbom = null,
        sha256sums = null,
    )

    @Nested
    inner class Admissible {

        @Test
        fun `P-UAT-04 a complete consistent handoff is admissible`() {
            val verdict = evaluateCandidateHandoff(handoff(), manifest(), digest)
            assertTrue(verdict is HandoffVerdict.Admissible, "got: ${verdict.render()}")
        }

        @Test
        fun `the handoff round-trips through its codec`() {
            val h = handoff()
            val decoded = CandidateHandoffCodec.decode(CandidateHandoffCodec.encode(h))
            assertEquals(h, decoded)
        }

        @Test
        fun `the handoff names its schema and the canonical candidate id`() {
            val encoded = CandidateHandoffCodec.encode(handoff())
            assertTrue(encoded.contains("\"schema_version\": \"pipelinek-candidate-2\""), encoded)
            assertTrue(encoded.contains("\"candidate_id\": \"sha256:$digest\""), encoded)
        }
    }

    @Nested
    inner class IdentityIsMaterial {

        @Test
        fun `a candidate id that is not the artifact digest is rejected`() {
            val verdict = evaluateCandidateHandoff(
                handoff(candidateId = "f".repeat(64)),
                manifest(),
                digest,
            )
            val rejected = assertInstanceOf(HandoffVerdict.Rejected::class.java, verdict)
            assertTrue(
                rejected.violations.any { it.code == HandoffViolationCode.CANDIDATE_ID_NOT_ARTIFACT_DIGEST },
                "got: ${rejected.render()}",
            )
        }

        @Test
        fun `a non-digest candidate id cannot even be constructed`() {
            // A branch, tag, Git SHA or RC name is not a candidate identity.
            // The type refuses them, so this is unrepresentable rather than
            // merely rejected later.
            listOf("main", "v0.44.0", "candidate-0.44.0-3", "latest", "2".repeat(40)).forEach { bad ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    CandidateId.fromDigest(bad)
                }
                assertTrue(
                    error.message.orEmpty().contains("not a candidate identity"),
                    "the diagnostic for '$bad' must explain the rule, got: ${error.message}",
                )
            }
        }

        @Test
        fun `a candidate id accepts the canonical sha256-prefixed form`() {
            val id = CandidateId.fromDigest("sha256:${"a".repeat(64)}")
            assertEquals("a".repeat(64), id.digest)
            assertEquals("sha256:${"a".repeat(64)}", id.canonical)
        }
    }

    @Nested
    inner class Laundering {

        @Test
        fun `P-UAT-08 a candidate whose bytes are rc but whose metadata says GA is rejected`() {
            // Every metadata field says 0.44.0; the bytes say 0.43.0-rc1.
            val verdict = evaluateCandidateHandoff(
                handoff(version = "0.44.0", embedded = "0.43.0-rc1"),
                manifest(version = "0.44.0"),
                digest,
            )
            val rejected = assertInstanceOf(HandoffVerdict.Rejected::class.java, verdict)
            assertTrue(
                rejected.violations.any {
                    it.code == HandoffViolationCode.IMPLEMENTATION_VERSION_DISAGREES
                },
                "the laundering must be named explicitly, got: ${rejected.render()}",
            )
        }

        @Test
        fun `a missing embedded identity is rejected, never treated as agreement`() {
            val verdict = evaluateCandidateHandoff(
                handoff(embedded = null),
                manifest(),
                digest,
            )
            val rejected = assertInstanceOf(HandoffVerdict.Rejected::class.java, verdict)
            assertTrue(
                rejected.violations.any {
                    it.code == HandoffViolationCode.IMPLEMENTATION_VERSION_MISSING
                },
                "got: ${rejected.render()}",
            )
        }

        @Test
        fun `a handoff whose manifest declares a different product version is rejected`() {
            val verdict = evaluateCandidateHandoff(
                handoff(version = "0.44.0"),
                manifest(version = "0.43.0"),
                digest,
            )
            val rejected = assertInstanceOf(HandoffVerdict.Rejected::class.java, verdict)
            assertTrue(
                rejected.violations.any {
                    it.code == HandoffViolationCode.PRODUCT_VERSION_DISAGREES_WITH_MANIFEST
                },
                "got: ${rejected.render()}",
            )
        }

        @Test
        fun `a handoff whose manifest asset digest differs is rejected`() {
            val verdict = evaluateCandidateHandoff(
                handoff(),
                manifest(assetSha = "9".repeat(64)),
                digest,
            )
            val rejected = assertInstanceOf(HandoffVerdict.Rejected::class.java, verdict)
            assertTrue(
                rejected.violations.any {
                    it.code == HandoffViolationCode.ASSET_DIGEST_DISAGREES_WITH_MANIFEST
                },
                "got: ${rejected.render()}",
            )
        }

        @Test
        fun `an asset renamed away from its product version is rejected`() {
            val verdict = evaluateCandidateHandoff(
                handoff(assetName = "pipelinek-0.43.0.zip"),
                manifest(),
                digest,
            )
            val rejected = assertInstanceOf(HandoffVerdict.Rejected::class.java, verdict)
            assertTrue(
                rejected.violations.any { it.code == HandoffViolationCode.ASSET_NAME_DISAGREES },
                "got: ${rejected.render()}",
            )
        }
    }

    @Nested
    inner class StructuralInvariants {

        @Test
        fun `a release train that is not the product version cannot be constructed`() {
            val error = assertThrows(IllegalArgumentException::class.java) {
                handoff().copy(releaseTrain = ProductVersion.parseOrThrow("0.45.0"))
            }
            assertTrue(
                error.message.orEmpty().contains("release_train"),
                "got: ${error.message}",
            )
        }

        @Test
        fun `a non-positive candidate sequence cannot be constructed`() {
            assertThrows(IllegalArgumentException::class.java) {
                handoff().copy(candidateSequence = 0)
            }
        }

        @Test
        fun `every violation is reported, not only the first`() {
            val verdict = evaluateCandidateHandoff(
                handoff(
                    candidateId = "f".repeat(64),
                    embedded = "0.43.0-rc1",
                    assetName = "pipelinek-0.43.0.zip",
                    archiveRoot = "pipelinek-0.43.0",
                ),
                manifest(),
                digest,
            )
            val rejected = assertInstanceOf(HandoffVerdict.Rejected::class.java, verdict)
            val codes = rejected.violations.map { it.code }.toSet()
            assertTrue(
                codes.containsAll(
                    listOf(
                        HandoffViolationCode.CANDIDATE_ID_NOT_ARTIFACT_DIGEST,
                        HandoffViolationCode.IMPLEMENTATION_VERSION_DISAGREES,
                        HandoffViolationCode.ASSET_NAME_DISAGREES,
                        HandoffViolationCode.ARCHIVE_ROOT_DISAGREES,
                    )
                ),
                "one build must reveal every problem; got: $codes",
            )
        }
    }
}
