package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * P0.2 / P-UAT-03 — the distribution manifest is generated from the build and
 * describes the real bytes. Promotion metadata cannot alter it.
 *
 * Two properties are proved here, and both are properties of the *type*, not
 * of reviewer discipline:
 *
 * 1. **Determinism** — the same value encodes to byte-identical JSON, so a
 *    rebuilt manifest for the same artifact is the same file and a digest over
 *    it is a stable identity.
 * 2. **Immutability** — there is no way to express a version that disagrees
 *    with the artifact, and the codec refuses unknown fields, so a promoter
 *    cannot smuggle in a "promotion" section. Promotion metadata lives in the
 *    candidate handoff descriptor (P0.4), a different document.
 */
class DistributionManifestTest {

    private fun manifest(
        version: String = "0.44.0",
        assetSha: String = "a".repeat(64),
        implementationVersion: String? = version,
    ) = DistributionManifest(
        version = ProductVersion.parseOrThrow(version),
        asset = DistributionAsset(
            name = "pipelinek-$version.zip",
            archiveRoot = "pipelinek-$version",
            size = 12_345_678L,
            sha256 = assetSha,
            implementationVersion = implementationVersion,
        ),
        source = DistributionSource(
            gitCommit = "b".repeat(40),
            candidateRef = "v$version",
            toolchain = "Gradle 9.0 / Kotlin 2.4.10 / JDK 21",
        ),
        sbom = DistributionArtifactRef("pipelinek-$version.sbom.json", "c".repeat(64)),
        sha256sums = DistributionArtifactRef("SHA256SUMS", "d".repeat(64)),
    )

    @Nested
    inner class Determinism {

        @Test
        fun `the same value always encodes to byte-identical JSON`() {
            val first = DistributionManifestCodec.encode(manifest())
            val second = DistributionManifestCodec.encode(manifest())
            assertEquals(first, second, "the manifest must be a deterministic function of the artifact")
        }

        @Test
        fun `encoding is stable across repeated calls in the same process`() {
            val m = manifest()
            val digests = (1..5).map { DistributionManifestCodec.encode(m).hashCode() }.distinct()
            assertEquals(1, digests.size, "encode must not embed a clock, a random id, or map ordering")
        }

        @Test
        fun `the encoded form round-trips to an equal value`() {
            val original = manifest()
            val decoded = DistributionManifestCodec.decode(DistributionManifestCodec.encode(original))
            assertEquals(original, decoded)
        }
    }

    @Nested
    inner class Immutability {

        @Test
        fun `P-UAT-03 promotion metadata cannot be added to the manifest`() {
            // A promoter who tries to record "this is now GA v0.44.0" in the
            // manifest must be refused: unknown keys are not tolerated. The
            // correct place for promotion state is the handoff descriptor.
            val tampered = """
                {
                  "schema_version": "pipelinek-distribution-manifest-2",
                  "version": "0.44.0",
                  "promotion": { "stable_tag": "v0.44.0", "outcome": "PROMOTED" },
                  "asset": {
                    "name": "pipelinek-0.44.0.zip",
                    "archive_root": "pipelinek-0.44.0",
                    "size": 12345678,
                    "sha256": "${"a".repeat(64)}",
                    "implementation_version": "0.44.0"
                  },
                  "source": { "git_commit": "${"b".repeat(40)}", "git_tag": "v0.44.0", "toolchain": "g" },
                  "sbom": { "name": "s.json", "sha256": "${"c".repeat(64)}" },
                  "sha256sums": { "name": "SHA256SUMS", "sha256": "${"d".repeat(64)}" }
                }
            """.trimIndent()
            val error = assertThrows(Exception::class.java) { DistributionManifestCodec.decode(tampered) }
            assertTrue(
                error.message.orEmpty().contains("promotion", ignoreCase = true),
                "the failure must name the offending field, got: ${error.message}",
            )
        }

        @Test
        fun `a manifest cannot declare a candidate-suffixed product version`() {
            // The type is the enforcement: ProductVersion.parseOrThrow refuses
            // -rcN, so there is no DistributionManifest whose version is a
            // candidate suffix. This is what removes the second identity that
            // the v0.43.0 incident laundered.
            val error = assertThrows(IllegalArgumentException::class.java) {
                manifest(version = "0.44.0-rc1")
            }
            assertTrue(
                error.message.orEmpty().contains("candidate"),
                "the diagnostic must explain the candidate rule, got: ${error.message}",
            )
        }

        @Test
        fun `changing the asset digest changes the manifest bytes`() {
            val a = DistributionManifestCodec.encode(manifest(assetSha = "a".repeat(64)))
            val b = DistributionManifestCodec.encode(manifest(assetSha = "e".repeat(64)))
            assertNotEquals(a, b, "the manifest must actually describe the asset it names")
        }

        @Test
        fun `the manifest records the embedded implementation version, not just the name`() {
            val m = manifest(implementationVersion = "0.44.0")
            val encoded = DistributionManifestCodec.encode(m)
            assertTrue(
                encoded.contains("\"implementation_version\": \"0.44.0\""),
                "the manifest must carry the JAR's own identity, got:\n$encoded",
            )
        }
    }

    @Nested
    inner class Shape {

        @Test
        fun `the manifest names its schema`() {
            val encoded = DistributionManifestCodec.encode(manifest())
            assertTrue(
                encoded.contains("\"schema_version\": \"pipelinek-distribution-manifest-2\""),
                "a consumer must be able to reject an unknown schema, got:\n$encoded",
            )
        }

        @Test
        fun `absent optional references serialise as null rather than vanishing`() {
            val m = manifest().copy(sbom = null, sha256sums = null)
            val encoded = DistributionManifestCodec.encode(m)
            assertTrue(
                encoded.contains("\"sbom\": null") && encoded.contains("\"sha256sums\": null"),
                "a consumer must be able to tell 'absent' from 'unknown schema', got:\n$encoded",
            )
        }
    }
}
