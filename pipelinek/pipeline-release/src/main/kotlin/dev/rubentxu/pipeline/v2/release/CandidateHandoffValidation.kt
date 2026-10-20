package dev.rubentxu.pipeline.v2.release

/**
 * A candidate handoff must be internally consistent with
 * the distribution manifest it names, and its `candidate_id` must be the real
 * digest of the artifact.
 * This is the last producer-side gate before the harness sees the bytes. The
 * v0.43.0 incident is the reason it is a *closed* result type rather than a
 * list of warnings: any inconsistency is a rejection, and the harness receives
 * a candidate or it receives nothing.
 * Pure: it takes values, returns a decision, and touches no filesystem. The
 * caller measures the ZIP and supplies the digest; this decides whether the
 * documents agree.
*/
sealed interface HandoffVerdict {

    /** Every mandatory field is present and the documents agree. */
    data class Admissible(val handoff: CandidateHandoff) : HandoffVerdict

    /**
     * The handoff is internally inconsistent and MUST NOT be emitted.
     * [violations] names every problem, not just the first, so one build
     * reveals all of them.
*/
    data class Rejected(val violations: List<HandoffViolation>) : HandoffVerdict
}

/** One specific reason a handoff was rejected. */
data class HandoffViolation(val code: HandoffViolationCode, val detail: String)

enum class HandoffViolationCode {
    /** `candidate_id` is not the SHA-256 of the artifact it names. */
    CANDIDATE_ID_NOT_ARTIFACT_DIGEST,

    /**
     * `artifact.sha256` is not the measured digest of the ZIP.
     * Separate from [CANDIDATE_ID_NOT_ARTIFACT_DIGEST] because the two name
     * DIFFERENT fields and have different fixes: a wrong candidate_id is an
     * identity defect, a wrong artifact.sha256 is a bytes defect. Folding them
     * into one code made a build report the same code twice for two root
     * causes, and a consumer that groups violations by code could not tell
     * them apart — it only saw the detail string, which is prose, not a key.
*/
    ARTIFACT_DIGEST_NOT_MEASURED_ZIP,

    /** The handoff's product version differs from the manifest's. */
    PRODUCT_VERSION_DISAGREES_WITH_MANIFEST,

    /** The manifest's asset digest differs from the handoff artifact digest. */
    ASSET_DIGEST_DISAGREES_WITH_MANIFEST,

    /** The embedded JAR identity is absent where it is required. */
    IMPLEMENTATION_VERSION_MISSING,

    /** The embedded JAR identity disagrees with the product version. */
    IMPLEMENTATION_VERSION_DISAGREES,

    /** The archive root does not match the product version. */
    ARCHIVE_ROOT_DISAGREES,

    /** The asset file name does not match the product version. */
    ASSET_NAME_DISAGREES,
}

/**
 * Decide whether [handoff] may be handed to the harness, given the
 * [manifest] that describes the same bytes and the [measuredZipSha256] digest
 * of the ZIP as it exists on disk.
 * [measuredZipSha256] is supplied by the caller rather than read here so this
 * function stays pure and testable without a filesystem.
 * **The parameter only bites when the caller received these documents from
 * somewhere else.** Half of the checks below compare two values a producer
 * building the candidate from one measurement already made equal, so on that
 * path they compare `x` to `x` and cannot fire: CANDIDATE_ID,
 * ARTIFACT_DIGEST_NOT_MEASURED_ZIP and ASSET_DIGEST_DISAGREES_WITH_MANIFEST
 * all trace back to one `sha256Of(zip)`, and PRODUCT_VERSION_DISAGREES_WITH_
 * MANIFEST traces back to one `productVersion`.
 * [CandidateMaterializer] is exactly that path: it measures once, feeds the
 * result into both documents, and passes the same value back here. That is
 * construction, not verification, and the previous version of this paragraph
 * claimed the opposite — that passing the declared digest would make the check
 * vacuous "so the distinction is part of the signature" — while the only
 * production call site did precisely that.
 * The other four are live from that same call site, because they observe the
 * archive rather than compare two documents: ASSET_NAME and ARCHIVE_ROOT read
 * the ZIP's own name and layout, and the two IMPLEMENTATION_VERSION codes read
 * the version embedded in the archive's JAR. The embedded-version check is the
 * one that catches the v0.43.0 laundering shape, and it fires from
 * [CandidateMaterializer] today.
 * Independent re-measurement belongs to whoever RECEIVES the bytes: the
 * certifier recomputes the digest of the published artifact. A producer-side
 * gate that tried to re-read its own output would only re-derive the same
 * measurement and call the result a check.
*/
fun evaluateCandidateHandoff(
    handoff: CandidateHandoff,
    manifest: DistributionManifest,
    measuredZipSha256: String,
): HandoffVerdict {
    val violations = mutableListOf<HandoffViolation>()

    // The candidate id must be the digest of the bytes, not a name.
    if (handoff.candidateId.digest != measuredZipSha256) {
        violations += HandoffViolation(
            HandoffViolationCode.CANDIDATE_ID_NOT_ARTIFACT_DIGEST,
            "candidate_id ${handoff.candidateId.canonical} is not the SHA-256 of the artifact " +
                "(${measuredZipSha256}). Candidate identity is material, never a name " +
                "(the cross-repo contract).",
        )
    }

    if (handoff.artifact.sha256 != measuredZipSha256) {
        violations += HandoffViolation(
            HandoffViolationCode.ARTIFACT_DIGEST_NOT_MEASURED_ZIP,
            "handoff artifact.sha256 ${handoff.artifact.sha256} is not the measured ZIP digest " +
                "($measuredZipSha256).",
        )
    }

    // Cross-document agreement: the manifest describes the same bytes.
    if (manifest.version != handoff.productVersion) {
        violations += HandoffViolation(
            HandoffViolationCode.PRODUCT_VERSION_DISAGREES_WITH_MANIFEST,
            "handoff product_version ${handoff.productVersion} but distribution manifest " +
                "declares ${manifest.version}. The manifest is immutable and authoritative.",
        )
    }

    if (manifest.asset.sha256 != handoff.artifact.sha256) {
        violations += HandoffViolation(
            HandoffViolationCode.ASSET_DIGEST_DISAGREES_WITH_MANIFEST,
            "handoff artifact.sha256 ${handoff.artifact.sha256} but manifest asset.sha256 " +
                "${manifest.asset.sha256}.",
        )
    }

    // Identity shape inside the artifact itself.
    val version = handoff.productVersion.value
    if (handoff.artifact.archiveRoot != "pipelinek-$version") {
        violations += HandoffViolation(
            HandoffViolationCode.ARCHIVE_ROOT_DISAGREES,
            "archive root '${handoff.artifact.archiveRoot}' but product version is $version " +
                "(expected 'pipelinek-$version').",
        )
    }

    if (handoff.artifact.name != "pipelinek-$version.zip") {
        violations += HandoffViolation(
            HandoffViolationCode.ASSET_NAME_DISAGREES,
            "asset name '${handoff.artifact.name}' but product version is $version " +
                "(expected 'pipelinek-$version.zip').",
        )
    }

    val embedded = handoff.artifact.implementationVersion
    if (embedded == null) {
        violations += HandoffViolation(
            HandoffViolationCode.IMPLEMENTATION_VERSION_MISSING,
            "no Implementation-Version was observed inside the archive. An unobservable identity " +
                "is a rejection, not a pass: a missing manifest must never read as correct.",
        )
    } else if (embedded != version) {
        violations += HandoffViolation(
            HandoffViolationCode.IMPLEMENTATION_VERSION_DISAGREES,
            "embedded Implementation-Version '$embedded' but product version is $version. " +
                "This is exactly the v0.43.0 laundering shape.",
        )
    }

    return if (violations.isEmpty()) {
        HandoffVerdict.Admissible(handoff)
    } else {
        HandoffVerdict.Rejected(violations)
    }
}

/** Operator-facing diagnostic for a rejected handoff. */
fun HandoffVerdict.render(): String = when (this) {
    is HandoffVerdict.Admissible ->
        "candidate admissible: ${handoff.candidateId.canonical} (train ${handoff.releaseTrain}, " +
            "sequence ${handoff.candidateSequence})"

    is HandoffVerdict.Rejected -> buildString {
        appendLine("candidate REJECTED: ${violations.size} violation(s)")
        violations.forEach { appendLine("  - ${it.code}: ${it.detail}") }
        append(
            "A rejected candidate MUST NOT be handed to the harness. Fix the build; do not " +
                "edit these documents by hand.",
        )
    }
}
