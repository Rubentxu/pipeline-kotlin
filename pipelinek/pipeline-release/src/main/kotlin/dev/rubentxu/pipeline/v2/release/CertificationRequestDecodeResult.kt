package dev.rubentxu.pipeline.v2.release

/**
 * What READING a certification request may yield, or why the document is not
 * one this product can act on.
 *
 * This is the result of the READING side of the cycle, and it is not the
 * same thing as stating a request: stating is [CertificationRequestOutcome],
 * and its refusal means "this producer could not state a request"; this
 * refusal means "this document is not a request I understand". The two live
 * apart because the two ends live apart: the producer states, the certifier
 * reads.
 *
 * A closed type rather than an exception, because the reader of this document
 * did not write it. An exception at that boundary is indistinguishable from a
 * defect in the reader, and the one thing a certifier must be able to say is
 * "this request says something I did not expect, and here is what".
 */
sealed interface CertificationRequestDecodeResult {

    /** The document is a request this build understands. */
    data class Accepted(val request: CertificationRequest) : CertificationRequestDecodeResult

    /** The document is not a request this build can accept. */
    data class Rejected(val rejection: CertificationRejection) : CertificationRequestDecodeResult
}

/** One specific reason a document was refused. */
sealed interface CertificationRejection {

    /**
     * The document carries a field this build does not know.
     *
     * [fields] names every unknown field, not just the first, so one read
     * reveals all of them. A receiver that ignores a field it does not
     * understand cannot claim to have understood the message: the field may
     * carry the instruction that would have changed what the receiver does.
     */
    data class UnknownField(val fields: List<String>) : CertificationRejection

    /** The document is not shaped like a request at all. */
    data class MalformedDocument(val detail: String) : CertificationRejection
}

/**
 * Operator-facing diagnostic for a document that was read.
 *
 * The refusal has to name what was wrong in words somebody can act on: a
 * certifier that could only say "something was wrong" would have to go and
 * find it by hand, in a document it did not write.
 */
fun CertificationRequestDecodeResult.render(): String = when (this) {
    is CertificationRequestDecodeResult.Accepted ->
        "certification request accepted: ${request.candidateId.canonical} against battery " +
            "${request.battery.id} ${request.battery.version} " +
            "(${request.battery.scenarioCount} scenarios)"

    is CertificationRequestDecodeResult.Rejected -> when (val r = rejection) {
        is CertificationRejection.UnknownField ->
            "certification request REJECTED: unknown field(s) ${r.fields.joinToString()}"

        is CertificationRejection.MalformedDocument ->
            "certification request REJECTED: ${r.detail}"
    }
}
