package dev.rubentxu.pipeline.v2.release

import java.nio.file.Paths
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Command-line entry point that states a certification request.
 *
 * It is a thin shell over [CertificationRequestProducer]: it parses
 * arguments, delegates, prints the diagnostic, and maps the outcome to an
 * exit code. The only thing it decides on its own is WHEN the request is
 * made, because the moment of a request is a fact of the world and not a
 * fact of the model.
 *
 * ```text
 * usage: certification-request <handoff.json> <catalogue.json> <out.json> [<defect.reported.json>]
 * exit  0 = the request was stated, 1 = refused, 2 = usage error
 * ```
 *
 * The fourth argument is the report of the defect this candidate answers. It
 * is optional, and when it is there the request says which defect it answers
 * and the candidate the defect was seen against. The report is READ, never
 * written: this product does not report defects, it receives them.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println(
            "usage: certification-request <handoff.json> <catalogue.json> <out.json> " +
                "[<defect.reported.json>]",
        )
        exitProcess(2)
    }

    val correctionReportPath = args.getOrNull(3)
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }

    val outcome = CertificationRequestProducer.produce(
        handoffPath = Paths.get(args[0]),
        cataloguePath = Paths.get(args[1]),
        requestedAt = Instant.now(),
        outPath = Paths.get(args[2]),
        correctionReportPath = correctionReportPath,
    )

    when (outcome) {
        is CertificationRequestOutcome.Refused -> {
            System.err.println("[release] certification request REFUSED")
            System.err.println(outcome.reason)
            exitProcess(1)
        }

        is CertificationRequestOutcome.Accepted -> {
            println("[release] certification request stated")
            println("[release]   ${outcome.render().trim()}")
            exitProcess(0)
        }
    }
}
