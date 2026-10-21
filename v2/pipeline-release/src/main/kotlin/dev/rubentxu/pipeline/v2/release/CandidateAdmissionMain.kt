package dev.rubentxu.pipeline.v2.release

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * P0.3 build wiring — command-line entry point for the cheap identity gate.
 *
 * Gradle cannot call Kotlin project code directly, so the task runs this main
 * against the release module's runtime classpath. It is intentionally a thin
 * shell over [CandidateAdmission]: it parses arguments, delegates, prints the
 * diagnostic, and maps [AdmissionOutcome] to an exit code. It contains no
 * admission rules of its own, so the build and the tests cannot disagree.
 *
 * ```text
 * usage: candidate-admission <zip> <productVersion> <gitCommit> <outDir>
 *                      [<candidateRef>] [<candidateSequence>] [<sbomPath>] [<sdkPath>] [<bomPath>] [<repoRoot>]
 * exit  0 = admitted, 1 = refused
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println(
            "usage: candidate-admission <zip> <productVersion> <gitCommit> <outDir> " +
                "[<candidateRef>] [<candidateSequence>] [<sbomPath>] [<sdkPath>] [<bomPath>] [<repoRoot>]",
        )
        exitProcess(2)
    }

    val zip = Paths.get(args[0])
    if (!Files.isRegularFile(zip)) {
        System.err.println("[release] FATAL — distribution ZIP not found: $zip")
        exitProcess(1)
    }

    val outDir = Paths.get(args[3])
    // `covered` is the list of distributable assets (zip, sbom, sdk.zip,
    // bom) whose SHA-256s are written into SHA256SUMS by the materializer.
    // The Gradle task passes a `;`-separated list. The list is required
    // to be non-empty when a `sha256sums` path is provided: an empty
    // SHA256SUMS file would cover nothing and is the kind of unbacked
    // claim the manifest protocol bans.
    val coveredRaw = args.getOrNull(10).orEmpty()
    val covered: List<Path> = if (coveredRaw.isBlank() || coveredRaw == "-") {
        emptyList()
    } else {
        coveredRaw.split(";").map(Paths::get)
    }
    val outcome = CandidateAdmission.admit(
        zip = zip,
        productVersion = args[1],
        gitCommit = args[2],
        candidateRef = args.getOrNull(4)?.takeIf { it.isNotBlank() && it != "-" },
        toolchain = "Gradle ${projectVersionOf(args)}",
        sbom = args.getOrNull(6)?.takeIf { it.isNotBlank() && it != "-" }?.let { Paths.get(it) },
        sdk = args.getOrNull(7)?.takeIf { it.isNotBlank() && it != "-" }?.let { Paths.get(it) },
        bom = args.getOrNull(8)?.takeIf { it.isNotBlank() && it != "-" }?.let { Paths.get(it) },
        sha256sums = outDir.resolve("SHA256SUMS"),
        covered = covered,
        candidateSequence = args.getOrNull(5)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 1,
        outDir = outDir,
        // The law is enforced from observed version control state, not
        // from the commit string the caller passed. Passing the caller's own
        // claim back in would make the gate check itself.
        provenanceFacts = SourceProvenanceProbe.probe(
            // The repository whose version control state defines this
            // candidate's provenance. When the caller omits arg 7, the process
            // cwd (Paths.get("") — no global System property read)
            // is the explicit fallback.
            Paths.get(args.getOrNull(9) ?: Paths.get("").toAbsolutePath().toString()),
        ),
    )

    when (outcome) {
        is AdmissionOutcome.Refused -> {
            System.err.println("[release] candidate admission REFUSED")
            System.err.println(outcome.reason)
            System.err.println(
                "A refused candidate MUST NOT be published. This is a build defect: fix the " +
                    "version/identity and rebuild. Do not hand-edit the emitted documents.",
            )
            exitProcess(1)
        }

        is AdmissionOutcome.Admitted -> {
            println("[release] candidate admission PASSED")
            println("[release]   candidate_id: ${outcome.candidateId}")
            println("[release]   identity: ${outcome.identityVerdict.trim()}")
            println("[release]   provenance: ${outcome.provenanceVerdict.trim()}")
            // The plugin verdict was computed, and a refusal would have stopped
            // this build — but a PASS said nothing about WHICH plugins were
            // checked. The release log is the only place where the operator
            // can see what the gate looked at, and a check whose result is
            // thrown away when it passes is a check nobody can audit.
            println("[release]   plugins: ${outcome.pluginCompatibilityVerdict.trim()}")
            println("[release]   manifest: ${outcome.manifestPath}")
            println("[release]   handoff:  ${outcome.handoffPath}")
            println(
                "[release] The candidate is immutable. Hand it to pipelinek-release-harness " +
                    "and continue main without waiting for the verdict.",
            )
            exitProcess(0)
        }
    }
}

/** Toolchain string; kept trivial so the CLI has no build-system coupling. */
private fun projectVersionOf(args: Array<String>): String =
    "candidate-admission (product ${args.getOrNull(1) ?: "?"})"
