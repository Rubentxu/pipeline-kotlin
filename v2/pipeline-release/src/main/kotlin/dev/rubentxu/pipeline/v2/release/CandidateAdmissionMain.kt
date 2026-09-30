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
 *                      [<candidateRef>] [<candidateSequence>] [<sbomPath>] [<repoRoot>]
 * exit  0 = admitted, 1 = refused
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println(
            "usage: candidate-admission <zip> <productVersion> <gitCommit> <outDir> " +
                "[<candidateRef>] [<candidateSequence>] [<sbomPath>] [<repoRoot>]",
        )
        exitProcess(2)
    }

    val zip = Paths.get(args[0])
    if (!Files.isRegularFile(zip)) {
        System.err.println("[release] FATAL — distribution ZIP not found: $zip")
        exitProcess(1)
    }

    val outDir = Paths.get(args[3])
    val outcome = CandidateAdmission.admit(
        zip = zip,
        productVersion = args[1],
        gitCommit = args[2],
        candidateRef = args.getOrNull(4)?.takeIf { it.isNotBlank() && it != "-" },
        toolchain = "Gradle ${projectVersionOf(args)}",
        sbom = args.getOrNull(6)?.takeIf { it.isNotBlank() && it != "-" }?.let { Paths.get(it) },
        sha256sums = null,
        candidateSequence = args.getOrNull(5)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 1,
        outDir = outDir,
        // P0.5 — the law is enforced from observed version control state, not
        // from the commit string the caller passed. Passing the caller's own
        // claim back in would make the gate check itself.
        provenanceFacts = SourceProvenanceProbe.probe(
            // P0.5 — the repository whose version control state defines this
            // candidate's provenance. When the caller omits arg 7, the process
            // cwd (Paths.get("") — no global System property read, LFC0-006)
            // is the explicit fallback.
            Paths.get(args.getOrNull(7) ?: Paths.get("").toAbsolutePath().toString()),
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
