package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * The emission of the certification request event, with its effects.
 *
 * The producer is the effectful half of the emission: it reads the documents,
 * asks the model what to do with them, and writes what the model produced. It
 * re-derives no rule of its own, so the release path and the tested model
 * cannot drift apart.
 *
 * It is also the part that knows WHERE a message goes. The model does not
 * know: the model asks, through a port, what an earlier emission of the same
 * candidate left behind, and the producer answers by looking in the inbox the
 * contract declares. The inbox is therefore not a path this file repeats: it
 * is read out of the contract, and the contract says where a receiver has to
 * look.
 */
object CertificationEmissionProducer {

    /**
     * Emit the event that asks for the certification of the published
     * candidate, and write the receipt of the emission.
     *
     * The message lands in the inbox the reception contract declares, under
     * the name that contract declares for this candidate. Nothing else is
     * written there: a receiver that finds a message in the inbox finds one
     * message about one candidate.
     */
    fun emit(
        handoffPath: Path,
        requestPath: Path,
        publicationReceiptPath: Path,
        publishedAt: Instant,
        contractPath: Path,
        receiptPath: Path,
        environment: Map<String, String>,
    ): CertificationEmissionOutcome {
        val contractText = readDocument(contractPath)
            ?: return CertificationEmissionOutcome.Failed(
                "the reception contract at $contractPath could not be read. A message with no " +
                    "declared inbox has no place to land, and an emission that lands it anywhere " +
                    "else is a message the receiver will not find.",
            )

        val contract = when (val decoded = ReceptionContractCodec.decode(contractText)) {
            is ReceptionContractDecodeResult.Accepted -> decoded.contract
            is ReceptionContractDecodeResult.Rejected -> return CertificationEmissionOutcome.Failed(
                "the reception contract at $contractPath is not one this build can act on: " +
                    "${decoded.rejection.describe()}",
            )
        }

        val inbox = contract.inboxDirectory(environment)
            ?: return CertificationEmissionOutcome.Failed(
                "the reception contract names no inbox that this machine can resolve. The " +
                    "contract needs ${contract.inbox.environmentVariable}, or " +
                    "${contract.inbox.default.baseEnvironmentVariable}, or a home directory.",
            )

        val handoffText = readDocument(handoffPath)
            ?: return CertificationEmissionOutcome.Failed(
                "the candidate handoff at $handoffPath could not be read. An emission that reads " +
                    "nothing emits nothing, and says so.",
            )
        val requestText = readDocument(requestPath)
            ?: return CertificationEmissionOutcome.Failed(
                "the certification request at $requestPath could not be read. An emission that " +
                    "reads nothing emits nothing, and says so.",
            )
        val publicationReceipt = readDocument(publicationReceiptPath)
            ?: return CertificationEmissionOutcome.Failed(
                "the publication receipt at $publicationReceiptPath could not be read. A candidate " +
                    "that was published leaves a receipt, and a request about bytes the receiver " +
                    "cannot download is worse than no request.",
            )

        // The port: the model asks what an earlier emission of this candidate
        // left behind, and the answer comes from the inbox the contract
        // declares. The name of the file is the contract's own template with
        // the digest of the candidate in it.
        val previousEmission: (CandidateId) -> String? = { candidateId ->
            val path = Paths.get(inbox).resolve(contract.messageFileName(candidateId))
            if (Files.isRegularFile(path)) Files.readString(path) else null
        }

        val outcome = certificationRequestedEmission(
            handoffText = handoffText,
            requestText = requestText,
            publicationReceipt = publicationReceipt,
            publishedAt = publishedAt,
            previousEmission = previousEmission,
        )

        return when (outcome) {
            is CertificationEmission.Ready -> {
                val eventFile = Paths.get(inbox).resolve(contract.messageFileName(outcome.event.candidateId))
                val failure = writeFile(eventFile, outcome.document)
                    ?: writeFile(receiptPath, outcome.receipt)
                if (failure != null) {
                    CertificationEmissionOutcome.Failed(failure)
                } else {
                    CertificationEmissionOutcome.Emitted(
                        event = outcome.event,
                        eventFile = eventFile,
                        receiptFile = receiptPath,
                    )
                }
            }

            is CertificationEmission.AlreadyEmitted -> {
                val failure = writeFile(receiptPath, outcome.receipt)
                if (failure != null) {
                    CertificationEmissionOutcome.Failed(failure)
                } else {
                    CertificationEmissionOutcome.AlreadyEmitted(receiptFile = receiptPath)
                }
            }

            is CertificationEmission.Refused ->
                CertificationEmissionOutcome.Refused(outcome.reason)
        }
    }

    /**
     * The contents of a document the emission reads, or null when it cannot
     * be read.
     *
     * The reason is not returned with it: the caller has one decision to
     * make — the emission cannot go on — and the diagnostic belongs to the
     * place that observed the failure, which knows what it was reading.
     */
    private fun readDocument(path: Path): String? {
        if (!Files.isRegularFile(path)) return null
        return try {
            Files.readString(path)
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Write a document, creating the directory that holds it.
     *
     * It returns the reason it could not be written, and not an exception: a
     * message that cannot land in the inbox is an outcome of the emission
     * and not a crash of the program, and the operator has to read WHY.
     */
    private fun writeFile(path: Path, content: String): String? {
        return try {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, content)
            null
        } catch (e: IOException) {
            "the document at $path could not be written: ${e.message ?: e::class.java.simpleName}"
        }
    }
}

/**
 * What emitting the certification request may yield.
 */
sealed interface CertificationEmissionOutcome {

    /** The message was written to the inbox, and the receipt with it. */
    data class Emitted(
        val event: CertificationRequestedEvent,
        val eventFile: Path,
        val receiptFile: Path,
    ) : CertificationEmissionOutcome

    /** The same question had already been asked; nothing was written twice. */
    data class AlreadyEmitted(val receiptFile: Path) : CertificationEmissionOutcome

    /** The emission was refused, and [reason] says why. */
    data class Refused(val reason: String) : CertificationEmissionOutcome

    /** The emission could not be carried out, and [reason] says why. */
    data class Failed(val reason: String) : CertificationEmissionOutcome
}

/**
 * Operator-facing diagnostic for the outcome of an emission.
 */
fun CertificationEmissionOutcome.render(): String = when (this) {
    is CertificationEmissionOutcome.Emitted ->
        "certification request emitted: ${event.candidateId.canonical} published as " +
            "${event.release.tag} at ${eventFile}"

    is CertificationEmissionOutcome.AlreadyEmitted ->
        "certification request ALREADY emitted: nothing was written twice"

    is CertificationEmissionOutcome.Refused ->
        "certification request NOT emitted: $reason"

    is CertificationEmissionOutcome.Failed ->
        "certification request NOT emitted: $reason"
}
