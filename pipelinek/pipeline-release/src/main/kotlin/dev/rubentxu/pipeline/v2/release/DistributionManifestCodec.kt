package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.json.Json

/**
 * P0.2 — deterministic serialisation of the distribution manifest.
 *
 * The manifest is a *description of bytes that already exist*. Two properties
 * matter and both are properties of this codec, not of convention:
 *
 * 1. **Determinism** — the same value always produces byte-identical JSON.
 *    `encodeDefaults` and the fixed key order of [DistributionManifest] make
 *    that true, so a rebuilt manifest of the same artifact is the same file.
 * 2. **Immutability** — nothing in this codec can express a version that
 *    differs from the artifact's own identity, because the version is not a
 *    free string here: it is the [ProductVersion] the build compiled in.
 *
 * Promotion metadata is a different document (the candidate handoff
 * descriptor, P0.4), so a promoter cannot "update" this file. If they tried,
 * the `candidate_id` in the handoff — which is the ZIP digest — would no
 * longer describe the artifact this manifest names.
 */
object DistributionManifestCodec {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        // Refuse to silently accept a manifest that declares a different
        // schema. An unknown field is a promotion attempt, not a forward-
        // compatible extension, and must be loud.
        ignoreUnknownKeys = false
    }

    fun encode(manifest: DistributionManifest): String =
        json.encodeToString(DistributionManifest.serializer(), manifest) + "\n"

    fun decode(text: String): DistributionManifest =
        json.decodeFromString(DistributionManifest.serializer(), text)
}
