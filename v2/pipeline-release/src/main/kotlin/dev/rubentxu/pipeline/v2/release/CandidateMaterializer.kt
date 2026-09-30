package dev.rubentxu.pipeline.v2.release

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * P0.2 / P0.4 — the effectful half: measure real bytes and emit the candidate
 * material (distribution manifest + SHA256SUMS + handoff descriptor).
 *
 * Everything here does I/O; the decisions it feeds stay pure in
 * [evaluateDistributionIdentity] and [evaluateCandidateHandoff]. This is the
 * "decide, then interpret" split from AGENTS.md: this class gathers facts and
 * writes files, and it never decides whether identity is acceptable. It calls
 * the pure deciders and fails closed on their verdicts.
 *
 * The one thing it must never do is *invent* a value. Every digest comes from
 * reading the bytes; every version comes from the artifact or the build. A
 * surface that cannot be read is recorded as absent, never defaulted.
 */
object CandidateMaterializer {

    /** Digest of a file's bytes, lowercase hex. */
    fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream -> digestStream(stream, digest) }
        return digest.digest().toHexString()
    }

    /**
     * Write the candidate material next to the ZIP and validate the whole set
     * before returning. On any rejection nothing is written and the caller is
     * told why: a build either produces a complete, self-consistent candidate
     * or produces no candidate at all.
     *
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
        sha256sums: Path?,
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
            sha256sums = sha256sums?.let { DistributionArtifactRef(it.fileName.toString(), sha256Of(it)) },
        )

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
                sha256 = "",
                builtAt = candidateRef ?: gitCommit,
            ),
            sbom = manifest.sbom?.let { CandidateFileRef(it.name, it.sha256, candidateRef ?: gitCommit) },
        )

        val verdict = evaluateCandidateHandoff(handoff, manifest, manifest.asset.sha256)
        if (verdict is HandoffVerdict.Rejected) {
            return MaterializationResult.Refused(verdict.render())
        }

        Files.createDirectories(outDir)
        val manifestPath = outDir.resolve(MANIFEST_NAME)
        val manifestText = DistributionManifestCodec.encode(manifest)
        Files.writeString(manifestPath, manifestText)

        val handoffPath = outDir.resolve(HANDOFF_NAME)
        val handoffText = CandidateHandoffCodec.encode(
            handoff.copy(
                distributionManifest = handoff.distributionManifest.copy(
                    sha256 = sha256Of(manifestPath),
                ),
            ),
        )
        Files.writeString(handoffPath, handoffText)

        return MaterializationResult.Materialized(
            manifestPath = manifestPath,
            handoffPath = handoffPath,
            identity = identity,
            manifest = manifest,
        )
    }

    /**
     * Write a `SHA256SUMS` file covering [covered]. This is the checksum
     * authority the installer consumes (P1.1): one file, standard
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

    private fun digestStream(stream: InputStream, digest: MessageDigest) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { "%02x".format(it) }
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
