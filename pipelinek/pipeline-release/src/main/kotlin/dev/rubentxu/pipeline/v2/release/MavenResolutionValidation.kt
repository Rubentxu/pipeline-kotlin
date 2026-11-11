package dev.rubentxu.pipeline.v2.release

/**
 * What a consumer RESOLVES from a repository: the version the publication
 * manifest declares, as the repository serves it.
 *
 * The two states are different facts, and the difference is the one a
 * publisher needs. A repository that serves nothing of the version is a
 * version a consumer cannot resolve, and a repository that serves PART of it
 * is a version that resolves only for the coordinates that happen to be
 * there. Neither is a repository without the version.
 */
sealed interface MavenResolvedVersion {

    /** The repository serves none of the artifacts of the version. */
    data object Absent : MavenResolvedVersion

    /**
     * The repository serves the version, with these artifacts.
     *
     * The artifacts are the ones of the recognised kinds, each with the digest
     * of the bytes the repository served. The digest is MEASURED from those
     * bytes by whoever read them; nothing here is a claim the repository made
     * about itself.
     */
    data class Resolved(val artifacts: List<MavenArtifactDigest>) : MavenResolvedVersion
}

/**
 * Whether a repository serves the bytes the publication manifest declares.
 *
 * The verdict is a closed result rather than a boolean plus a message: a
 * consumer either resolves the certified bytes, or it has a list of named
 * violations, and there is no third state in which it "probably" does.
 *
 * This is the OTHER side of the publication gate. That gate asks whether a
 * request may publish a bundle, and it refuses a repository that already
 * holds the version, because a published version is never replaced. This one
 * asks what the repository serves AFTER the publication, and its answer is
 * the only thing that tells a consumer whether the channel carries the
 * certified bytes.
 */
sealed interface MavenResolutionVerdict {

    /** The repository serves every artifact the manifest declares, with its digest. */
    data class Verified(val manifest: MavenPublicationManifest) : MavenResolutionVerdict

    /**
     * The repository does NOT serve what the manifest declares.
     *
     * [violations] names every problem, not just the first, so one reading
     * reveals all of them: a publisher that fixes one problem and reads again
     * only to find the next one has been led by a verdict that told it half
     * the truth.
     */
    data class Rejected(
        val manifest: MavenPublicationManifest,
        val violations: List<MavenResolutionViolation>,
    ) : MavenResolutionVerdict
}

/**
 * One specific reason a repository does not serve the declared bytes.
 */
data class MavenResolutionViolation(
    val code: MavenResolutionViolationCode,
    val detail: String,
)

/**
 * The reasons a repository can fail to serve the declared bytes, one per
 * fact.
 *
 * Each code names a DIFFERENT fact, and each one has a different fix. A
 * version that is not served at all and an artifact that is served with other
 * bytes are both "the consumer does not get what it expects", but only one of
 * them is about the bytes, and a reader that takes the wrong one goes looking
 * in the wrong place.
 */
enum class MavenResolutionViolationCode {

    /** The repository serves none of the artifacts of the version. */
    VERSION_ABSENT,

    /** The repository does not serve one of the artifacts the manifest declares. */
    ARTIFACT_ABSENT,

    /** The repository serves an artifact with bytes the manifest does not declare. */
    ARTIFACT_DISAGREES,
}

/**
 * Decide whether [resolved] is what [manifest] declares.
 *
 * Pure: it takes the manifest and the measurements as values and returns a
 * decision. It reads no file, asks no clock, starts no process and opens no
 * connection, so every rule below is pinned by a case that runs in the unit
 * lane.
 *
 * The rules, and what each one refuses:
 *
 * 1. A repository that serves none of the artifacts does not serve the
 *    version. A version that is not served is a version a consumer cannot
 *    resolve, and a repository in the middle of processing an upload is in
 *    exactly that state.
 * 2. Every artifact the manifest declares must be served. A coordinate that
 *    the repository does not hold is a coordinate that fails to resolve, and
 *    the failure happens in the consumer's build, far from the publication
 *    that caused it.
 * 3. Every artifact the manifest declares must be served with ITS digest. The
 *    bytes a consumer resolves are the bytes that were certified only when
 *    they are the same bytes.
 *
 * The comparison is ONE-DIRECTIONAL: it says what the repository must serve,
 * and it says nothing about the rest of what the repository holds. A registry
 * serves files of its own beside the artifacts (checksums and metadata), and
 * those files are the registry's business, not this decision's.
 */
fun evaluateMavenResolution(
    manifest: MavenPublicationManifest,
    resolved: MavenResolvedVersion,
): MavenResolutionVerdict {
    val violations = mutableListOf<MavenResolutionViolation>()

    val served = when (resolved) {
        is MavenResolvedVersion.Absent -> {
            violations += MavenResolutionViolation(
                MavenResolutionViolationCode.VERSION_ABSENT,
                "the repository serves none of the ${manifest.artifacts.size} artifact(s) " +
                    "the manifest declares for version ${manifest.productVersion}. A version " +
                    "that is not served is a version a consumer cannot resolve.",
            )
            null
        }

        is MavenResolvedVersion.Resolved -> resolved.artifacts.associateBy { it.path }
    }

    if (served != null) {
        for (artifact in manifest.artifacts) {
            val servedArtifact = served[artifact.path]
            if (servedArtifact == null) {
                violations += MavenResolutionViolation(
                    MavenResolutionViolationCode.ARTIFACT_ABSENT,
                    "the repository does not serve ${artifact.path}, which the manifest declares " +
                        "with digest ${artifact.sha256}. A consumer that resolves this " +
                        "coordinate gets nothing.",
                )
                continue
            }
            if (servedArtifact.sha256 != artifact.sha256) {
                violations += MavenResolutionViolation(
                    MavenResolutionViolationCode.ARTIFACT_DISAGREES,
                    "the repository serves ${artifact.path} with digest ${servedArtifact.sha256} " +
                        "and the manifest declares ${artifact.sha256}. The bytes a consumer " +
                        "resolves are not the bytes the publication certified.",
                )
            }
        }
    }

    return if (violations.isEmpty()) {
        MavenResolutionVerdict.Verified(manifest)
    } else {
        MavenResolutionVerdict.Rejected(manifest, violations)
    }
}

/**
 * Operator-facing diagnostic for a resolution verdict.
 *
 * The text names every violation and says what it means, because the reader of
 * this message is deciding whether a consumer will resolve the release, and
 * needs the reason to be actionable, not merely present.
 */
fun MavenResolutionVerdict.render(): String = when (this) {
    is MavenResolutionVerdict.Verified ->
        "maven resolution VERIFIED: version ${manifest.productVersion}, " +
            "${manifest.artifacts.size} artifact(s) served with the declared digests"

    is MavenResolutionVerdict.Rejected -> buildString {
        appendLine("maven resolution REJECTED: ${violations.size} violation(s)")
        violations.forEach { appendLine("  - ${it.code}: ${it.detail}") }
    }
}
