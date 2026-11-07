package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The certification that a promotion to a stable release requires: the
 * COMPLETE coverage of one candidate.
 *
 * This is not the receipt of the admission. The admission receipt answers ONE
 * request — "certify these bytes against this battery" — and this document
 * answers the wider question a promotion asks: "certify these bytes against
 * everything the cycle demands before a stable release". A partial
 * certification, the canaries and nothing else, is not a certification that
 * is a little weak: it is a certification of something else, and reading it
 * as complete is how a release that was never fully certified gets promoted.
 *
 * The candidate is named by its digest, like everywhere else in this cycle. A
 * certification of another candidate is not a weak certification of this one:
 * it is a certification of other bytes, and the refusal says so by name.
 *
 * The coverage is stated as PARTS. Each part says what it covers, how it
 * ended and what ran, so the coverage a part claims and the coverage it
 * carries can be compared, and a part that is not stated can be named as not
 * stated. "The certification is not complete" is not something an operator
 * can act on; "the scenarios part is missing" is.
 *
 * The shape of this document is named by its schema version, and there are
 * two of them: the one that carries the REASON a part did not apply, and the
 * one that came before and did not. A part that does not apply says why,
 * because a part that was skipped without a word about it is a part nobody
 * can audit. The shape that came before is read exactly as it was read
 * before, so the certifications that were certified before the reason
 * arrived keep being read.
 *
 * What this document does NOT say is that the certifier certified well. It
 * says what was covered and by whom; whether the certification was earned is
 * a question about the certifier, answered where the trust anchor lives and
 * not here, where nothing is anchored yet.
 *
 * The constructor is internal on purpose. This product reads certifications;
 * it does not state them. The only way one enters the product is through the
 * codec, and the codec is where a document is held to its shape.
 */
@Serializable
data class PromotionCertification internal constructor(
    /** The shape this document claims to follow. */
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The candidate this certification is about: the digest of the certified bytes. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The verdict the certifier reached about that candidate. */
    val decision: CertificationDecision,
    /** What was certified, one entry per part of the coverage. */
    val parts: List<CertifiedPart>,
) {
    init {
        require(parts.isNotEmpty()) {
            "a certification that covers nothing is not a certification: parts is empty."
        }
    }

    companion object {
        /**
         * The shape this build reads and writes: the reason a part did not
         * apply travels in the document.
         */
        const val SCHEMA_VERSION: String = "pipelinek-promotion-certification-2"

        /**
         * The shape that came before: the same document without the reason.
         *
         * It is still read, and it is read as ITSELF: a part of it may not
         * carry the reason, because the reason is not part of that shape.
         * The promotion of today hands this build documents of this shape,
         * and a build that stopped reading them would refuse the promotion
         * of every candidate certified before the reason arrived.
         */
        const val LEGACY_SCHEMA_VERSION: String = "pipelinek-promotion-certification-1"
    }
}

/**
 * One part of the coverage a promotion demands.
 *
 * The three parts are the three things the cycle asks for before a stable
 * release: the canaries, the mandatory scenario battery, and the
 * re-validation of a fix. The third one is the only one that may not apply,
 * and it says so itself instead of being left out — a part that is absent is
 * a part nobody can tell apart from a part that was forgotten.
 */
@Serializable
enum class CertificationPart {

    /** The canary battery: the fast scenarios that catch the obvious breakage. */
    CANARIES,

    /** The mandatory scenario battery: the full end-to-end suite. */
    SCENARIOS,

    /** The re-validation of a fix, when the candidate fixes a reported defect. */
    REVALIDATION,
}

/**
 * How a part of the coverage ended.
 *
 * The vocabulary is closed because the status IS the conclusion of that part:
 * a part whose status is not understood cannot be read as a coverage, so
 * reading it fails instead of guessing.
 */
@Serializable
enum class CertificationPartStatus {

    /** The part was run and everything in it passed. */
    CERTIFIED,

    /** The part was run and something in it did not pass. */
    FAILED,

    /** The part does not apply to this candidate: there was nothing to re-validate. */
    NOT_APPLICABLE,
}

/**
 * What one part of the coverage certifies.
 *
 * A part that was certified names the battery it ran and carries the
 * scenarios it executed, so the two can be compared. A part that does not
 * apply names no battery and carries no scenario, because "nothing to
 * re-validate" is not a battery of zero scenarios: it is the absence of a
 * battery.
 *
 * A part that does not apply says WHY, and the reason travels in this
 * document. The reason is one fact with one meaning: it says why a part
 * does not apply, so a part that applied has none, and a part that does
 * not apply has one. A field with two meanings says nothing.
 *
 * The reason is written only when it is there, so a part of the shape that
 * came before keeps the bytes it had.
 */
@Serializable
data class CertifiedPart(
    /** Which part of the coverage this entry is. */
    val part: CertificationPart,
    /** The battery that was certified, or null when this part does not apply. */
    val battery: CertifiedBattery? = null,
    /** How this part ended. */
    val status: CertificationPartStatus,
    /** The scenarios that were executed for this part. */
    val runs: List<ScenarioExecution> = emptyList(),
    /** Why this part does not apply, when it does not apply. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reason: String? = null,
)
