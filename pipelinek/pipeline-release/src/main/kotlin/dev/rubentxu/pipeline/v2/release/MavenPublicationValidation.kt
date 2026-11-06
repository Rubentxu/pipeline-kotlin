package dev.rubentxu.pipeline.v2.release

/**
 * Whether a request publishes a Maven bundle, or why it does not.
 *
 * The verdict is a closed result rather than a boolean plus a message: a
 * publisher either has a request that may be published, or it has a list of
 * named violations, and there is no third state in which it "probably" may.
 *
 * The rules this decides are the ones the rest of the cycle already holds
 * and this channel had no word for. A published stable tag is never moved and
 * never replaced; a stable release is promoted only against the complete
 * certification of the candidate it carries; and the bytes that leave are
 * the bytes that were certified. This file says the same three things about
 * the Maven channel, and it says them about the BYTES: a bundle is a
 * repository of files with digests, and every rule below is a comparison
 * between a digest somebody claims and a digest somebody measured.
 *
 * The certification policy is NOT restated here. It is judged with
 * [evaluatePromotionCertification], the decider that the promotion path
 * already uses, so the two paths cannot disagree about what a certification
 * admits.
 */
sealed interface MavenPublicationVerdict {

    /** The request may be published. */
    data class Admitted(
        val request: MavenPublicationRequest,
        val manifest: MavenPublicationManifest,
    ) : MavenPublicationVerdict

    /**
     * The request may NOT be published.
     *
     * [violations] names every problem, not just the first, so one reading
     * reveals all of them: a publisher that fixes one problem and reads
     * again only to find the next one has been led by a request that told it
     * half the truth.
     */
    data class Rejected(
        val request: MavenPublicationRequest,
        val violations: List<MavenPublicationViolation>,
    ) : MavenPublicationVerdict
}

/**
 * One specific reason a request was refused.
 */
data class MavenPublicationViolation(
    val code: MavenPublicationViolationCode,
    val detail: String,
)

/**
 * The reasons a request can be refused, one per fact.
 *
 * Each code names a DIFFERENT fact about the request, and each one has a
 * different fix. A request about another candidate and a request about
 * another version are both "not this one", but only one of them is about
 * other bytes, and a publisher that reads the wrong one goes looking in the
 * wrong place.
 */
enum class MavenPublicationViolationCode {

    /** No certification was presented at all. */
    CERTIFICATION_ABSENT,

    /** The certification names a candidate other than the one being published. */
    CERTIFICATION_IS_ANOTHER,

    /** The certification does not admit the candidate. */
    CERTIFICATION_DOES_NOT_ADMIT,

    /** The manifest names a candidate other than the one being published. */
    CANDIDATE_IS_ANOTHER,

    /** The request publishes a version other than the one the manifest describes. */
    VERSION_IS_ANOTHER,

    /** The manifest on disk is not the manifest the request names. */
    MANIFEST_DIGEST_DISAGREES,

    /** The contracts published are not the contracts the candidate was admitted with. */
    CONTRACT_DISAGREES,

    /** The bytes to publish are not the bytes the manifest describes. */
    ARTIFACT_DISAGREES,

    /** The bundle to publish is not the bundle the manifest describes. */
    BUNDLE_DISAGREES,

    /** The version is already published with these bytes: a retry has nothing to publish. */
    VERSION_ALREADY_PUBLISHED,

    /** The version is already published with DIFFERENT bytes: publishing would replace it. */
    VERSION_PUBLISHED_WITH_OTHER_BYTES,
}

/**
 * Decide whether [request] may publish the bundle described by [manifest].
 *
 * Pure: it takes the documents and the measurements as values and returns a
 * decision. It reads no file, asks no clock and starts no process, so every
 * rule below is pinned by a case that runs in the unit lane.
 *
 * The rules, and what each one refuses:
 *
 * 1. The request must be presented WITH the complete certification of its
 *    candidate. No certification is not a certification that admits nothing:
 *    it is a certification nobody can account for, and publishing on the
 *    strength of it is the mistake the boundary exists to prevent.
 * 2. The certification must be about the candidate being published, and it
 *    must cover every part the promotion demands. The rule is the one the
 *    promotion already applies ([evaluatePromotionCertification]), and the
 *    detail it produces names the part that is missing.
 * 3. The manifest must be about the same candidate. A manifest of other
 *    bytes says nothing about these bytes.
 * 4. The version published must be the version the manifest describes. A
 *    request for a version that is not this candidate's is a request to
 *    publish a stable version that was published before, under a candidate
 *    that is not this one.
 * 5. The manifest on disk must be the manifest the request names, by digest.
 *    A manifest that was swapped after the request was written is a request
 *    about something else.
 * 6. The contracts published must be the contracts the candidate was admitted
 *    with. The candidate's SDK ZIP carries the POMs of those contracts, so
 *    the comparison is between the manifest's POMs and the ZIP's.
 * 7. The bytes to publish must be the bytes the manifest describes, artifact
 *    by artifact and bundle by bundle. A path that rebuilds an artifact
 *    after the certification is a path that publishes something nobody
 *    certified.
 * 8. The version must not already be published with DIFFERENT bytes. A
 *    published version is never replaced: the correct action is a new
 *    version, not an overwritten one.
 * 9. A version already published with THESE bytes is not published again. The
 *    retry has nothing to do, and a retry that overwrites a published
 *    version is a retry that can change bytes nobody re-certified.
 */
fun evaluateMavenPublication(
    request: MavenPublicationRequest,
    manifest: MavenPublicationManifest,
    certification: PromotionCertification?,
    observed: MavenObservedPublication,
): MavenPublicationVerdict {
    val violations = mutableListOf<MavenPublicationViolation>()

    violations += certificationViolations(certification, request.candidateId)

    if (manifest.candidateId != request.candidateId) {
        violations += MavenPublicationViolation(
            MavenPublicationViolationCode.CANDIDATE_IS_ANOTHER,
            "the manifest names candidate ${manifest.candidateId.canonical} and the request is " +
                "about ${request.candidateId.canonical}. A manifest of other bytes says nothing " +
                "about these bytes.",
        )
    }

    if (manifest.productVersion != request.productVersion) {
        violations += MavenPublicationViolation(
            MavenPublicationViolationCode.VERSION_IS_ANOTHER,
            "the request publishes version ${request.productVersion} and the manifest describes " +
                "version ${manifest.productVersion}. A request to publish a version that is not " +
                "this candidate's is a request to publish a version that was published before.",
        )
    }

    if (request.manifestSha256 != observed.manifestSha256) {
        violations += MavenPublicationViolation(
            MavenPublicationViolationCode.MANIFEST_DIGEST_DISAGREES,
            "the request names the manifest ${request.manifestName} with digest " +
                "${request.manifestSha256} and the manifest on disk has digest " +
                "${observed.manifestSha256}. The request is about a manifest that is not the one " +
                "on disk.",
        )
    }

    val contracts = manifest.artifacts.filter { it.kind == MavenArtifactKind.POM }
    val contractDifference = difference(contracts, observed.certifiedContracts)
    if (contractDifference != null) {
        violations += MavenPublicationViolation(
            MavenPublicationViolationCode.CONTRACT_DISAGREES,
            "the contracts of the bundle are not the contracts the candidate was admitted with: " +
                contractDifference,
        )
    }

    val artifactDifference = difference(manifest.artifacts, observed.bundleArtifacts)
    if (artifactDifference != null) {
        violations += MavenPublicationViolation(
            MavenPublicationViolationCode.ARTIFACT_DISAGREES,
            "the bytes to publish are not the bytes the manifest describes: $artifactDifference",
        )
    }

    if (manifest.bundleSha256 != observed.bundleSha256) {
        violations += MavenPublicationViolation(
            MavenPublicationViolationCode.BUNDLE_DISAGREES,
            "the bundle to publish has digest ${observed.bundleSha256} and the manifest names " +
                "${manifest.bundleSha256}. The bundle was rebuilt after the manifest was written.",
        )
    }

    violations += publishedViolations(manifest, observed.published)

    return if (violations.isEmpty()) {
        MavenPublicationVerdict.Admitted(request, manifest)
    } else {
        MavenPublicationVerdict.Rejected(request, violations)
    }
}

/**
 * The violations of the certification, judged by the decider the promotion
 * path already uses.
 *
 * The policy is not restated: a certification that does not admit a
 * candidate to a stable release does not admit it to a Maven repository
 * either, and the part it does not cover is named by the rule that already
 * knows how to name it.
 */
private fun certificationViolations(
    certification: PromotionCertification?,
    candidate: CandidateId,
): List<MavenPublicationViolation> {
    if (certification == null) {
        return listOf(
            MavenPublicationViolation(
                MavenPublicationViolationCode.CERTIFICATION_ABSENT,
                "no certification was presented. A published version is published only against " +
                    "the complete certification of the candidate it carries, and a request " +
                    "without one is a request nobody can account for.",
            ),
        )
    }

    val verdict = evaluatePromotionCertification(certification, candidate)
    if (verdict is PromotionCertificationVerdict.Admissible) return emptyList()

    val violations = verdict as PromotionCertificationVerdict.Rejected
    return violations.violations.map { violation ->
        val code = when (violation.code) {
            PromotionCertificationViolationCode.CANDIDATE_IS_ANOTHER ->
                MavenPublicationViolationCode.CERTIFICATION_IS_ANOTHER

            else -> MavenPublicationViolationCode.CERTIFICATION_DOES_NOT_ADMIT
        }
        MavenPublicationViolation(code, violation.detail)
    }
}

/**
 * The violations of the state of the target repository.
 *
 * The two cases are different facts and they are reported differently. A
 * version that holds these bytes was published already: the retry has
 * nothing to do, and publishing it again would replace a published version
 * with the same bytes, which is the shape of an overwrite even when the
 * bytes match. A version that holds OTHER bytes is a conflict: replacing it
 * would take away a version that consumers already resolve.
 */
private fun publishedViolations(
    manifest: MavenPublicationManifest,
    published: MavenPublishedVersion,
): List<MavenPublicationViolation> {
    if (published is MavenPublishedVersion.Absent) return emptyList()

    val held = published as MavenPublishedVersion.Present
    val difference = difference(manifest.artifacts, held.artifacts)
    if (difference == null) {
        return listOf(
            MavenPublicationViolation(
                MavenPublicationViolationCode.VERSION_ALREADY_PUBLISHED,
                "version ${manifest.productVersion} is already published with these bytes. The " +
                    "retry has nothing to publish, and a retry MUST NOT overwrite a published " +
                    "version: the correct action is to publish nothing.",
            ),
        )
    }
    return listOf(
        MavenPublicationViolation(
            MavenPublicationViolationCode.VERSION_PUBLISHED_WITH_OTHER_BYTES,
            "version ${manifest.productVersion} is already published with other bytes: " +
                "$difference. A published version is never replaced: the correct action is a new " +
                "version, not an overwritten one.",
        ),
    )
}

/**
 * How two sets of artifacts differ, or null when they do not.
 *
 * The comparison is by path and by digest, and it names what is missing, what
 * is extra and what changed: "the bytes differ" is not something a publisher
 * can act on.
 */
private fun difference(
    expected: List<MavenArtifactDigest>,
    actual: List<MavenArtifactDigest>,
): String? {
    val expectedByPath = expected.associate { it.path to it.sha256 }
    val actualByPath = actual.associate { it.path to it.sha256 }

    val missing = expectedByPath.keys - actualByPath.keys
    val extra = actualByPath.keys - expectedByPath.keys
    val changed = (expectedByPath.keys intersect actualByPath.keys)
        .filter { expectedByPath.getValue(it) != actualByPath.getValue(it) }

    if (missing.isEmpty() && extra.isEmpty() && changed.isEmpty()) return null

    return buildString {
        if (missing.isNotEmpty()) append("missing ${missing.sorted().joinToString(", ")}")
        if (extra.isNotEmpty()) {
            if (isNotEmpty()) append("; ")
            append("unexpected ${extra.sorted().joinToString(", ")}")
        }
        if (changed.isNotEmpty()) {
            if (isNotEmpty()) append("; ")
            append(
                "changed " + changed.sorted().joinToString(", ") { path ->
                    "${path} (${expectedByPath.getValue(path)} != ${actualByPath.getValue(path)})"
                },
            )
        }
    }
}

/**
 * Operator-facing diagnostic for a publication verdict.
 *
 * The text names every violation and says what to do with them, because the
 * reader of this message is deciding whether to publish and needs the reason
 * to be actionable, not merely present.
 */
fun MavenPublicationVerdict.render(): String = when (this) {
    is MavenPublicationVerdict.Admitted ->
        "maven publication ADMITTED: candidate ${request.candidateId.canonical}, version " +
            "${manifest.productVersion}, ${manifest.artifacts.size} artifact(s), bundle " +
            "${manifest.bundleSha256}"

    is MavenPublicationVerdict.Rejected -> buildString {
        appendLine("maven publication REJECTED: ${violations.size} violation(s)")
        violations.forEach { appendLine("  - ${it.code}: ${it.detail}") }
        append(
            "A rejected request MUST NOT publish the bundle. Publish nothing, and hand the " +
                "request back with the violations above.",
        )
    }
}
