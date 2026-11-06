package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.json.Json

/**
 * Deterministic serialisation of the documents of the Maven channel.
 *
 * The same two properties the other codecs of this cycle hold, and for the
 * same reasons: the same value always produces byte-identical JSON, and an
 * unknown field is refused rather than ignored.
 *
 * The determinism is load-bearing here in a way it is not for a receipt: the
 * request names the manifest by the digest of its BYTES, so the bytes the
 * producer wrote and the bytes the door reads have to be the same bytes. A
 * codec whose output depends on the order of a map would make that digest a
 * property of the serializer rather than of the document.
 *
 * A document that carries a field this build does not define is refused. A
 * manifest that silently drops a field nobody recognises says less than it
 * appears to, and the door reads it to decide what is published.
 */
object MavenPublicationCodec {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    /** The canonical textual form of a manifest. */
    fun encode(manifest: MavenPublicationManifest): String =
        json.encodeToString(MavenPublicationManifest.serializer(), manifest) + "\n"

    /** Read a manifest, refusing a document that is not one. */
    fun decodeManifest(text: String): MavenPublicationManifest =
        json.decodeFromString(MavenPublicationManifest.serializer(), text)

    /** The canonical textual form of a request. */
    fun encode(request: MavenPublicationRequest): String =
        json.encodeToString(MavenPublicationRequest.serializer(), request) + "\n"

    /** Read a request, refusing a document that is not one. */
    fun decodeRequest(text: String): MavenPublicationRequest =
        json.decodeFromString(MavenPublicationRequest.serializer(), text)
}
