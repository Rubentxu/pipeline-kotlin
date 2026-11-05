package dev.rubentxu.pipeline.v2.release

import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Command-line entry point for the certification gate.
 *
 * Gradle cannot call Kotlin project code directly, so the task runs this main
 * against the release module's runtime classpath. It is intentionally a thin
 * shell over [CertificationAdmission]: it parses arguments, delegates, prints
 * the diagnostic, and maps [CertificationAdmissionOutcome] to an exit code. It
 * contains no admission rule of its own, so the release script and the tests
 * cannot disagree with the model.
 *
 * ```text
 * usage: certification-admission <request.json> <receipt.json>
 * exit  0 = the receipt admits the candidate, 1 = refused, 2 = usage error
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: certification-admission <request.json> <receipt.json>")
        exitProcess(2)
    }

    val outcome = CertificationAdmission.admit(Paths.get(args[0]), Paths.get(args[1]))

    when (outcome) {
        is CertificationAdmissionOutcome.Refused -> {
            System.err.println("[release] certification admission REFUSED")
            System.err.println(outcome.reason)
            System.err.println(
                "The candidate is not certified, so nothing is published. The reason is above.",
            )
            exitProcess(1)
        }

        is CertificationAdmissionOutcome.Admitted -> {
            println("[release] certification admission PASSED")
            println("[release]   candidate_id: ${outcome.candidateId}")
            println("[release]   battery: ${outcome.battery}")
            println("[release]   verdict: ${outcome.summary.trim()}")
            exitProcess(0)
        }
    }
}
