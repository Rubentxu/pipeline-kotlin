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
 * TRAIN P3 — identity separation. The fields this document may carry are
 * exactly:
 *
 * ```text
 * ProductVersion   the version the product will report   [in artifact]
 * SourceCommit     the exact code that was built         [in artifact]
 * CandidateId      SHA-256 of the ZIP                    [in handoff]
 * CandidateRef     optional build provenance             [in artifact]
 * ```
 *
 * and never:
 *
 * ```text
 * StableTag             result of a FUTURE promotion
 * CertificationResultId result of an EXTERNAL verdict
 * PromotionId           the publication operation
 * ```
 *
 * The candidate's identity is `CandidateId = SHA256(ZIP)`, never a `-rcN`
 * suffix carried inside the product version. A candidate built at
 * ProductVersion `0.44.0` is already a FINAL-version candidate and does not
 * need — and must not be given — a stable `v0.44.0` tag to exist.
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
    /**
     * TRAIN P3 — provenance of the BUILD, never a promotion result.
     *
     * Semantics are fixed here because the v0.43.0 incident proved the
     * ambiguity is dangerous. This field means `buildRef` / `candidateRef`:
     * "what ref, if any, was checked out when these bytes were produced". It is
     * supplied by the `candidate.tag` Gradle property, which defaults to empty.
     *
     * It is NOT the stable tag, and it MUST NOT be read as one. `stableTag` is
     * the RESULT of a promotion that has not happened yet — a promotion cannot
     * retroactively describe the bytes that predate it. Encoding that future
     * value here would force exactly one of two bad outcomes on promotion:
     *
     * ```text
     * A. leave the manifest alone  -> it still names a pre-promotion ref
     * B. rewrite the manifest     -> the ZIP digest stops being the one certified
     * ```
     *
     * Both are the v0.43.0 family. `stableTag`, `publishedAt` and
     * `certificationResult` belong exclusively to `promotion-receipt.json`,
     * which is written after the external verdict and names the exact
     * `candidateId` it promotes.
     *
     * The wire name is kept as `git_tag` rather than renamed to
     * `candidate_ref`: the manifest is a published document and its key names
     * are already consumed by the harness. The SEMANTICS are what P3 fixed,
     * and they are now unambiguous. A future schema v3 may rename the key; it
     * MUST NOT be redefined in place.
     */
    @SerialName("git_tag") val candidateRef: String?,
    /** Build tool and version that produced the bytes. */
    val toolchain: String,
)

@Serializable
data class DistributionArtifactRef(
    val name: String,
    val sha256: String,
)
