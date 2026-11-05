package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Composes the receipt of a certification cycle out of the documents the
 * cycle produced.
 *
 * The producer is the effectful half: it reads the documents, hands the
 * values to [composeCycleReceipt] — which holds the rules — and writes the
 * receipt when the composition holds. It re-derives no rule of its own, so
 * the build and the tested model cannot drift apart.
 *
 * The extra documents are classified by WHAT THEY SAY and not by where they
 * sit in the command line. A defect report and a promotion certification are
 * different documents with different schemas, and each of them is refused by
 * the reader of the other one, so a file that is neither can be named as
 * neither. That is what lets the caller hand over the documents of a cycle
 * without having to say in which order they come.
 */
object CycleReceiptProducer {

    /**
     * Compose the receipt of the cycle described by the documents at the given
     * paths, and write it to [outPath].
     *
     * The receipt is written only when the composition holds. A composition
     * that does not hold writes NO receipt: a receipt that was written and
     * then refused would be a document that says something the receipt
     * itself denies. The refusal says why, and it does not claim that there
     * is no receipt at [outPath]: a receipt of an earlier cycle is a record
     * and this producer does not delete records.
     */
    fun produce(
        candidatePath: Path,
        requestPath: Path,
        certificationPath: Path,
        outPath: Path,
        extraPaths: List<Path> = emptyList(),
    ): CycleReceiptOutcome {
        val candidate = readCandidate(candidatePath)
            ?: return CycleReceiptOutcome.Refused(
                "the candidate document at $candidatePath does not exist, cannot be read, or is " +
                    "not a candidate this build understands. A receipt of the cycle names the " +
                    "candidate, and a candidate this build cannot read is a candidate the receipt " +
                    "cannot be about.",
            )

        val request = when (val decoded = readRequest(requestPath)) {
            is CertificationRequestDecodeResult.Rejected -> return CycleReceiptOutcome.Refused(
                "the certification request at $requestPath is not a request this build can act " +
                    "on: ${decoded.render()}",
            )

            is CertificationRequestDecodeResult.Accepted -> decoded.request
        }

        val certification = when (val decoded = readCertification(certificationPath)) {
            is CertificationReceiptDecodeResult.Rejected -> return CycleReceiptOutcome.Refused(
                "the certification receipt at $certificationPath is not a receipt this build can " +
                    "act on: ${decoded.render()}",
            )

            is CertificationReceiptDecodeResult.Accepted -> decoded.receipt
        }

        val extras = when (val read = readExtras(extraPaths)) {
            is ExtrasReading.Refused -> return CycleReceiptOutcome.Refused(read.reason)
            is ExtrasReading.Read -> read.extras
        }

        val outcome = composeCycleReceipt(
            candidate = candidate,
            request = request,
            certification = certification,
            defects = extras.defects,
            promotion = extras.promotion,
        )

        if (outcome is CycleReceiptOutcome.Accepted) {
            val parent = outPath.parent
            if (parent != null) Files.createDirectories(parent)
            Files.writeString(outPath, CycleReceiptCodec.encode(outcome.receipt))
        }

        return outcome
    }

    /** The candidate's own document, or null when it cannot be read. */
    private fun readCandidate(path: Path): CandidateHandoff? {
        val text = readText(path) ?: return null
        return try {
            CandidateHandoffCodec.decode(text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The certification request, or the reason it is not one. */
    private fun readRequest(path: Path): CertificationRequestDecodeResult {
        val text = readText(path)
            ?: return CertificationRequestDecodeResult.Rejected(
                CertificationRejection.MalformedDocument(
                    "the certification request at $path does not exist or cannot be read.",
                ),
            )
        return CertificationRequestCodec.decode(text)
    }

    /** The certification receipt, or the reason it is not one. */
    private fun readCertification(path: Path): CertificationReceiptDecodeResult {
        val text = readText(path)
            ?: return CertificationReceiptDecodeResult.Rejected(
                CertificationReceiptRejection.MalformedDocument(
                    "the certification receipt at $path does not exist or cannot be read.",
                ),
            )
        return CertificationReceiptCodec.decode(text)
    }

    /**
     * The extra documents of the cycle, classified by what they say.
     *
     * A document that is neither a defect report nor a promotion certification
     * is refused, and the refusal says both what it was read as and what it
     * could not be: a file that nobody can account for is not a document that
     * may be silently dropped.
     */
    private fun readExtras(paths: List<Path>): ExtrasReading {
        val defects = mutableListOf<CandidateDefect>()
        var promotion: PromotionCertification? = null
        for (path in paths) {
            val text = readText(path)
                ?: return ExtrasReading.Refused(
                    "the document at $path does not exist or cannot be read. A cycle that " +
                        "carries a document nobody can read has not been accounted for.",
                )
            when (val defect = DefectReportedEventReader.decode(text)) {
                is DefectReportedEventDecode.Accepted -> defects += CandidateDefect.of(defect.event)

                is DefectReportedEventDecode.Rejected -> {
                    val promotionRead = PromotionCertificationCodec.decode(text)
                    when (promotionRead) {
                        is PromotionCertificationDecode.Accepted -> {
                            if (promotion != null) {
                                return ExtrasReading.Refused(
                                    "the document at $path is a second promotion certification. A " +
                                        "candidate is promoted once, and a receipt that binds two " +
                                        "promotions binds a decision nobody took.",
                                )
                            }
                            promotion = promotionRead.certification
                        }

                        is PromotionCertificationDecode.Rejected -> return ExtrasReading.Refused(
                            "the document at $path is neither a defect report nor a promotion " +
                                "certification. As a defect report it is refused: " +
                                "${defect.rejection.describe()}. As a promotion certification it " +
                                "is refused: ${promotionRead.rejection.describe()}.",
                        )
                    }
                }
            }
        }
        return ExtrasReading.Read(Extras(defects = defects, promotion = promotion))
    }

    /** The text of the document at [path], or null when it cannot be read. */
    private fun readText(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (e: java.io.IOException) {
        null
    }
}

/** The extra documents of a cycle: the defects and the promotion. */
private data class Extras(
    val defects: List<CandidateDefect>,
    val promotion: PromotionCertification?,
)

/** What reading the extra documents of a cycle may yield. */
private sealed interface ExtrasReading {

    /** The documents were read and classified. */
    data class Read(val extras: Extras) : ExtrasReading

    /** A document could not be read, or is not one this build understands. */
    data class Refused(val reason: String) : ExtrasReading
}
