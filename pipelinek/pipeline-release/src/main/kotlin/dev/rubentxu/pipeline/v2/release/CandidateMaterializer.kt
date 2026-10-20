package dev.rubentxu.pipeline.v2.release

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import dev.rubentxu.pipeline.v2.domain.digest.Sha256

/**
 * The effectful half: measure real bytes and emit the candidate
 * material (distribution manifest + SHA256SUMS + handoff descriptor).
 * Everything here does I/O; the decisions it feeds stay pure in
 * [evaluateDistributionIdentity] and [evaluateCandidateHandoff]. This is the
 * "decide, then interpret" split from AGENTS.md: this class gathers facts and
 * writes files, and it never decides whether identity is acceptable. It calls
 * the pure deciders and fails closed on their verdicts.
 * The one thing it must never do is *invent* a value. Every digest comes from
 * reading the bytes; every version comes from the artifact or the build. A
 * surface that cannot be read is recorded as absent, never defaulted.
*/
object CandidateMaterializer {

    /**
     * Digest of a file's bytes, lowercase hex.
     * This value becomes the candidate's identity, so the bytes hashed are unchanged: the
     * Shared utility streams with the same algorithm and produces the same lowercase hex
     * as the local 64 KiB loop this replaces. The local loop and its private hex helper are
     * deleted rather than left behind, because a candidate digest computable two ways is the
     * kind of ambiguity that lets a wrong ZIP pass admission.
*/
    fun sha256Of(path: Path): String = Sha256.ofFile(path)

    /**
     * Write the candidate material next to the ZIP and validate the whole set
     * before returning. On any rejection nothing is written and the caller is
     * told why: a build either produces a complete, self-consistent candidate
     * or produces no candidate at all.
     * [productVersion] is the build's declared identity. [gitCommit],
     * [candidateRef] and [toolchain] are build provenance, never promotion. [sbom] is optional because the
     * CycloneDX task may not have run; the manifest records its absence rather
     * than pretending it exists.
*/
    fun materialize(
        zip: Path,
        productVersion: ProductVersion,
        gitCommit: String,
        candidateRef: String?,
        toolchain: String,
        sbom: Path?,
        sdk: Path?,
        bom: Path?,
        sha256sums: Path?,
        covered: List<Path> = emptyList(),
        candidateSequence: Int,
        outDir: Path,
    ): MaterializationResult {
        val observations = buildList {
            add(IdentityObservation(IdentitySurface.PRODUCT_VERSION, productVersion.value))
            addAll(DistributionIdentityProbe.probeZip(zip))
            // The manifest is written from these very bytes, so its version is
            // the same observation, not an independent claim. RUNTIME_VERSION
            // is deliberately NOT synthesised here: it requires executing the
            // installed binary, which is the harness's job (heavy lane). Its
            // absence must stay visible as Incomplete, not be faked.
        }

        val identity = evaluateDistributionIdentity(DistributionIdentityFacts(observations))
        if (identity is DistributionIdentityVerdict.Divergent) {
            return MaterializationResult.Refused(identity.render())
        }

        // The manifest records the OBSERVED archive root, never an assumed one.
        // If the root cannot be read the candidate is refused: writing a
        // manifest that asserts a root nobody verified is precisely the kind
        // of unbacked claim this protocol bans.
        val archiveRoot = DistributionIdentityProbe.archiveRootName(zip)
            ?: return MaterializationResult.Refused(
                "archive root is not observable: the ZIP has zero or several top-level " +
                    "directories, so its layout is not the canonical distribution shape. " +
                    "Refusing to write a manifest that would assert an unverified root.",
            )

        // — SHA256SUMS is where the proof that `covered` was digested is kept,
        // so the two are decided together and BEFORE the manifest is built.
        // A non-empty `covered` is a claim that those assets MUST be digested.
        // With no `sha256sums` path there is nowhere to keep that proof, so the
        // candidate is refused: materializing it would write a manifest whose
        // `sha256sums` slot is null next to an asset list that nothing digested,
        // and the file it DID write earlier (the SHA256SUMS of a smaller set) would
        // still verify — the same silent absence as the `locateSbom` null, one
        // layer down.
        // An EMPTY `covered` is a genuinely different thing and is not refused:
        // no assets declared means nothing to digest, and a pre-existing sums
        // path is then a reference the manifest records without rewriting.
        // Previously this was the `else -> null` arm of a `when`, which covered
        // this case and returned null — while the comment above it claimed the
        // case was refused. The comment was true about the intent and false about
        // the code, and nothing measured which one shipped.
        val sumsRef: DistributionArtifactRef? = when {
            covered.isNotEmpty() -> {
                val target = sha256sums
                    ?: return MaterializationResult.Refused(
                        "covered declares ${covered.size} asset(s) to digest, which is the whole " +
                            "point of SHA256SUMS, but no path was given to write it. Refusing to " +
                            "materialize a candidate whose manifest could only record " +
                            "`sha256sums = null` next to assets it never digested; "the release policy " +
                            "DISTRIBUTION-FIRST RELEASE POLICY fixes the asset set exactly, and a " +
                            "digest that was never computed is a defect to fix, not a member to " +
                            "ship around. Either pass the path to write, or declare an empty " +
                            "`covered` if no assets are being certified.",
                    )
                Files.createDirectories(target.parent)
                writeSha256Sums(target, covered)
                DistributionArtifactRef(target.fileName.toString(), sha256Of(target))
            }
            else ->
                sha256sums?.let { DistributionArtifactRef(it.fileName.toString(), sha256Of(it)) }
        }

        val manifest = DistributionManifest(
            version = productVersion,
            asset = DistributionAsset(
                name = zip.fileName.toString(),
                archiveRoot = archiveRoot,
                size = Files.size(zip),
                sha256 = sha256Of(zip),
                implementationVersion = DistributionIdentityProbe.jarImplementationVersion(zip),
            ),
            source = DistributionSource(gitCommit, candidateRef, toolchain),
            sbom = sbom?.let { DistributionArtifactRef(it.fileName.toString(), sha256Of(it)) },
            sdk = sdk?.let { DistributionArtifactRef(it.fileName.toString(), sha256Of(it)) },
            bom = bom?.let { DistributionArtifactRef(it.fileName.toString(), sha256Of(it)) },
            sha256sums = sumsRef,
        )

        // The manifest's BYTES are decided before they reach disk. Encoding once
        // and digesting that text means the digest below is the digest of exactly
        // the bytes that get written, and the admission gate still runs BEFORE
        // anything is written -- writing first and measuring afterwards would
        // make this method leave a manifest behind for a candidate it refused,
        // contradicting the contract on this function.
        val manifestText = DistributionManifestCodec.encode(manifest)
        val manifestDigest = Sha256.ofText(manifestText)

        val handoff = CandidateHandoff(
            candidateId = CandidateId.fromDigest(manifest.asset.sha256),
            releaseTrain = productVersion,
            candidateSequence = candidateSequence,
            productVersion = productVersion,
            sourceCommit = gitCommit,
            artifact = CandidateArtifact(
                name = manifest.asset.name,
                sha256 = manifest.asset.sha256,
                size = manifest.asset.size,
                archiveRoot = manifest.asset.archiveRoot,
                implementationVersion = manifest.asset.implementationVersion,
            ),
            distributionManifest = CandidateFileRef(
                name = MANIFEST_NAME,
                sha256 = manifestDigest,
                builtAt = candidateRef ?: gitCommit,
            ),
            sbom = manifest.sbom?.let { CandidateFileRef(it.name, it.sha256, candidateRef ?: gitCommit) },
            sdk = manifest.sdk?.let { CandidateFileRef(it.name, it.sha256, candidateRef ?: gitCommit) },
            bom = manifest.bom?.let { CandidateFileRef(it.name, it.sha256, candidateRef ?: gitCommit) },
        )

        val verdict = evaluateCandidateHandoff(handoff, manifest, manifest.asset.sha256)
        if (verdict is HandoffVerdict.Rejected) {
            return MaterializationResult.Refused(verdict.render())
        }

        Files.createDirectories(outDir)
        val manifestPath = outDir.resolve(MANIFEST_NAME)
        Files.writeString(manifestPath, manifestText)

        // The SAME handoff that was admitted, not a copy patched afterwards. The
        // previous code emitted `handoff.copy(distributionManifest =...sha256 =
        // sha256Of(manifestPath))`, a second object that no gate ever saw: the
        // admission verdict described a document carrying an empty digest while
        // the published one carried a real one. The bytes are identical, so this
        // changes no output -- it removes the gap between what is checked and
        // what ships, which is what would bite the day a check on that field
        // was added and every build started rejecting its own placeholder.
        val handoffPath = outDir.resolve(HANDOFF_NAME)
        Files.writeString(handoffPath, CandidateHandoffCodec.encode(handoff))

        return MaterializationResult.Materialized(
            manifestPath = manifestPath,
            handoffPath = handoffPath,
            identity = identity,
            manifest = manifest,
        )
    }

    /**
     * Write a `SHA256SUMS` file covering [covered]. This is the checksum
     * One file, standard
     * `sha256sum` format, so `sha256sum -c SHA256SUMS` works unchanged.
*/
    fun writeSha256Sums(target: Path, covered: List<Path>) {
        val text = covered.joinToString(separator = "\n", postfix = "\n") { path ->
            "${sha256Of(path)}  ${path.fileName}"
        }
        Files.writeString(target, text)
    }

    private const val MANIFEST_NAME = "distribution-manifest.json"
    private const val HANDOFF_NAME = "candidate-handoff.json"
}

/** Outcome of materializing a candidate. */
sealed interface MaterializationResult {

    /** The candidate material was written and is internally consistent. */
    data class Materialized(
        val manifestPath: Path,
        val handoffPath: Path,
        val identity: DistributionIdentityVerdict,
        val manifest: DistributionManifest,
    ) : MaterializationResult

    /**
     * The candidate was refused and NOTHING was written. [reason] is the
     * operator diagnostic from the pure decider that rejected it.
*/
    data class Refused(val reason: String) : MaterializationResult
}
