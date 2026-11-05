package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
 * [certificationRequestedEmission]: it reads the documents, asks the model what
 * to do with them, writes what the model produced, and maps the outcome to an
 * exit code.
 *
 * The publication receipt is one of the inputs because it is the fact of the
 * publication. A candidate that was built and never published leaves no
 * receipt, and a request about bytes the receiver cannot download is worse
 * than no request.
 *
 * ```text
 * usage: certification-requested <handoff> <request> <publicationReceipt>
 *                               <publishedAt> <eventFile> <receiptFile>
 * exit  0 = emitted, or already emitted; 1 = refused; 2 = usage error
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 6) {
        System.err.println(
            "usage: certification-requested <handoff> <request> <publicationReceipt> " +
                "<publishedAt> <eventFile> <receiptFile>",
        )
        exitProcess(2)
    }

    val handoffPath = Paths.get(args[0])
    val requestPath = Paths.get(args[1])
    val publicationReceiptPath = Paths.get(args[2])
    val eventFile = Paths.get(args[4])
    val receiptFile = Paths.get(args[5])

    val publishedAt = try {
        Instant.parse(args[3])
    } catch (e: DateTimeException) {
        System.err.println(
            "[release] certification request NOT emitted — '${args[3]}' is not an instant.",
        )
        exitProcess(2)
    }

    val handoffText = readDocument(handoffPath, "the candidate handoff")
    val requestText = readDocument(requestPath, "the certification request")
    val publicationReceipt = readDocument(publicationReceiptPath, "the publication receipt")
    if (handoffText == null || requestText == null || publicationReceipt == null) {
        exitProcess(2)
    }

    // What an earlier emission of this candidate left behind. The event file
    // is named after the candidate, so the file that is there is the one
    // emission this candidate may have.
    val alreadyEmitted = if (Files.isRegularFile(eventFile)) {
        Files.readString(eventFile)
    } else {
        null
    }

    when (
        val outcome = certificationRequestedEmission(
            handoffText = handoffText,
            requestText = requestText,
            publicationReceipt = publicationReceipt,
            publishedAt = publishedAt,
            alreadyEmitted = alreadyEmitted,
        )
    ) {
        is CertificationEmission.Ready -> {
            writeFile(eventFile, outcome.document)
            writeFile(receiptFile, outcome.receipt)
            println("[release] rc.certification.requested EMITTED")
            println("[release]   event: $eventFile")
            println("[release]   receipt: $receiptFile")
            exitProcess(0)
        }

        is CertificationEmission.AlreadyEmitted -> {
            writeFile(receiptFile, outcome.receipt)
            println("[release] rc.certification.requested ALREADY emitted — nothing was written twice")
            println("[release]   event: $eventFile")
            exitProcess(0)
        }

        is CertificationEmission.Refused -> {
            System.err.println("[release] rc.certification.requested NOT emitted")
            System.err.println(outcome.reason)
            exitProcess(1)
        }
    }
}

/**
 * The contents of a document the emission reads, or null when it cannot be
 * read.
 *
 * The reason is printed here and not returned: the caller has one decision to
 * make — the emission cannot go on — and the diagnostic belongs to the place
 * that observed the failure. Nothing but the entry point ends the process.
 */
private fun readDocument(path: Path, what: String): String? {
    if (!Files.isRegularFile(path)) {
        System.err.println(
            "[release] certification request NOT emitted — $what at $path does not exist.",
        )
        return null
    }
    return try {
        Files.readString(path)
    } catch (e: IOException) {
        System.err.println(
            "[release] certification request NOT emitted — $what at $path could not be read: " +
                (e.message ?: e::class.java.simpleName),
        )
        null
    }
}

/**
 * Write a document, creating the directory that holds it.
 */
private fun writeFile(path: Path, content: String) {
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, content)
}
