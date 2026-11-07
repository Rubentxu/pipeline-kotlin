package dev.rubentxu.pipeline.v2.release

import java.util.HexFormat
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The guarantee: the distribution manifest survives its own round trip, the
 * same manifest always yields the same bytes, and a document that carries a
 * field the schema does not declare is refused.
 *
 * The capacity this serves: a release whose manifest describes the bytes that
 * already exist. The manifest is written by the build and read by whoever
 * publishes it; a codec that drops a field hands the reader a narrower
 * document than the one that was written, and nobody can tell.
 *
 * These rows are pure: a manifest in, text out, text in, manifest out. No
 * build, no filesystem, no clock.
 */
class DistributionManifestCodecTest {

    private val manifest = DistributionManifest(
        version = requireNotNull(ProductVersion.parseOrNull("0.76.0")),
        asset = DistributionAsset(
            name = "pipelinek-0.76.0.zip",
            archiveRoot = "pipelinek-0.76.0",
            size = 1L,
            sha256 = digest("pipelinek-0.76.0.zip"),
            implementationVersion = "0.76.0",
        ),
        source = DistributionSource("commit", null, "toolchain"),
        sbom = ref("pipelinek-0.76.0.sbom.json"),
        sdk = ref("pipelinek-0.76.0.sdk.zip"),
        bom = ref("pipelinek-bom-0.76.0.json"),
        sha256sums = ref("SHA256SUMS"),
    )

    private fun ref(name: String) = DistributionArtifactRef(name, digest(name))

    private fun digest(seed: String): String =
        HexFormat.of().formatHex(seed.toByteArray()).padEnd(64, '0')

    @Test
    fun `the manifest survives the round trip`() {
        val text = DistributionManifestCodec.encode(manifest)

        assertEquals(manifest, DistributionManifestCodec.decode(text))
    }

    @Test
    fun `the same manifest always yields the same bytes`() {
        assertEquals(
            DistributionManifestCodec.encode(manifest),
            DistributionManifestCodec.encode(manifest),
        )
    }

    @Test
    fun `a document with a field the schema does not declare is refused`() {
        // The document is the encoded manifest with ONE key added, so the
        // refusal can only be about that key.
        val text = DistributionManifestCodec.encode(manifest)
            .replaceFirst("{", "{\n  \"unexpected\": true,")

        assertThrows(SerializationException::class.java) {
            DistributionManifestCodec.decode(text)
        }
    }
}
