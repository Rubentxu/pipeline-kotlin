package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.json.Json

/**
 * Deterministic serialisation of the published asset set.
 *
 * The same two properties the distribution manifest's codec holds, and for the
 * same reasons: the same set always produces byte-identical JSON, and an
 * unknown field is refused rather than ignored. A receipt that silently drops
 * a field it does not recognise is a receipt that says less than it appears
 * to, and the publisher reads this one to decide what it uploads.
 */
object PublishedAssetSetCodec {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(set: PublishedAssetSet): String =
        json.encodeToString(PublishedAssetSet.serializer(), set) + "\n"

    fun decode(text: String): PublishedAssetSet =
        json.decodeFromString(PublishedAssetSet.serializer(), text)
}
