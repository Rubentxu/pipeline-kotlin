package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The publication manifest of the MAVEN channel: what the bundle publishes,
 * with the digest of every byte, bound to the candidate it belongs to.
 *
 * It is a document of the MAVEN channel and not a second distribution
 * manifest. The distribution manifest describes the distribution ZIP and, by
 * construction, cannot carry the state of a publication ([DistributionManifest]
 * says so in its own KDoc); this one describes the artifacts a Maven
 * consumer resolves and binds them to a candidate, a version and an SDK ZIP.
 *
 * The bindings are the whole point of the document:
 *
 *  - [candidateId] says WHICH candidate these bytes belong to. It is the
 *    digest of the distribution ZIP, and it is the identity the
 *    certification is judged against.
 *  - [productVersion] says the EXACT version. A version is not a range and
 *    not a train: the artifacts of one version are the artifacts of that
 *    version, and a manifest that named a train would be a manifest about
 *    several sets of bytes at once.
 *  - [sdkZip] says the SDK ZIP of that candidate, by name and by digest.
 *    The ZIP carries the POMs of the same contracts, so the comparison
 *    between the two is what proves that the contracts this bundle
 *    publishes are the contracts the candidate was admitted with.
 *  - [artifacts] says WHAT is published and with which digest, one entry per
 *    POM, JAR, sources JAR and javadoc JAR.
 *  - [bundleSha256] says the digest of the WHOLE bundle, so a byte that is
 *    not an artifact — a signature, a checksum — is still pinned.
 *
 * The coordinates are NOT written here. They are the path of each artifact
 * inside the repository, and the repository layout is the one authority about
 * where a file lives; a coordinate written in a second field is a second
 * place where the same fact can be wrong.
 *
 * The constructor is internal on purpose: the invariants above are part of the
 * type, so a document that satisfies the grammar and breaks them is a
 * malformed document rather than a valid one. The producer and the door live
 * in this module; outside it, a document enters through the codec, and the
 * codec is where a document is held to its shape.
 */
@Serializable
data class MavenPublicationManifest internal constructor(
    /** The shape this document claims to follow. */
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The candidate these artifacts belong to: the digest of the candidate's bytes. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The exact version these artifacts carry. */
    @SerialName("product_version") val productVersion: ProductVersion,
    /** The SDK ZIP of the same candidate, by name and by digest. */
    @SerialName("sdk_zip") val sdkZip: MavenFileDigest,
    /** What is published: one entry per artifact, with the digest of its bytes. */
    val artifacts: List<MavenArtifactDigest>,
    /** The digest of the whole bundle, artifact or not. */
    @SerialName("bundle_sha256") val bundleSha256: String,
) {
    init {
        require(artifacts.isNotEmpty()) {
            "a manifest that publishes nothing is not a manifest: artifacts is empty."
        }
        require(artifacts.map { it.path }.distinct().size == artifacts.size) {
            "two artifacts would carry the same path, and a repository cannot hold two " +
                "files under one path."
        }
        require(Sha256.isSha256Hex(bundleSha256)) {
            "bundle_sha256 must be a lowercase SHA-256 hex digest (64 chars), got '$bundleSha256'."
        }
    }

    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-maven-publication-manifest-1"
    }
}

/**
 * The request to publish a Maven bundle: the ASK, as opposed to the
 * description of the bytes.
 *
 * The request names the manifest it publishes and the digest of that
 * manifest, and it names the candidate and the version it is about. It does
 * NOT carry the artifacts: the manifest does, and a request that carried its
 * own copy of the list would be a second authority about what is published.
 *
 * The request carries no field that names a transport. Where it travels is
 * the business of the reception contract, which declares the local inbox;
 * a document that names where it should be filed is a document that can
 * disagree with the contract about it.
 *
 * The constructor is internal on purpose, for the reason the manifest's is:
 * the invariants are part of the type.
 */
@Serializable
data class MavenPublicationRequest internal constructor(
    /** The shape this document claims to follow. */
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The candidate being published: the digest of the candidate's bytes. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The exact version being published. */
    @SerialName("product_version") val productVersion: ProductVersion,
    /** The name the manifest is filed under. */
    @SerialName("manifest_name") val manifestName: String,
    /** The digest of the manifest's bytes. */
    @SerialName("manifest_sha256") val manifestSha256: String,
) {
    init {
        require(manifestName.isNotBlank()) {
            "a request that names no manifest is not a request: manifest_name is blank."
        }
        require(Sha256.isSha256Hex(manifestSha256)) {
            "manifest_sha256 must be a lowercase SHA-256 hex digest (64 chars), got '$manifestSha256'."
        }
    }

    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-maven-publication-request-1"
    }
}

/**
 * One file of the bundle, with the digest of its bytes.
 *
 * The path is the one the file has inside the repository, and it carries the
 * coordinate: `group/artifact/version/file`. The coordinate is not written
 * again in another field, because a fact written twice is a fact that can
 * be true in one place and false in the other.
 *
 * The kind is what tells a consumer's resolver what this file is: the POM
 * that declares the contract, the JAR that carries the classes, and the two
 * companions a repository is expected to hold beside them.
 */
@Serializable
data class MavenArtifactDigest(
    /** The path of the file inside the repository. */
    val path: String,
    /** What the file is. */
    val kind: MavenArtifactKind,
    /** The digest of the bytes. */
    val sha256: String,
) {
    init {
        require(path.isNotBlank()) { "an artifact without a path is not an artifact: path is blank." }
        require(Sha256.isSha256Hex(sha256)) {
            "sha256 must be a lowercase SHA-256 hex digest (64 chars), got '$sha256'."
        }
    }
}

/**
 * A file named with the digest of its bytes.
 *
 * It is the shape of a reference to a file that is not part of the bundle:
 * the SDK ZIP of the candidate, which the bundle is published beside.
 */
@Serializable
data class MavenFileDigest(
    /** The name of the file. */
    val name: String,
    /** The digest of its bytes. */
    val sha256: String,
) {
    init {
        require(name.isNotBlank()) { "a file without a name is not a file: name is blank." }
        require(Sha256.isSha256Hex(sha256)) {
            "sha256 must be a lowercase SHA-256 hex digest (64 chars), got '$sha256'."
        }
    }
}

/**
 * What one file of the bundle IS.
 *
 * The vocabulary is closed because the kind is what the publisher and the
 * certification comparison act on: the POMs are the CONTRACTS and the JARs
 * are the code, and a file of no kind is a file that nothing has to agree
 * about. The signatures and the checksums are not kinds here — they are
 * pinned by the bundle's own digest, and giving them a kind would make them
 * look like artifacts a consumer resolves.
 */
enum class MavenArtifactKind {

    /** The POM: the document that declares the contract of one coordinate. */
    POM,

    /** The JAR: the classes of one coordinate. */
    JAR,

    /** The sources JAR: the code a consumer reads. */
    SOURCES,

    /** The javadoc JAR: the reference a consumer reads. */
    JAVADOC,
}

/**
 * What the target repository already holds at the version being published.
 *
 * The absence of the version and the presence of it are two different facts,
 * and they are two different cases here: a target that holds nothing is a
 * target waiting for the publication, and a target that holds the version is
 * a target whose version must not be overwritten.
 */
sealed interface MavenPublishedVersion {

    /** The target holds no such version: there is nothing to overwrite. */
    data object Absent : MavenPublishedVersion

    /**
     * The target holds the version, with these artifacts.
     *
     * The artifacts are the ones of the recognised kinds, so the comparison
     * with a manifest is a comparison of like with like.
     */
    data class Present(val artifacts: List<MavenArtifactDigest>) : MavenPublishedVersion
}

/**
 * What was MEASURED about the publication, as values.
 *
 * The measurements are separated from the documents because the decision
 * compares a claim with a fact: the manifest says what is published and the
 * observed says what is on disk, and a decision that compared a document
 * with itself would certify nothing.
 */
data class MavenObservedPublication(
    /** The artifacts measured in the bundle that is about to be published. */
    val bundleArtifacts: List<MavenArtifactDigest>,
    /** The digest measured over the whole bundle. */
    val bundleSha256: String,
    /** What the target repository already holds at the version. */
    val published: MavenPublishedVersion,
    /** The POMs carried by the candidate's SDK ZIP: the contracts it was admitted with. */
    val certifiedContracts: List<MavenArtifactDigest>,
    /** The digest of the manifest document as it was read. */
    val manifestSha256: String,
)
