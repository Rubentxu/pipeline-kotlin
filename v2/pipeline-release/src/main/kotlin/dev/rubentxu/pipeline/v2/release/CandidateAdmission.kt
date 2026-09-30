package dev.rubentxu.pipeline.v2.release

import java.nio.file.Files
import java.nio.file.Path

/**
 * P0.3 build wiring — the interpreter at the candidate boundary.
 *
 * The pure deciders ([evaluateDistributionIdentity], [evaluateCandidateHandoff])
 * are the authority on whether a candidate is admissible. This class is the
 * effectful interpreter that turns their verdict into a build outcome: it
 * materializes the candidate, prints the operator diagnostic, and returns a
 * value the Gradle task can fail on.
 *
 * Keeping the pass/fail decision out of here is deliberate. A build script
 * that re-derived the rules could disagree with the model, and the model is
 * what the tests pin. This only reports and refuses.
 */
object CandidateAdmission {

    /**
     * Run the cheap identity admission for [zip] and emit the candidate
     * material next to it. Returns the outcome; the caller decides whether to
     * fail the build.
     */
    fun admit(
        zip: Path,
        productVersion: String,
        gitCommit: String,
        candidateRef: String?,
        toolchain: String,
        sbom: Path?,
        sha256sums: Path?,
        candidateSequence: Int,
        outDir: Path,
    ): AdmissionOutcome {
        // A candidate-suffixed build cannot produce candidate material under
        // protocol v2. This is reported as a refusal rather than an
        // exception so the diagnostic is the build's output, not a stack trace.
        val version = ProductVersion.parseOrNull(productVersion)
            ?: return AdmissionOutcome.Refused(
                "declared product version '$productVersion' is not a final MAJOR.MINOR.PATCH. " +
                    "Under the release-evolution protocol the binary identity must be the " +
                    "TARGET version (e.g. 0.44.0); candidate state lives in the candidate " +
                    "descriptor, not in the product version " +
                    "(cross-repo contract v2 §4, §11).",
            )

        val result = CandidateMaterializer.materialize(
            zip = zip,
            productVersion = version,
            gitCommit = gitCommit,
            candidateRef = candidateRef,
            toolchain = toolchain,
            sbom = sbom,
            sha256sums = sha256sums,
            candidateSequence = candidateSequence,
            outDir = outDir,
        )

        return when (result) {
            is MaterializationResult.Refused -> AdmissionOutcome.Refused(result.reason)
            is MaterializationResult.Materialized -> AdmissionOutcome.Admitted(
                candidateId = "sha256:${result.manifest.asset.sha256}",
                manifestPath = result.manifestPath,
                handoffPath = result.handoffPath,
                identityVerdict = result.identity.render(),
            )
        }
    }

    /** Locate the SBOM the CycloneDX task produced, if any. */
    fun locateSbom(distributionsDir: Path, version: String): Path? {
        val candidates = listOf(
            distributionsDir.resolve("pipelinek-$version.sbom.json"),
            distributionsDir.resolve("bom.json"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }
}

/** Result of the cheap identity admission at the build boundary. */
sealed interface AdmissionOutcome {

    /**
     * The candidate is admissible. [identityVerdict] is the identity gate's
     * own diagnostic, carried through verbatim so the build log shows what the
     * gate actually concluded rather than a flattened "OK".
     */
    data class Admitted(
        val candidateId: String,
        val manifestPath: Path,
        val handoffPath: Path,
        val identityVerdict: String,
    ) : AdmissionOutcome

    /** The candidate is refused. The build MUST fail. */
    data class Refused(val reason: String) : AdmissionOutcome
}
