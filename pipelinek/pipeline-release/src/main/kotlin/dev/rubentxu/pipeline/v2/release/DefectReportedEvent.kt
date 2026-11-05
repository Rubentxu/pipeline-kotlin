package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The event that tells a receiver that a defect was observed against a
 * candidate.
 *
 * The event is what crosses the boundary between repositories, so its shape is
 * a contract rather than a report. It says four things: WHICH candidate was
 * measured, WHAT defect was observed (its structural footprint), WHAT was seen
 * (the evidence), and WHEN it was seen.
 *
 * The footprint travels as the four facts that identify the defect and NOT as
 * a hash of them: the hash is what both sides COMPUTE from those facts, and a
 * hash that travelled would be a second spelling of the same fact that the two
 * sides could disagree about. The canonicalisation is the one the harness
 * already applies to its own incidents (`harness/issues.py`), so both sides
 * name the same defect with the same string.
 *
 * The candidate identity IS the SHA-256 of the distribution, so the evidence
 * does NOT carry it again: two measures of one file that disagree are two
 * measures, and the event would be one of them.
 *
 * The event carries NO signature: the product reads CONTENT, and the
 * authenticity of a certification belongs to the promotion to GA, where there
 * is a key to check it against.
 */
@Serializable
data class DefectReportedEvent internal constructor(
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The name the receiver listens for. */
    val name: String = NAME,
    /** The version of the cross-repository event protocol this event speaks. */
    @SerialName("protocol_version") val protocolVersion: String,
    /** The candidate the defect was observed against. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The structural footprint of the defect. */
    val defect: DefectFingerprint,
    /** What was seen, and how it can be reproduced. */
    val evidence: DefectEvidence,
    /** The instant the defect was observed. */
    @SerialName("observed_at") val observedAt: WireInstant,
) {
    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-defect-reported-1"

        /** The name of the event, as the receiver knows it. */
        const val NAME: String = "defect.reported"

        /** The version of the cross-repository event protocol this build speaks. */
        const val PROTOCOL_VERSION: String = "1"
    }
}

/**
 * The structural footprint of a defect: the four facts that say WHICH defect
 * this is, whatever candidate it was observed against.
 *
 * The footprint carries NO candidate and NO date, and that is the whole point:
 * a defect observed against two candidates is ONE defect, and an identity that
 * included the candidate would turn it into two.
 *
 * The shape of the three free fields and the set of failure types are the ones
 * the harness already applies to its own incidents (`harness/issues.py`).
 * They are repeated here on purpose. The identity of a defect is computed over
 * the TEXT of these fields, so a value that arrived with a stray space would
 * be computed under one name here and under another name there, and the two
 * sides would stop recognising the same defect.
 */
@Serializable
data class DefectFingerprint internal constructor(
    /** The contract the failing scenario exercises. */
    val contract: String,
    /** The scenario that produced the failure. */
    val scenario: String,
    /** What kind of failure it was. */
    @SerialName("failure_type") val failureType: DefectFailureType,
    /** The signature of the failure. */
    val signature: String,
) {
    init {
        require(contract.matches(TEXT)) {
            "the contract of a defect must be one of the harness' text fields, got '$contract'."
        }
        require(scenario.matches(TEXT)) {
            "the scenario of a defect must be one of the harness' text fields, got '$scenario'."
        }
        require(signature.matches(TEXT)) {
            "the signature of a defect must be one of the harness' text fields, got '$signature'."
        }
    }

    companion object {
        /** The shape of the three free fields: the one the harness applies. */
        private val TEXT = Regex("^[A-Za-z0-9._\\-/]{1,128}$")
    }
}

/**
 * What kind of failure was observed.
 *
 * The four kinds are the ones the harness classifies with, and the vocabulary
 * is closed because the receiver copies it into the task it opens: a task that
 * names a kind of failure nobody can act on is not actionable.
 */
enum class DefectFailureType {

    /** The scenario failed the way it was supposed to fail. */
    EXPECTED,

    /** The failure came from the infrastructure, not from the product. */
    INFRASTRUCTURE,

    /** The failure is a defect of the product, and it reproduces. */
    REPRODUCIBLE_DEFECT,

    /** The failure is not classified. */
    UNEXPECTED,
}

/**
 * What was seen, and how it can be reproduced.
 *
 * The evidence is what separates "the harness failed for a reason" from "the
 * product failed for a reason", so it travels with the report instead of
 * being reconstructed by whoever reads the task later.
 *
 * The candidate is NOT here: the event carries it once, as the candidate
 * identity, and the digest of the distribution and the candidate identity are
 * the same measurement.
 */
@Serializable
data class DefectEvidence internal constructor(
    /** The product version the candidate materialises. */
    @SerialName("product_version") val productVersion: ProductVersion,
    /** The commit of the product that was measured. */
    @SerialName("product_commit") val productCommit: String,
    /** The revision of the harness that measured it. */
    @SerialName("harness_revision") val harnessRevision: String,
    /** The commands that reproduce the failure. */
    val commands: List<String>,
    /** What was expected to happen. */
    val expected: String,
    /** What happened. */
    val observed: String,
    /** The logs of the run, with the secrets taken out. */
    val logs: List<String>,
    /** How grave the defect is. */
    val severity: DefectSeverity,
    /** Whether the defect keeps the candidate from being promoted. */
    @SerialName("blocks_release") val blocksRelease: Boolean,
) {
    init {
        require(productCommit.isNotBlank()) {
            "a defect report names the commit of the product it was observed against."
        }
        require(harnessRevision.isNotBlank()) {
            "a defect report names the revision of the harness that observed it."
        }
        require(observed.isNotBlank()) {
            "a defect report says what was observed: a defect nobody saw is not a defect."
        }
    }
}

/**
 * How grave a defect is, in the vocabulary the emitter judged it with.
 *
 * The receiver does not re-judge the severity: the harness is the one that
 * ran the scenarios and knows what it saw, so the severity travels and is
 * copied into the task. What the receiver DOES decide is the priority of the
 * task it opens, and that comes from [DefectEvidence.blocksRelease]: a defect
 * that keeps the candidate from being promoted is worked before one that does
 * not.
 */
enum class DefectSeverity {
    LOW,
    MEDIUM,
    HIGH,
}
