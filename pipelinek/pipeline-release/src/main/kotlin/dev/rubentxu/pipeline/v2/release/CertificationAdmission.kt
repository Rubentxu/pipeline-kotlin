package dev.rubentxu.pipeline.v2.release

import java.nio.file.Files
import java.nio.file.Path

/**
 * The interpreter at the certification boundary.
 *
 * The pure decider ([evaluateCertificationReceipt]) is the authority on
 * whether a receipt admits a candidate. This object is the effectful
 * interpreter that turns that verdict into a publication outcome: it reads the
 * two documents, prints the operator diagnostic, and returns a value the
 * release path can fail on.
 *
 * Keeping the pass/fail decision out of here is deliberate. A script that
 * re-derived the rules could disagree with the model, and the model is what
 * the tests pin. This only reports and refuses.
 */
object CertificationAdmission {

    /**
     * Read the request and the receipt, and decide whether the receipt admits
     * the candidate the request asked about.
     *
     * A document that cannot be read, or that is not the document this build
     * understands, refuses the admission with the reason: an unreadable
     * receipt is not a receipt that admits nothing, it is a receipt nobody
     * can account for, and publishing on the strength of it would be exactly
     * the mistake the boundary exists to prevent.
     */
    fun admit(requestPath: Path, receiptPath: Path): CertificationAdmissionOutcome {
        val requestText = readText(requestPath)
            ?: return CertificationAdmissionOutcome.Refused(
                "the certification request at $requestPath does not exist or cannot be read. A " +
                    "candidate is published only against a request that names it.",
            )

        val request = when (val decoded = CertificationRequestCodec.decode(requestText)) {
            is CertificationRequestDecodeResult.Rejected -> return CertificationAdmissionOutcome.Refused(
                "the certification request at $requestPath is not a request this build can act " +
                    "on: ${decoded.render()}",
            )

            is CertificationRequestDecodeResult.Accepted -> decoded.request
        }

        val receiptText = readText(receiptPath)
            ?: return CertificationAdmissionOutcome.Refused(
                "the certification receipt at $receiptPath does not exist or cannot be read. A " +
                    "candidate is published only with a receipt that admits it.",
            )

        val receipt = when (val decoded = CertificationReceiptCodec.decode(receiptText)) {
            is CertificationReceiptDecodeResult.Rejected -> return CertificationAdmissionOutcome.Refused(
                "the certification receipt at $receiptPath is not a receipt this build can act " +
                    "on: ${decoded.render()}",
            )

            is CertificationReceiptDecodeResult.Accepted -> decoded.receipt
        }

        return when (val verdict = evaluateCertificationReceipt(request, receipt)) {
            is CertificationAdmissionVerdict.Admissible -> CertificationAdmissionOutcome.Admitted(
                candidateId = verdict.receipt.candidateId.canonical,
                battery = "${verdict.receipt.battery.id} ${verdict.receipt.battery.version}",
                summary = verdict.render(),
            )

            is CertificationAdmissionVerdict.Rejected -> CertificationAdmissionOutcome.Refused(
                verdict.render(),
            )
        }
    }

    /** The text of the document at [path], or null when it cannot be read. */
    private fun readText(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (e: java.io.IOException) {
        null
    }
}

/** Outcome of the certification admission at the publication boundary. */
sealed interface CertificationAdmissionOutcome {

    /**
     * The receipt admits the candidate. [summary] is the verdict's own
     * diagnostic, carried through verbatim so the release log shows what the
     * gate concluded rather than a flattened "OK".
     */
    data class Admitted(
        val candidateId: String,
        val battery: String,
        val summary: String,
    ) : CertificationAdmissionOutcome

    /** The candidate is not admitted. The publication MUST NOT continue. */
    data class Refused(val reason: String) : CertificationAdmissionOutcome
}
