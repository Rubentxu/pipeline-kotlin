package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * SHA-256 fingerprint of a durable operation's input, used as a cache key.
 *
 * Represented as a 64-character lowercase hex string (256 bits).
 *
 * ## Canonical JSON encoding
 *
 * The payload is encoded with a plain `Json { encodeDefaults; explicitNulls }`,
 * and the ordering is imposed on the VALUE before encoding by recursing through
 * every map and sorting it by key. That ordering is not an optimisation here, it
 * is the property being relied upon: kotlinx.serialization writes a `Map` in
 * iteration order, so two equal `params` maps assembled in a different order
 * would otherwise hash to different digests and the same operation would acquire
 * two identities.
 *
 * There is no canonical-mode flag to lean on. `JsonBuilder` in
 * kotlinx.serialization-json 1.11.0 has no `canonical` property — an earlier
 * revision of this comment claimed otherwise, and the dangling
 * `[Json].[canonical][Json.DeepRecursive]` link beside it was the tell.
 *
 * ## Collision resistance
 *
 * SHA-256 over bounded input (≤ 64 KiB enforced by [OperationInput.validate]) is
 * cryptographically collision-resistant for this use case.
 *
 * Provenance note: this comment used to cite two design documents that are not
 * in the repository, so the citations were removed rather than left pointing at
 * nothing. Sibling citations of the same shape survive elsewhere in the Kotlin
 * sources and need the same decision.
 *
 * @param hex 64-character lowercase hex string.
 */
@JvmInline
value class Fingerprint(val hex: String) {
    init {
        require(hex.length == 64) { "Fingerprint hex must be exactly 64 characters, got ${hex.length}" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Fingerprint hex must contain only 0-9 and a-f, got: $hex" }
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * Sorts every map in the payload so encoding is order-independent.
         *
         * kotlinx.serialization-json exposes no canonical-mode flag: `JsonBuilder`
         * in 1.11.0 has no `canonical` property (measured against the jar, not
         * assumed), and it writes a `Map` in iteration order. So the ordering has
         * to be imposed here, BEFORE encoding, or two equal `params` maps built in
         * a different order would produce different digests.
         *
         * The sort is recursive on purpose. Sorting only the top level would fix
         * the common case and leave a nested `JsonObject` — a parameter whose
         * value is itself an object — order-dependent again, which is the same
         * defect one level down.
         */
        private fun JsonElement.withSortedKeys(): JsonElement = when (this) {
            is JsonObject -> JsonObject(
                entries.sortedBy { it.key }.associate { it.key to it.value.withSortedKeys() },
            )
            is JsonArray -> JsonArray(map { it.withSortedKeys() })
            else -> this
        }

        private fun Map<String, JsonElement>.withSortedKeys(): Map<String, JsonElement> =
            entries.sortedBy { it.key }.associate { it.key to it.value.withSortedKeys() }

        /**
         * Payload DTO for canonical fingerprint computation.
         *
         * The encoded field order is NOT the declaration order: the encoder runs
         * with `canonical = true`, which orders members by name. Declaration order
         * is therefore free to change for readability, while the SET of fields and
         * their names are not — adding or renaming one is a BREAKING CHANGE for the
         * fingerprint contract and changes every digest this class produces.
         */
        @Serializable
        private data class FingerprintPayload(
            val stepId: String,
            val params: Map<String, JsonElement>,
            val runId: String,
            val attempt: Int,
            val replayPolicy: ReplayPolicy,
        )

        /**
         * Computes the SHA-256 fingerprint of a durable operation.
         *
         * The canonical payload is constructed from [input], [stepId], [replayPolicy],
         * and [attempt], then encoded as canonical JSON and hashed with SHA-256.
         *
         * @param input        The durable operation's input.
         * @param stepId       Stable step identifier.
         * @param replayPolicy The replay policy at the time of computation.
         * @param attempt      Current attempt number (≥ 1).
         * @return A 64-char hex fingerprint.
         *
         * @throws IllegalArgumentException if [attempt] is < 1.
         */
        fun compute(
            input: OperationInput,
            stepId: String,
            replayPolicy: ReplayPolicy,
            attempt: Int,
        ): Fingerprint {
            require(attempt >= 1) { "attempt must be >= 1, got $attempt" }

            val payload = FingerprintPayload(
                stepId = stepId,
                params = input.params.withSortedKeys(),
                runId = input.runId,
                attempt = attempt,
                replayPolicy = replayPolicy,
            )

            val canonicalJson = JSON.encodeToString(FingerprintPayload.serializer(), payload)
            // Routed through [dev.rubentxu.pipeline.v2.domain.digest.Sha256] (B0). This is the
            // single most durable digest in the repository: it is the operation identity that
            // replay, divergence and resume are decided against, and every shipped candidate
            // has written rows keyed by the previous expression's exact value. The bytes hashed
            // are unchanged — the previous code also used UTF-8 explicitly — so no existing
            // fingerprint moves. What changes is that the hex form now comes from one place
            // instead of a third local re-spelling of "%02x".
            return Fingerprint(dev.rubentxu.pipeline.v2.domain.digest.Sha256.ofText(canonicalJson))
        }
    }
}
