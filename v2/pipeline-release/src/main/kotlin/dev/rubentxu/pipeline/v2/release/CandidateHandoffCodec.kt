package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.json.Json

/**
 * P0.4 — deterministic serialisation of the candidate handoff descriptor.
 *
 * Mirrors [DistributionManifestCodec]: fixed key order, defaults encoded, and
 * unknown fields refused. The descriptor is a handoff artifact the harness
 * ingests, so a field it does not understand must be a loud failure rather
 * than a silently dropped instruction.
 */
object CandidateHandoffCodec {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(handoff: CandidateHandoff): String =
        json.encodeToString(CandidateHandoff.serializer(), handoff) + "\n"

    fun decode(text: String): CandidateHandoff =
        json.decodeFromString(CandidateHandoff.serializer(), text)
}
