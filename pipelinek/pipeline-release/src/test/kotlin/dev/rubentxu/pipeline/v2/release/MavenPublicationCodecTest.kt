package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The document the door reads: what it carries, and what it refuses.
 *
 * The shape is the field that names the vocabulary a document speaks, so a
 * document that does not declare it is not a document of any shape. The
 * reader has to refuse it rather than assume the one this build speaks: the
 * assumption is what would let a manifest of another shape in through the
 * very field that names the shape.
 */
class MavenPublicationCodecTest {

    private val manifest = MavenPublicationManifest(
        schemaVersion = MavenPublicationManifest.SCHEMA_VERSION,
        candidateId = CandidateId.fromDigest("a".repeat(64)),
        productVersion = ProductVersion.parseOrThrow("0.96.0"),
        sdkZip = MavenFileDigest("pipelinek-0.96.0.sdk.zip", "e".repeat(64)),
        artifacts = listOf(
            MavenArtifactDigest(
                path = "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.pom",
                kind = MavenArtifactKind.POM,
                sha256 = "1".repeat(64),
            ),
        ),
        bundleSha256 = "d".repeat(64),
    )

    @Test
    fun `the manifest carries its shape`() {
        val encoded = MavenPublicationCodec.encode(manifest)

        assertTrue(encoded.contains("\"schema_version\""), encoded)
        assertTrue(encoded.contains(MavenPublicationManifest.SCHEMA_VERSION), encoded)
    }

    @Test
    fun `a manifest that declares no shape is not a manifest`() {
        val text = MavenPublicationCodec.encode(manifest)
            .replace(Regex("\"schema_version\"\\s*:\\s*\"[^\"]*\",\\s*"), "")

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MavenPublicationCodec.decodeManifest(text)
        }
        assertTrue(refusal.message?.contains("schema_version") == true, refusal.message)
    }

    @Test
    fun `a manifest of another shape is not a manifest`() {
        val text = MavenPublicationCodec.encode(manifest)
            .replace(
                MavenPublicationManifest.SCHEMA_VERSION,
                "pipelinek-maven-publication-manifest-1",
            )

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MavenPublicationCodec.decodeManifest(text)
        }
        assertTrue(
            refusal.message?.contains("pipelinek-maven-publication-manifest-1") == true,
            refusal.message,
        )
    }
}
