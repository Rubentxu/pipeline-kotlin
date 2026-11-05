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
 * usage: certification-request <handoff.json> <catalogue.json> <out.json>
 * exit  0 = the request was stated, 1 = refused, 2 = usage error
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println(
            "usage: certification-request <handoff.json> <catalogue.json> <out.json>",
        )
        exitProcess(2)
    }

    val outcome = CertificationRequestProducer.produce(
        handoffPath = Paths.get(args[0]),
        cataloguePath = Paths.get(args[1]),
        requestedAt = Instant.now(),
        outPath = Paths.get(args[2]),
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
