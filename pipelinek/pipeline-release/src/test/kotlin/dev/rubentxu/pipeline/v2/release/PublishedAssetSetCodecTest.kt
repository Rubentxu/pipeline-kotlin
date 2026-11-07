package dev.rubentxu.pipeline.v2.release

import java.util.HexFormat
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The guarantee: the published asset set survives its own round trip, the
 * same set always yields the same bytes, and a document that carries a field
 * the schema does not declare is refused.
 *
 * The capacity this serves: a publication a consumer can check. The set
 * names the files a release publishes and the digest of each one; a codec
 * that drops a field hands the publisher a set that is narrower than the one
 * the gate measured, and the file it uploads is not the file the gate saw.
 *
 * These rows are pure: a set in, text out, text in, set out. No build, no
 * filesystem, no clock.
 */
class PublishedAssetSetCodecTest {

    private val set = PublishedAssetSet(
        distribution = asset("pipelinek-0.76.0.zip"),
        assets = listOf(
            asset("SHA256SUMS"),
            asset("pipelinek-0.76.0.sbom.json"),
        ),
    )

    private fun asset(name: String) = PublishedAsset(name, digest(name))

    private fun digest(seed: String): String =
        HexFormat.of().formatHex(seed.toByteArray()).padEnd(64, '0')

    @Test
    fun `the asset set survives the round trip`() {
        val text = PublishedAssetSetCodec.encode(set)

        assertEquals(set, PublishedAssetSetCodec.decode(text))
    }

    @Test
    fun `the same asset set always yields the same bytes`() {
        assertEquals(
            PublishedAssetSetCodec.encode(set),
            PublishedAssetSetCodec.encode(set),
        )
    }

    @Test
    fun `a document with a field the schema does not declare is refused`() {
        // The document is the encoded set with ONE key added, so the refusal
        // can only be about that key.
        val text = PublishedAssetSetCodec.encode(set)
            .replaceFirst("{", "{\n  \"unexpected\": true,")

        assertThrows(SerializationException::class.java) {
            PublishedAssetSetCodec.decode(text)
        }
    }
}
