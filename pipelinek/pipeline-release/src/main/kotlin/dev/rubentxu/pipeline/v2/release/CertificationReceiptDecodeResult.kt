package dev.rubentxu.pipeline.v2.release

/**
 * What READING a certification receipt may yield, or why the document is not
 * one this product can act on.
 *
 * A closed type rather than an exception, because the reader of this document
 * did not write it. An exception at that boundary is indistinguishable from a
 * defect in the reader, and the one thing a publisher must be able to say is
 * "this receipt says something I did not expect, and here is what".
 */
sealed interface CertificationReceiptDecodeResult {

    /** The document is a receipt this build understands. */
    data class Accepted(val receipt: CertificationReceipt) : CertificationReceiptDecodeResult

    /** The document is not a receipt this build can accept. */
    data class Rejected(val rejection: CertificationReceiptRejection) : CertificationReceiptDecodeResult
}

/**
 * One specific reason a document was refused.
 *
 * The three reasons are kept apart because they are three different facts
 * about the document, and each one points at a different fix: a field this
 * build does not define is a document written for another shape, a field the
 * schema requires is a document that left out what it had to say, and a
 * malformed document is a document that does not parse at all.
 */
sealed interface CertificationReceiptRejection {

    /**
     * The document carries a field this build does not know.
     *
     * [fields] names every unknown field, not just the first, so one read
     * reveals all of them. A receiver that ignores a field it does not
     * understand cannot claim to have understood the message: the field may
     * carry the instruction that would have changed what the receiver does.
     */
    data class UnknownField(val fields: List<String>) : CertificationReceiptRejection

    /**
     * The document leaves out a field the schema requires.
     *
     * [fields] names every missing field. A receipt that does not say which
     * candidate it is about, or which battery it ran, is not repaired by
     * assuming the missing part: the missing part is exactly what the
     * publisher was about to trust.
     */
    data class MissingField(val fields: List<String>) : CertificationReceiptRejection

    /** The document is not shaped like a receipt at all. */
    data class MalformedDocument(val detail: String) : CertificationReceiptRejection
}

/**
 * Operator-facing diagnostic for a document that was read.
 *
 * The refusal has to name what was wrong in words somebody can act on: a
 * publisher that could only say "something was wrong" would have to go and
 * find it by hand, in a document it did not write.
 */
fun CertificationReceiptDecodeResult.render(): String = when (this) {
    is CertificationReceiptDecodeResult.Accepted ->
        "certification receipt accepted: ${receipt.candidateId.canonical} against battery " +
            "${receipt.battery.id} ${receipt.battery.version} (${receipt.runs.size} scenarios)"

    is CertificationReceiptDecodeResult.Rejected -> when (val r = rejection) {
        is CertificationReceiptRejection.UnknownField ->
            "certification receipt REJECTED: unknown field(s) ${r.fields.joinToString()}"

        is CertificationReceiptRejection.MissingField ->
            "certification receipt REJECTED: missing field(s) ${r.fields.joinToString()}"

        is CertificationReceiptRejection.MalformedDocument ->
            "certification receipt REJECTED: ${r.detail}"
    }
}
