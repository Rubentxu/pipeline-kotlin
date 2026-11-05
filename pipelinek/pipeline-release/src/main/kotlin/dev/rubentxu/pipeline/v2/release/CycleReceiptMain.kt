package dev.rubentxu.pipeline.v2.release

import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Command-line entry point that composes the receipt of a certification
 * cycle.
 *
 * It is a thin shell over [CycleReceiptProducer]: it parses the arguments,
 * delegates, prints the diagnostic, and maps the outcome to an exit code. It
 * decides nothing on its own.
 *
 * ```text
 * usage: certification-cycle-receipt <candidate.json> <request.json> <certification.json> <out.json> [<defect-or-promotion.json>...]
 * exit  0 = the receipt was stated, 1 = refused, 2 = usage error
 * ```
 *
 * The extra arguments are the documents of the cycle that are not the three
 * the receipt cannot be built without: the defect reports observed against
 * the candidate, and the certification of the promotion when the candidate
 * got that far. Each one is classified by what it says, so the order they
 * come in does not matter.
 */
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println(
            "usage: certification-cycle-receipt <candidate.json> <request.json> " +
                "<certification.json> <out.json> [<defect-or-promotion.json>...]",
        )
        exitProcess(2)
    }

    val outcome = CycleReceiptProducer.produce(
        candidatePath = Paths.get(args[0]),
        requestPath = Paths.get(args[1]),
        certificationPath = Paths.get(args[2]),
        outPath = Paths.get(args[3]),
        extraPaths = args.drop(4).map { Paths.get(it) },
    )

    when (outcome) {
        is CycleReceiptOutcome.Refused -> {
            System.err.println("[release] cycle receipt REFUSED")
            System.err.println(outcome.reason)
            exitProcess(1)
        }

        is CycleReceiptOutcome.Accepted -> {
            println("[release] cycle receipt stated")
            println("[release]   ${outcome.render().trim()}")
            exitProcess(0)
        }
    }
}
