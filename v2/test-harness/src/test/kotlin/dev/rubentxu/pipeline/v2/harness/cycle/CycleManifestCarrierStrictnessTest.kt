package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlinx.serialization.json.Json

/**
 * BQ4 v7 (cycle carrier strict-validate, 2026-10-17):
 * the previous shape of [CycleManifest.sourceSha] validated
 * length only, not hex characters. An uppercase or non-hex
 * string of length 40 was accepted as a "SHA-1" and compared
 * by string equality against the runner's lowercase sourceSha,
 * silently never matching. The init now strict-validates
 * length + lowercase hex, mirroring the rest of the cycle
 * package (PromotionEvidence.boundCommit, ProbeEntry.contentSha256).
 *
 * The two pinned claims:
 *  1. A [CycleManifest] with a non-lowercase-hex sourceSha is
 *     refused at JSON load.
 *  2. A [CycleManifest] with a 40-char non-hex string is
 *     refused at JSON load.
 *
 * The first claim also pins that the wire-format JSON loader
 * surfaces the init's [IllegalArgumentException] (not a
 * [SerializationException]); the init is the format authority
 * and the loader is a thin pass-through.
 */
class CycleManifestCarrierStrictnessTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    @Test
    fun `a CycleManifest with an uppercase sourceSha is refused at load`() {
        val text = """
            {
              "cycle_id": "cyc-1",
              "source_sha": "0123456789ABCDEF0123456789ABCDEF01234567",
              "expected_hf": "HF0",
              "isolation_policy": "cycleDirOnly",
              "replacement": {
                "evidence": {
                  "type": "permanent_contract",
                  "contract_id": "x",
                  "bound_commit": "0123456789abcdef0123456789abcdef01234567",
                  "source_path": "Foo.kt",
                  "content_digest": "${"0".repeat(64)}",
                  "fidelity": "HF0",
                  "oracle": {"type":"fitness","name":"Foo","module":":test-harness"}
                }
              },
              "contracts": [],
              "probes": []
            }
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> {
            json.decodeFromString(CycleManifest.serializer(), text)
        }
        assertTrue(
            (ex.message ?: "").contains("lowercase hex"),
            "refusal must name the lowercase-hex requirement; got ${ex.message}",
        )
    }

    @Test
    fun `a CycleManifest with a 40-char non-hex sourceSha is refused at load`() {
        // 40 chars of 'Z' is the right LENGTH but the wrong
        // CHARSET. The previous length-only shape accepted
        // it; the new shape refuses it.
        val text = """
            {
              "cycle_id": "cyc-1",
              "source_sha": "${"Z".repeat(40)}",
              "expected_hf": "HF0",
              "isolation_policy": "cycleDirOnly",
              "replacement": {
                "evidence": {
                  "type": "permanent_contract",
                  "contract_id": "x",
                  "bound_commit": "0123456789abcdef0123456789abcdef01234567",
                  "source_path": "Foo.kt",
                  "content_digest": "${"0".repeat(64)}",
                  "fidelity": "HF0",
                  "oracle": {"type":"fitness","name":"Foo","module":":test-harness"}
                }
              },
              "contracts": [],
              "probes": []
            }
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> {
            json.decodeFromString(CycleManifest.serializer(), text)
        }
        assertTrue(
            (ex.message ?: "").contains("lowercase hex"),
            "refusal must name the lowercase-hex requirement; got ${ex.message}",
        )
    }
}
