package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The receipt a certifier returns for a candidate, as a value with a closed
 * shape.
 *
 * A receipt is the answer to a question this product asked: "certify these
 * bytes against this battery". It answers about ONE candidate, so it names that
 * candidate by the digest of the bytes it certified — never by a name, a tag
 * or a release train. A receipt that names another candidate is the receipt of
 * something else, and reading it as this candidate's answer is how the wrong
 * bytes get published.
 *
 * The shape is closed in both directions: a document that carries a field this
 * build does not define is refused, and a document that leaves out a field the
 * schema requires is refused by name. Neither is repaired, and neither is
 * coerced into something this build understands.
 *
 * The constructor is internal on purpose. This product reads receipts; it does
 * not state them. The only way a receipt enters the product is through the
 * codec, and the codec is where a document is held to its shape.
 */
@Serializable
data class CertificationReceipt internal constructor(
    /** The shape this document claims to follow. */
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The identity of this receipt, quoted in diagnostics. */
    @SerialName("result_id") val resultId: String,
    /** The candidate this receipt is about: the digest of the certified bytes. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The verdict the certifier reached. */
    val decision: CertificationDecision,
    /** The battery the certifier ran. */
    val battery: CertifiedBattery,
    /** What was executed, one entry per scenario. */
    val runs: List<ScenarioExecution>,
) {
    init {
        require(resultId.isNotBlank()) {
            "a receipt without an identity cannot be quoted in a diagnostic: result_id is blank."
        }
    }

    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-certification-receipt-1"
    }
}

/**
 * The verdict a certifier reached about a candidate.
 *
 * The vocabulary is closed because the verdict IS the document's conclusion: a
 * receipt whose decision this build does not know cannot be read as an
 * approval, so reading it fails instead of guessing.
 */
enum class CertificationDecision {
    /** Every scenario of the battery passed. */
    CERTIFIED,

    /** At least one scenario did not pass. */
    CERTIFICATION_FAILED,

    /** Nothing was executed for this candidate. */
    NOT_RUN,
}

/**
 * The battery a receipt declares it ran, named and sized.
 *
 * The size is a DECLARATION: what the certifier says the battery holds. It is
 * compared against the request that asked for the battery, and against the
 * scenarios the receipt actually carries, and the two comparisons are separate
 * facts — a receipt can name a battery of the right size and carry a different
 * number of runs, and that difference is a defect the reader has to see.
 */
@Serializable
data class CertifiedBattery(
    /** The name the battery is published under. */
    val id: String,
    /** The version of that battery. */
    val version: String,
    /** How many scenarios the battery holds, as this receipt declares it. */
    @SerialName("scenario_count") val scenarioCount: Int,
) {
    init {
        require(id.isNotBlank()) { "a battery without a name is not a battery: id is blank." }
        require(version.isNotBlank()) { "a battery without a version cannot be told apart: version is blank." }
        require(scenarioCount > 0) {
            "a battery that holds no scenario certifies nothing: scenario_count is $scenarioCount."
        }
    }
}

/**
 * One scenario the certifier executed, and how it ended.
 *
 * The verdict is a string and not a closed enumeration on purpose: the
 * certifier may grow a verdict this build has never seen, and the rule that
 * protects the candidate does not depend on knowing the whole vocabulary. Only
 * [SCENARIO_VERDICT_PASSED] admits, so a verdict nobody has heard of is
 * treated as a verdict that did not pass.
 */
@Serializable
data class ScenarioExecution(
    /** The identifier of the scenario that was executed. */
    val scenario: String,
    /** How the scenario ended. */
    val verdict: String,
) {
    init {
        require(scenario.isNotBlank()) { "an execution without a scenario names nothing: scenario is blank." }
        require(verdict.isNotBlank()) { "an execution without a verdict says nothing: verdict is blank." }
    }
}

/**
 * The only scenario verdict that admits a candidate.
 *
 * Every other verdict — a failure, an infrastructure problem, or one this
 * build does not know — leaves the candidate uncertified. A verdict that is
 * not understood cannot be read as a pass.
 */
const val SCENARIO_VERDICT_PASSED: String = "EXECUTED_PASS"
