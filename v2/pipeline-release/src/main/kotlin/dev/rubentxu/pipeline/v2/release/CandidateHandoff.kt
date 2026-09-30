package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * P0.4 — the candidate handoff descriptor, emitted by `pipeline-kotlin` for
 * the external `pipelinek-release-harness`.
 *
 * Authority: `docs/pipelinek-release-evolution/shared/03-handoff-schemas.md` §1
 * and `shared/01-cross-repo-contract.md` §4.
 *
 * The single most important property is that [candidateId] is the SHA-256 of
 * the distribution ZIP and nothing else. Per contract §4 it is NOT a branch, an
 * isolated Git SHA, a mutable tag, `latest`, or a human RC name. Those remain
 * as mandatory *metadata* ([sourceCommit], [releaseTrain], [candidateSequence])
 * but they never substitute for the digest. This is the type-level expression
 * of "BUILD ONCE, CERTIFY EXACT BYTES, PUBLISH SAME BYTES": if the digest
 * changes, it is a different candidate, no matter what it is called.
 *
 * Candidate state lives entirely in this document. The product identity lives
 * in the bytes ([ProductVersion]) and in the distribution manifest. Keeping
 * them in separate documents is what makes promotion-rewriting structurally
 * impossible rather than merely discouraged.
 */
@Serializable
data class CandidateHandoff(
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** Canonical candidate identity: `sha256:<64 hex>`. Immutable. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The target ProductVersion this candidate materialises. */
    @SerialName("release_train") val releaseTrain: ProductVersion,
    /** Monotonic sequence within the release train. Not part of SemVer. */
    @SerialName("candidate_sequence") val candidateSequence: Int,
    @SerialName("product_version") val productVersion: ProductVersion,
    @SerialName("source_commit") val sourceCommit: String,
    val artifact: CandidateArtifact,
    @SerialName("distribution_manifest") val distributionManifest: CandidateFileRef,
    val sbom: CandidateFileRef?,
) {
    init {
        require(candidateSequence > 0) {
            "candidate_sequence is 1-based within a release train, got $candidateSequence"
        }
        require(productVersion == releaseTrain) {
            "product_version ($productVersion) must equal release_train ($releaseTrain): a " +
                "candidate materialises exactly one target version, and the train IS that " +
                "version (cross-repo contract v2 §5, §11)."
        }
    }

    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-candidate-2"
    }
}

/**
 * A candidate's material identity: the digest of the distribution ZIP.
 *
 * Wrapped in a value class with a private constructor so a `CandidateId` can
 * only be built from a real 64-hex digest. This makes "the candidate id is
 * whatever the tag said" unrepresentable rather than merely discouraged.
 */
@Serializable(with = CandidateIdSerializer::class)
@JvmInline
value class CandidateId private constructor(val digest: String) {

    /** The canonical wire form, `sha256:<hex>`. */
    val canonical: String get() = "sha256:$digest"

    override fun toString(): String = canonical

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun fromDigest(digest: String): CandidateId {
            val trimmed = digest.trim().removePrefix("sha256:")
            require(HEX64.matches(trimmed)) {
                "candidate_id must be a lowercase SHA-256 hex digest (64 chars), got '$digest'. " +
                    "A branch, tag, Git SHA or RC name is not a candidate identity " +
                    "(cross-repo contract v2 §4)."
            }
            return CandidateId(trimmed)
        }
    }
}

/**
 * Serialises [CandidateId] in the canonical `sha256:<hex>` wire form required
 * by `shared/03-handoff-schemas.md` §1, and validates on the way back in.
 *
 * Validating in [deserialize] matters: a descriptor authored by hand (or
 * rewritten downstream) must not be able to smuggle a name in as a candidate
 * id. The rule is enforced at the boundary, not only at construction.
 */
object CandidateIdSerializer : KSerializer<CandidateId> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.rubentxu.pipeline.v2.release.CandidateId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CandidateId) {
        encoder.encodeString(value.canonical)
    }

    override fun deserialize(decoder: Decoder): CandidateId {
        val raw = decoder.decodeString()
        return try {
            CandidateId.fromDigest(raw)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message, e)
        }
    }
}

@Serializable
data class CandidateArtifact(
    val name: String,
    val sha256: String,
    val size: Long,
    @SerialName("archive_root") val archiveRoot: String,
    /**
     * `Implementation-Version` observed inside the archive. Carried here so
     * the harness can reject a mismatched candidate before installing it.
     */
    @SerialName("implementation_version") val implementationVersion: String?,
)

@Serializable
data class CandidateFileRef(
    val name: String,
    val sha256: String,
    /** ISO-8601 instant the file was produced. */
    @SerialName("built_at") val builtAt: String,
)
