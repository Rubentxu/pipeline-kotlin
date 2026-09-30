package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * P0.2 — the immutable distribution manifest, as a typed value.
 *
 * Authority: `docs/pipelinek-release-evolution/pipeline-kotlin/01-responsibilities-and-spec.md`
 * §5 (must be generated from the build and describe the real bytes) and
 * `shared/01-cross-repo-contract.md` §10 (downstream MUST NOT rewrite it).
 *
 * The manifest is a *description of bytes that already exist*. Every field is
 * either derived from the artifact itself or from the build that produced it.
 * There is deliberately no "promotion" field and no way to declare a version
 * that differs from the artifact: the version is [ProductVersion], which is the
 * identity the build compiled into the bytes.
 *
 * Immutability is structural, not conventional. This type has no mutator, and
 * the writer ([DistributionManifestCodec]) serialises to a deterministic
 * key order so the same bytes always produce the same file. Promotion metadata
 * lives in the candidate handoff descriptor, which is a *different* document
 * (P0.4) — so a promoter cannot "update" this file without changing
 * `candidate_id`, which is the ZIP digest it is supposed to describe.
 */
@Serializable
data class DistributionManifest(
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The product identity compiled into the artifact. */
    val version: ProductVersion,
    /** Asset file name, e.g. `pipelinek-0.44.0.zip`. */
    val asset: DistributionAsset,
    /** Provenance of the bytes. Never rewritten downstream. */
    val source: DistributionSource,
    /** The SBOM describing this artifact, with its own digest. */
    val sbom: DistributionArtifactRef?,
    /** Checksum authority file covering the asset. */
    @SerialName("sha256sums") val sha256sums: DistributionArtifactRef?,
) {
    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-distribution-manifest-2"
    }
}

@Serializable
data class DistributionAsset(
    val name: String,
    @SerialName("archive_root") val archiveRoot: String,
    val size: Long,
    val sha256: String,
    /**
     * `Implementation-Version` read from the application JAR inside the
     * archive. Present so the manifest describes the embedded identity, not
     * only the file name.
     */
    @SerialName("implementation_version") val implementationVersion: String?,
)

@Serializable
data class DistributionSource(
    @SerialName("git_commit") val gitCommit: String,
    /** Git tag at build time, if the revision was tagged. Null otherwise. */
    @SerialName("git_tag") val gitTag: String?,
    /** Build tool and version that produced the bytes. */
    val toolchain: String,
)

@Serializable
data class DistributionArtifactRef(
    val name: String,
    val sha256: String,
)
