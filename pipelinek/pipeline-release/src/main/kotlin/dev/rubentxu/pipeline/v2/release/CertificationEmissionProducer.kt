package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
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
     * the name that contract declares for this candidate, and it lands
     * WHOLE: it is written beside its name and moved onto it, so a receiver
     * that finds a message in the inbox finds one whole message about one
     * candidate. Nothing else is written there.
     */
    fun emit(
        handoffPath: Path,
        requestPath: Path,
        publicationReceiptPath: Path,
        publishedAt: Instant,
        contractPath: Path,
        receiptPath: Path,
        environment: Map<String, String>,
    ): CertificationEmissionOutcome = emit(
        handoffPath = handoffPath,
        requestPath = requestPath,
        publicationReceiptPath = publicationReceiptPath,
        publishedAt = publishedAt,
        contractPath = contractPath,
        receiptPath = receiptPath,
        environment = environment,
        writeDocument = { path, content -> Files.writeString(path, content) },
    )

    /**
     * The emission, with the way a document is written made explicit.
     *
     * The seam exists because the guarantee is about WHAT a receiver finds on
     * the disk, and a guarantee about the disk needs an instrument that can
     * break a write on purpose: the failure that leaves a message half
     * written cannot be asked for politely, and a test that waits for a race
     * to lose is a test that passes by luck.
     *
     * It is `internal` because how a document is written is not a choice the
     * callers of the emission get to make: the emission publishes whole
     * documents, and that is what it does.
     */
    internal fun emit(
        handoffPath: Path,
        requestPath: Path,
        publicationReceiptPath: Path,
        publishedAt: Instant,
        contractPath: Path,
        receiptPath: Path,
        environment: Map<String, String>,
        writeDocument: (Path, String) -> Unit,
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
                val failure = writeFile(eventFile, outcome.document, writeDocument)
                    ?: writeFile(receiptPath, outcome.receipt, writeDocument)
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
                val failure = writeFile(receiptPath, outcome.receipt, writeDocument)
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
     * Write a document and publish it where it belongs.
     *
     * The document is written BESIDE its destination and moved onto it, and
     * not written onto it: a file that is created before its content is
     * there is a file a receiver can read halfway. The receiver of a
     * certification request reads the messages it finds in the inbox, and a
     * message it cannot parse is a message it has to leave pending — so a
     * write that breaks halfway would leave the certification of that
     * candidate unrequested, with nothing to say why.
     *
     * A write that fails leaves nothing behind: the temporary is deleted and
     * the destination is not touched, so the inbox holds what it held before
     * the emission and no half-written document.
     *
     * It returns the reason it could not be written, and not an exception: a
     * message that cannot land in the inbox is an outcome of the emission
     * and not a crash of the program, and the operator has to read WHY.
     */
    private fun writeFile(
        path: Path,
        content: String,
        writeDocument: (Path, String) -> Unit,
    ): String? {
        return try {
            path.parent?.let { Files.createDirectories(it) }
            val temporary = temporaryBeside(path)
            try {
                writeDocument(temporary, content)
                moveIntoPlace(temporary, path)
                null
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(temporary) }
                throw e
            }
        } catch (e: IOException) {
            "the document at $path could not be written: ${e.message ?: e::class.java.simpleName}"
        }
    }

    /**
     * The file a document is written to before it is published.
     *
     * It is a SIBLING of the destination, because a rename is atomic only
     * within one filesystem, and it carries a name that cannot be read as a
     * message: the reception contract's pattern is anchored, so a file that
     * is not exactly the name of a message is not one, and the receiver
     * ignores it instead of refusing it.
     *
     * The instant in the name is what keeps two emissions of the same
     * candidate from writing the same temporary: the bytes of two writers in
     * one file are bytes nobody can read.
     */
    private fun temporaryBeside(path: Path): Path =
        path.resolveSibling("${path.fileName}.${System.nanoTime()}.tmp")

    /**
     * Move a written document onto its destination.
     *
     * The atomic move IS the publication: a receiver sees the document whole
     * or not at all. A filesystem that cannot move atomically falls back to a
     * replacing move, which is not atomic but is still a move of complete
     * bytes; the fallback exists because refusing to publish on such a
     * filesystem would be a worse answer than publishing the document there.
     */
    private fun moveIntoPlace(temporary: Path, destination: Path) {
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
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
