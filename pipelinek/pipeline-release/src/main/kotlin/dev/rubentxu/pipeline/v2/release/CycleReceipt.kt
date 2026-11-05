package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The receipt of a whole certification cycle, for one candidate.
 *
 * This document binds the documents of one certification cycle, so that a
 * reader can answer "what happened to THIS artifact" by reading ONE file.
 *
 * The cycle materialises a candidate, asks for a certification, receives a
 * receipt, may find defects against it, and may promote it to a stable
 * release. Each of those steps left a document of its own, and each one names
 * the candidate it is about. None of them says which artifact the OTHERS are
 * about, so a reader who wanted the story had to open five documents and
 * check by hand that they talked about the same bytes.
 *
 * The receipt binds them BY DIGEST. Every fact in it names the candidate, and
 * the composition refuses to write the receipt when they do not name the same
 * one: a receipt that binds a request about one candidate to a certification
 * of another is not a receipt, it is a coincidence.
 *
 * It is a COMPOSITION and not a re-measurement. No digest in here is
 * computed: the candidate identity is copied from the candidate's own
 * document and compared against the others. A document that re-derived the
 * digest of the bytes it describes would be a second measurement of the same
 * file, and two measurements of one file that disagree are two measurements.
 *
 * It does not say the artifact was good. It says which steps happened and
 * which of them failed: a certification that reports a failed scenario is
 * written down, with the violation named, because "what happened to this
 * artifact" includes the answers that are not yes.
 *
 * The constructor is internal on purpose. This product composes the receipt
 * out of documents that already exist; a receipt is not something a caller
 * may state by hand.
 */
@Serializable
data class CycleReceipt internal constructor(
    /** The shape this document claims to follow. */
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The candidate every fact in this receipt is about. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The candidate's own document. */
    val candidate: CandidateHandoff,
    /** The request that asked for the certification. */
    val request: CertificationRequest,
    /** The receipt the certifier returned. */
    val certification: CertificationReceipt,
    /** What that receipt says about that request. */
    val admission: CycleAdmission,
    /** The defects observed against this candidate. */
    val defects: List<CandidateDefect>,
    /** The certification of the promotion, when the candidate got that far. */
    val promotion: PromotionCertification?,
) {
    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-cycle-receipt-1"
    }
}

/**
 * What the certification receipt says about the request that asked.
 *
 * This is the admission, and until now it existed only as a line the
 * publisher printed: the decision was made, it was acted upon, and it was
 * never written down anywhere a reader could find it. The receipt keeps the
 * decision and the reasons it went the way it did.
 *
 * The reasons are the codes and not the prose. A consumer that groups the
 * violations by code can tell "the receipt is about another candidate" from
 * "a scenario did not pass" without reading a sentence, and a consumer that
 * reads only the prose is reading prose.
 */
@Serializable
data class CycleAdmission internal constructor(
    /** Whether the receipt admits the candidate the request asked about. */
    val admitted: Boolean,
    /** The violations that stand against the admission, empty when it holds. */
    val violations: List<CertificationAdmissionViolation>,
)

/**
 * A defect observed against a candidate, as the cycle receipt states it.
 *
 * The defect travels by its FOOTPRINT and not by its evidence. The footprint
 * is the four facts that identify the defect and that both sides compute the
 * identity from; the evidence — the commands, the logs, the expected and the
 * observed — belongs to the record the certifier keeps, and a cycle receipt
 * that carried every log of every defect would be a transcript.
 *
 * The candidate it was observed against travels too, and it is the fact that
 * makes the coherence check possible: a defect observed against another
 * candidate is not a defect of this one.
 */
@Serializable
data class CandidateDefect internal constructor(
    /** The candidate the defect was observed against. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The structural footprint of the defect. */
    val defect: DefectFingerprint,
) {
    companion object {
        /**
         * The defect of [event], as the receipt states it.
         */
        fun of(event: DefectReportedEvent): CandidateDefect =
            CandidateDefect(candidateId = event.candidateId, defect = event.defect)
    }
}

/**
 * What composing a cycle receipt may yield.
 */
sealed interface CycleReceiptOutcome {

    /** The documents agree with each other, and the receipt binds them. */
    data class Accepted(val receipt: CycleReceipt) : CycleReceiptOutcome

    /**
     * The documents do not agree, and [reason] says which ones and how.
     *
     * The receipt is not written. A receipt that binds documents about
     * different artifacts is not a receipt with a defect: it is a document
     * that says something false about both of them.
     */
    data class Refused(val reason: String) : CycleReceiptOutcome
}

/**
 * Operator-facing diagnostic for a composed receipt.
 */
fun CycleReceiptOutcome.render(): String = when (this) {
    is CycleReceiptOutcome.Accepted -> buildString {
        append("cycle receipt stated: ${receipt.candidateId.canonical}")
        append(" (certification ${receipt.certification.decision}")
        append(", ${receipt.certification.runs.size} scenario(s)")
        if (!receipt.admission.admitted) {
            append(", admission REFUSED with ${receipt.admission.violations.size} violation(s)")
        }
        if (receipt.defects.isNotEmpty()) {
            append(", ${receipt.defects.size} defect(s)")
        }
        receipt.promotion?.let { append(", promotion ${it.decision}") }
        append(")")
    }

    is CycleReceiptOutcome.Refused -> "cycle receipt REFUSED: $reason"
}

/**
 * Compose the receipt of the cycle that [candidate] went through.
 *
 * The documents are the facts, and they are taken as they are: nothing is
 * measured here and nothing is derived except the admission, which is
 * derived by the ONE function that already holds those rules
 * ([evaluateCertificationReceipt]) rather than by a second reading of them.
 *
 * The composition refuses when the documents do not agree about WHICH
 * artifact they are about. It does NOT refuse when the certification failed:
 * a certification that reports a failed scenario is an outcome of the cycle,
 * and a receipt that refuses to record it would leave the failure only in a
 * console log. The line between the two is the identity and not the verdict.
 *
 * Pure: it reads the values it is given and returns a decision. It touches no
 * filesystem and no clock.
 */
fun composeCycleReceipt(
    candidate: CandidateHandoff,
    request: CertificationRequest,
    certification: CertificationReceipt,
    defects: List<CandidateDefect>,
    promotion: PromotionCertification?,
): CycleReceiptOutcome {
    val named = mutableListOf<Pair<String, CandidateId>>()
    named += "the request" to request.candidateId
    named += "the certification receipt" to certification.candidateId
    defects.forEachIndexed { index, defect ->
        named += "the defect report #$index" to defect.candidateId
    }
    promotion?.let { named += "the promotion certification" to it.candidateId }

    val other = named.firstOrNull { it.second != candidate.candidateId }
    if (other != null) {
        return CycleReceiptOutcome.Refused(
            "${other.first} names candidate ${other.second.canonical} and the candidate's own " +
                "document names ${candidate.candidateId.canonical}. A receipt that binds " +
                "documents about different artifacts is not a receipt of either of them.",
        )
    }

    val repeated = defects.groupBy { it.defect }.filterValues { it.size > 1 }.keys
    if (repeated.isNotEmpty()) {
        return CycleReceiptOutcome.Refused(
            "the defect in scenario(s) ${repeated.map { it.scenario }.sorted().joinToString(", ")} " +
                "was reported more than once. A defect observed twice is one defect, and a " +
                "receipt that counts it twice states a number nobody measured.",
        )
    }

    val admission = when (val verdict = evaluateCertificationReceipt(request, certification)) {
        is CertificationAdmissionVerdict.Admissible ->
            CycleAdmission(admitted = true, violations = emptyList())

        is CertificationAdmissionVerdict.Rejected ->
            CycleAdmission(admitted = false, violations = verdict.violations)
    }

    return CycleReceiptOutcome.Accepted(
        CycleReceipt(
            candidateId = candidate.candidateId,
            candidate = candidate,
            request = request,
            certification = certification,
            admission = admission,
            defects = defects,
            promotion = promotion,
        ),
    )
}
