package dev.rubentxu.pipeline.v2.release

/**
 * Whether a receipt admits the candidate the request asked about.
 *
 * The admission is a closed result rather than a boolean plus a message: a
 * publisher either has a receipt that admits its candidate, or it has a list
 * of named violations, and there is no third state in which it "probably" does.
 *
 * What the admission does NOT say is that the certifier certified well. It
 * says that this receipt is about these bytes, that it names the battery that
 * was asked for, that it covers that battery whole, and that it reports every
 * scenario as passed. Whether the certifier ran the battery honestly is a
 * question about the certifier, and it is answered where the trust anchor
 * lives — not here, where nothing is anchored yet.
 */
sealed interface CertificationAdmissionVerdict {

    /** The receipt admits the requested candidate. */
    data class Admissible(val receipt: CertificationReceipt) : CertificationAdmissionVerdict

    /**
     * The receipt does not admit the requested candidate and MUST NOT be
     * published with it.
     *
     * [violations] names every problem, not just the first, so one reading
     * reveals all of them: a publisher that fixes one problem and reads again
     * only to find the next one has been led by a receipt that told it half
     * the truth.
     *
     * [receipt] is the document that was refused, so the diagnostic can name
     * WHICH receipt was rejected and not merely that one was.
     */
    data class Rejected(
        val receipt: CertificationReceipt,
        val violations: List<CertificationAdmissionViolation>,
    ) : CertificationAdmissionVerdict
}

/** One specific reason a receipt was refused. */
data class CertificationAdmissionViolation(
    val code: CertificationAdmissionViolationCode,
    val detail: String,
)

/**
 * The reasons a receipt can be refused, one per fact.
 *
 * Each code names a DIFFERENT fact about the receipt, and each one has a
 * different fix. Two codes are not folded together here: a receipt about
 * another candidate and a receipt about another battery are both "not this
 * one", but only one of them is another candidate's receipt, and a publisher
 * that reads the wrong one goes looking in the wrong place.
 */
enum class CertificationAdmissionViolationCode {

    /** The receipt names a candidate other than the one the request asked about. */
    CANDIDATE_IS_ANOTHER,

    /** The receipt names a battery other than the one the request asked about. */
    BATTERY_IS_ANOTHER,

    /** The battery the receipt declares is not the size of the battery that was asked for. */
    BATTERY_COUNT_DISAGREES,

    /** The receipt declares a battery of one size and carries a different number of scenarios. */
    DECLARED_COUNT_DISAGREES_WITH_EXECUTED,

    /** The verdict the receipt reports is not a certification. */
    DECISION_NOT_CERTIFIED,

    /** A scenario of the battery did not end in a pass. */
    SCENARIO_NOT_PASSED,
}

/**
 * Decide whether [receipt] admits the candidate [request] asked about.
 *
 * Pure: it takes the two documents as values and returns a decision. It reads
 * no file, asks no clock and starts no process, so every rule below is pinned
 * by a case that runs in the unit lane.
 *
 * The rules, and what each one refuses:
 *
 * 1. The receipt must be about the candidate that was requested. A receipt
 *    about other bytes is the receipt of another candidate, whatever it calls
 *    itself.
 * 2. The receipt must name the battery that was requested, by name AND by
 *    version: a battery under a new version is a different battery.
 * 3. The battery the receipt declares must be the size of the battery that
 *    was requested. When it is smaller, the rejection names how many
 *    scenarios are missing, because "the battery is incomplete" is not
 *    something an operator can act on.
 * 4. The receipt must carry as many scenarios as it declares. A receipt that
 *    declares thirteen and carries three contradicts itself, and the coverage
 *    it claims is not the coverage it carries.
 * 5. The verdict must be a certification.
 * 6. Every scenario must have ended in a pass. A verdict this build does not
 *    know is not a pass.
 */
fun evaluateCertificationReceipt(
    request: CertificationRequest,
    receipt: CertificationReceipt,
): CertificationAdmissionVerdict {
    val violations = mutableListOf<CertificationAdmissionViolation>()

    if (receipt.candidateId != request.candidateId) {
        violations += CertificationAdmissionViolation(
            CertificationAdmissionViolationCode.CANDIDATE_IS_ANOTHER,
            "the receipt names candidate ${receipt.candidateId.canonical} and the request asked " +
                "for ${request.candidateId.canonical}. This is the receipt of another candidate, " +
                "and it admits nothing about these bytes.",
        )
    }

    if (receipt.battery.id != request.battery.id || receipt.battery.version != request.battery.version) {
        violations += CertificationAdmissionViolation(
            CertificationAdmissionViolationCode.BATTERY_IS_ANOTHER,
            "the receipt names battery '${receipt.battery.id}' '${receipt.battery.version}' and " +
                "the request asked for '${request.battery.id}' '${request.battery.version}'. A " +
                "battery under another name or another version is another battery.",
        )
    }

    if (receipt.battery.scenarioCount != request.battery.scenarioCount) {
        val declared = receipt.battery.scenarioCount
        val asked = request.battery.scenarioCount
        val gap = if (asked > declared) {
            " ${asked - declared} of them are missing: $declared were certified and $asked " +
                "were asked for."
        } else {
            " The receipt certifies $declared scenarios and only $asked were asked for."
        }
        violations += CertificationAdmissionViolation(
            CertificationAdmissionViolationCode.BATTERY_COUNT_DISAGREES,
            "the receipt declares a battery of $declared scenario(s) and the request asked for " +
                "$asked.$gap",
        )
    }

    if (receipt.battery.scenarioCount != receipt.runs.size) {
        violations += CertificationAdmissionViolation(
            CertificationAdmissionViolationCode.DECLARED_COUNT_DISAGREES_WITH_EXECUTED,
            "the receipt declares a battery of ${receipt.battery.scenarioCount} scenario(s) and " +
                "carries ${receipt.runs.size}. The coverage it claims is not the coverage it " +
                "carries.",
        )
    }

    if (receipt.decision != CertificationDecision.CERTIFIED) {
        violations += CertificationAdmissionViolation(
            CertificationAdmissionViolationCode.DECISION_NOT_CERTIFIED,
            "the receipt reports ${receipt.decision} and only ${CertificationDecision.CERTIFIED} " +
                "admits a candidate.",
        )
    }

    receipt.runs.filter { it.verdict != SCENARIO_VERDICT_PASSED }.forEach { run ->
        violations += CertificationAdmissionViolation(
            CertificationAdmissionViolationCode.SCENARIO_NOT_PASSED,
            "scenario '${run.scenario}' ended in '${run.verdict}' and only " +
                "'$SCENARIO_VERDICT_PASSED' admits a candidate.",
        )
    }

    return if (violations.isEmpty()) {
        CertificationAdmissionVerdict.Admissible(receipt)
    } else {
        CertificationAdmissionVerdict.Rejected(receipt, violations)
    }
}

/**
 * Operator-facing diagnostic for an admission verdict.
 *
 * The text names every violation and says what to do with them, because the
 * reader of this message is deciding whether to publish a release candidate
 * and needs the reason to be actionable, not merely present.
 */
fun CertificationAdmissionVerdict.render(): String = when (this) {
    is CertificationAdmissionVerdict.Admissible ->
        "certification receipt ADMISSIBLE: candidate ${receipt.candidateId.canonical} against " +
            "battery '${receipt.battery.id}' '${receipt.battery.version}' " +
            "(${receipt.runs.size}/${receipt.battery.scenarioCount} scenarios, " +
            "${receipt.decision})"

    is CertificationAdmissionVerdict.Rejected -> buildString {
        appendLine(
            "certification receipt REJECTED (receipt '${receipt.resultId}'): " +
                "${violations.size} violation(s)",
        )
        violations.forEach { appendLine("  - ${it.code}: ${it.detail}") }
        append(
            "A rejected receipt MUST NOT publish the candidate. Certify this candidate against " +
                "the requested battery and hand the receipt back; do not edit the receipt by " +
                "hand.",
        )
    }
}
