package dev.rubentxu.pipeline.v2.release

import java.nio.file.Paths
import java.time.DateTimeException
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Command-line entry point for the emission of the certification request event.
 *
 * The task that runs this main resolves the paths and hands the arguments
 * over; it re-derives no rule, so the build and the tested model cannot drift
 * apart. Everything this main decides, it decides by calling
 * [CertificationEmissionProducer]: it reads the documents, asks the model what
 * to do with them, writes what the model produced, and maps the outcome to an
 * exit code.
 *
 * The publication receipt is one of the inputs because it is the fact of the
 * publication. A candidate that was built and never published leaves no
 * receipt, and a request about bytes the receiver cannot download is worse
 * than no request.
 *
 * The reception contract is one of the inputs because it is where the message
 * goes. The caller does not name the inbox: the contract declares it, and a
 * caller that named a different place would put the message where the
 * receiver does not look.
 *
 * ```text
 * usage: certification-requested <handoff> <request> <publicationReceipt>
 *                               <publishedAt> <contract> <receiptFile>
 * exit  0 = emitted, or already emitted; 1 = refused; 2 = usage error
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 6) {
        System.err.println(
            "usage: certification-requested <handoff> <request> <publicationReceipt> " +
                "<publishedAt> <contract> <receiptFile>",
        )
        exitProcess(2)
    }

    val publishedAt = try {
        Instant.parse(args[3])
    } catch (e: DateTimeException) {
        System.err.println(
            "[release] certification request NOT emitted — '${args[3]}' is not an instant.",
        )
        exitProcess(2)
    }

    val outcome = CertificationEmissionProducer.emit(
        handoffPath = Paths.get(args[0]),
        requestPath = Paths.get(args[1]),
        publicationReceiptPath = Paths.get(args[2]),
        publishedAt = publishedAt,
        contractPath = Paths.get(args[4]),
        receiptPath = Paths.get(args[5]),
        environment = System.getenv(),
    )

    when (outcome) {
        is CertificationEmissionOutcome.Emitted -> {
            println("[release] rc.certification.requested EMITTED")
            println("[release]   event: ${outcome.eventFile}")
            println("[release]   receipt: ${outcome.receiptFile}")
            exitProcess(0)
        }

        is CertificationEmissionOutcome.AlreadyEmitted -> {
            println("[release] rc.certification.requested ALREADY emitted — nothing was written twice")
            println("[release]   receipt: ${outcome.receiptFile}")
            exitProcess(0)
        }

        is CertificationEmissionOutcome.Refused -> {
            System.err.println("[release] rc.certification.requested NOT emitted")
            System.err.println(outcome.reason)
            exitProcess(1)
        }

        is CertificationEmissionOutcome.Failed -> {
            System.err.println("[release] rc.certification.requested NOT emitted")
            System.err.println(outcome.reason)
            exitProcess(2)
        }
    }
}
