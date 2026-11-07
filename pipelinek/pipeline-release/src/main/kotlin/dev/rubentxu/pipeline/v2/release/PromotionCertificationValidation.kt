package dev.rubentxu.pipeline.v2.release

/**
 * Whether a certification admits a candidate to a stable release.
 *
 * The verdict is a closed result rather than a boolean plus a message: a
 * publisher either has a certification that admits its candidate, or it has
 * a list of named violations, and there is no third state in which it
 * "probably" does.
 *
 * What the verdict does NOT say is that the certifier certified well. It says
 * that this certification is about these bytes, that it covers every part the
 * promotion demands, and that everything it covers passed. Whether the
 * certifier ran the coverage honestly is a question about the certifier, and
 * it is answered where the trust anchor lives — not here, where nothing is
 * anchored yet.
 */
sealed interface PromotionCertificationVerdict {

    /** The certification admits the candidate to a stable release. */
    data class Admissible(val certification: PromotionCertification) : PromotionCertificationVerdict

    /**
     * The certification does not admit the candidate and MUST NOT promote it.
     *
     * [violations] names every problem, not just the first, so one reading
     * reveals all of them: a publisher that fixes one problem and reads again
     * only to find the next one has been led by a certification that told it
     * half the truth.
     *
     * [certification] is the document that was refused, so the diagnostic can
     * name WHICH certification was rejected and not merely that one was.
     */
    data class Rejected(
        val certification: PromotionCertification,
        val violations: List<PromotionCertificationViolation>,
    ) : PromotionCertificationVerdict
}

/**
 * One specific reason a certification was refused.
 */
data class PromotionCertificationViolation(
    val code: PromotionCertificationViolationCode,
    val detail: String,
)

/**
 * The reasons a certification can be refused, one per fact.
 *
 * Each code names a DIFFERENT fact about the certification, and each one has
 * a different fix. A certification about another candidate and a
 * certification missing a part are both "not this one", but only one of them
 * is about other bytes, and a publisher that reads the wrong one goes looking
 * in the wrong place.
 */
enum class PromotionCertificationViolationCode {

    /** The certification names a candidate other than the one being promoted. */
    CANDIDATE_IS_ANOTHER,

    /** The verdict the certification reports is not a certification. */
    DECISION_NOT_CERTIFIED,

    /** A part of the coverage is not stated at all. */
    PART_MISSING,

    /** A part of the coverage is stated more than once. */
    PART_DUPLICATED,

    /** A part of the coverage did not end certified. */
    PART_NOT_CERTIFIED,

    /** A part of the coverage contradicts itself. */
    PART_COVERAGE_DISAGREES,

    /** A scenario of a part did not end in a pass. */
    SCENARIO_NOT_PASSED,

    /**
     * A part that does not apply does not say why.
     *
     * The shape that says the reason asks for it: a part that was skipped
     * without a word about it is a part nobody can audit.
     */
    PART_REASON_MISSING,

    /**
     * A part that applied carries the reason of one that did not.
     *
     * The reason says why a part does not apply, so a part that ran has no
     * reason to give.
     */
    PART_REASON_DISAGREES,
}

/**
 * The parts a promotion demands, in the order they are reported when one of
 * them is missing.
 */
private val REQUIRED_PARTS: List<CertificationPart> = listOf(
    CertificationPart.CANARIES,
    CertificationPart.SCENARIOS,
    CertificationPart.REVALIDATION,
)

/**
 * Decide whether [certification] admits [candidate] to a stable release.
 *
 * Pure: it takes the document and the candidate as values and returns a
 * decision. It reads no file, asks no clock and starts no process, so every
 * rule below is pinned by a case that runs in the unit lane.
 *
 * The rules, and what each one refuses:
 *
 * 1. The certification must be about the candidate being promoted. A
 *    certification of other bytes is the certification of another candidate,
 *    whatever it calls itself.
 * 2. The verdict must be a certification. A certification that reports
 *    anything else admits nothing, whatever coverage it carries.
 * 3. Every part the promotion demands must be stated. A partial coverage —
 *    the canaries and nothing else — is refused, and the refusal names the
 *    part that is missing.
 * 4. A part stated twice is refused: two statements of the same part leave
 *    the reader with no way to tell which one is the coverage.
 * 5. A part must end certified. The re-validation is the only part that may
 *    say it does not apply, because a candidate that fixes no defect has
 *    nothing to re-validate; the other two are not optional.
 * 6. A part that was certified must name the battery it certified, and the
 *    battery it declares must be the size of the coverage it carries. A part
 *    that does not apply must carry no coverage at all.
 * 7. Every scenario must have ended in a pass. A verdict this build does not
 *    know is not a pass.
 * 8. A part that does not apply says why, and only in the shape that says
 *    why. The reason says why a part does not apply, so a part that applied
 *    has none, and a part that did not apply has one. A certification of the
 *    shape that came before is judged exactly as it was: it has no reason to
 *    give and it is not asked for one.
 */
fun evaluatePromotionCertification(
    certification: PromotionCertification,
    candidate: CandidateId,
): PromotionCertificationVerdict {
    val violations = mutableListOf<PromotionCertificationViolation>()

    if (certification.candidateId != candidate) {
        violations += PromotionCertificationViolation(
            PromotionCertificationViolationCode.CANDIDATE_IS_ANOTHER,
            "the certification names candidate ${certification.candidateId.canonical} and the " +
                "release being promoted is about ${candidate.canonical}. A certification of " +
                "other bytes says nothing about these bytes.",
        )
    }

    if (certification.decision != CertificationDecision.CERTIFIED) {
        violations += PromotionCertificationViolation(
            PromotionCertificationViolationCode.DECISION_NOT_CERTIFIED,
            "the certification reports ${certification.decision} and only " +
                "${CertificationDecision.CERTIFIED} admits a candidate to a stable release.",
        )
    }

    // The shape the document declares decides whether the reason is asked
    // for. A document of the shape that came before has no reason to give,
    // and asking it for one would refuse the certifications that were
    // certified before the reason existed.
    val asksTheReason =
        certification.schemaVersion == PromotionCertification.SCHEMA_VERSION

    val byPart = certification.parts.groupBy { it.part }
    for (part in REQUIRED_PARTS) {
        val stated = byPart[part].orEmpty()
        if (stated.isEmpty()) {
            violations += PromotionCertificationViolation(
                PromotionCertificationViolationCode.PART_MISSING,
                "the certification does not cover ${part.name.lowercase()}. A promotion needs " +
                    "the complete coverage of the candidate, and this part is not stated.",
            )
            continue
        }
        if (stated.size > 1) {
            violations += PromotionCertificationViolation(
                PromotionCertificationViolationCode.PART_DUPLICATED,
                "the certification states ${part.name.lowercase()} ${stated.size} times. Two " +
                    "statements of the same part leave the reader with no way to tell which " +
                    "one is the coverage.",
            )
            continue
        }
        violations += violationsOf(stated.first(), part, asksTheReason)
    }

    return if (violations.isEmpty()) {
        PromotionCertificationVerdict.Admissible(certification)
    } else {
        PromotionCertificationVerdict.Rejected(certification, violations)
    }
}

/**
 * The violations of one stated part, on its own.
 *
 * The part is separated from the rest so that each rule is a fact about ONE
 * part: which part it was, how it ended, what it covered. A reader that
 * fixed one part and read again does not have to work out which part the
 * violation was about.
 */
private fun violationsOf(
    stated: CertifiedPart,
    required: CertificationPart,
    asksTheReason: Boolean,
): List<PromotionCertificationViolation> {
    val violations = mutableListOf<PromotionCertificationViolation>()
    val name = required.name.lowercase()

    // The reason is one fact about the part, and it is judged apart from
    // the status: it says why a part does not apply, so a part that applied
    // has no reason to give and a part that did not apply has one.
    if (asksTheReason) {
        val applies = stated.status != CertificationPartStatus.NOT_APPLICABLE
        val reason = stated.reason
        when {
            applies && !reason.isNullOrBlank() -> violations += PromotionCertificationViolation(
                PromotionCertificationViolationCode.PART_REASON_DISAGREES,
                "the certification says $name ${
                    if (stated.status == CertificationPartStatus.FAILED) "did not pass" else "was certified"
                } and carries a reason. The reason says why a part does not apply, and a " +
                    "part that applied has no reason to give.",
            )

            !applies && reason.isNullOrBlank() -> violations += PromotionCertificationViolation(
                PromotionCertificationViolationCode.PART_REASON_MISSING,
                "the certification says $name does not apply and gives no reason. A part that " +
                    "was skipped without a word about it is a part nobody can audit.",
            )
        }
    }

    when (stated.status) {
        CertificationPartStatus.CERTIFIED -> {
            val battery = stated.battery
            val declared = battery?.scenarioCount
            if (battery == null) {
                violations += PromotionCertificationViolation(
                    PromotionCertificationViolationCode.PART_COVERAGE_DISAGREES,
                    "the certification says $name was certified and names no battery. A part " +
                        "that was certified says which battery it certified.",
                )
            } else if (declared != stated.runs.size) {
                violations += PromotionCertificationViolation(
                    PromotionCertificationViolationCode.PART_COVERAGE_DISAGREES,
                    "the certification declares a $name battery of ${declared ?: 0} scenario(s) " +
                        "and carries ${stated.runs.size}. The coverage it claims is not the " +
                        "coverage it carries.",
                )
            }
            stated.runs.filter { it.verdict != SCENARIO_VERDICT_PASSED }.forEach { run ->
                violations += PromotionCertificationViolation(
                    PromotionCertificationViolationCode.SCENARIO_NOT_PASSED,
                    "the scenario '${run.scenario}' of $name ended in '${run.verdict}' and only " +
                        "'$SCENARIO_VERDICT_PASSED' admits a candidate.",
                )
            }
        }

        CertificationPartStatus.FAILED -> violations += PromotionCertificationViolation(
            PromotionCertificationViolationCode.PART_NOT_CERTIFIED,
            "the certification says $name did not pass. A promotion needs the complete " +
                "coverage of the candidate.",
        )

        CertificationPartStatus.NOT_APPLICABLE -> {
            if (required != CertificationPart.REVALIDATION) {
                violations += PromotionCertificationViolation(
                    PromotionCertificationViolationCode.PART_NOT_CERTIFIED,
                    "the certification says $name does not apply. It is not optional for a " +
                        "promotion: it is part of the coverage the cycle demands.",
                )
            } else if (stated.battery != null || stated.runs.isNotEmpty()) {
                violations += PromotionCertificationViolation(
                    PromotionCertificationViolationCode.PART_COVERAGE_DISAGREES,
                    "the certification says $name does not apply, and yet it names a battery or " +
                        "carries scenarios. A part that does not apply covers nothing.",
                )
            }
        }
    }

    return violations
}

/**
 * Operator-facing diagnostic for a certification verdict.
 *
 * The text names every violation and says what to do with them, because the
 * reader of this message is deciding whether to publish a stable release and
 * needs the reason to be actionable, not merely present.
 */
fun PromotionCertificationVerdict.render(): String = when (this) {
    is PromotionCertificationVerdict.Admissible -> buildString {
        append(
            "promotion certification ADMISSIBLE: candidate ${certification.candidateId.canonical}, " +
                "${certification.parts.size} part(s) covered, all certified",
        )
        // The reason a part did not apply travels with the certification,
        // and this verdict is what the operator reads: a verdict that carries
        // the coverage but not the reason is a verdict about a part nobody
        // can account for.
        certification.parts
            .filter { !it.reason.isNullOrBlank() }
            .forEach {
                appendLine()
                append("  - ${it.part.name.lowercase()}: ${it.reason}")
            }
    }

    is PromotionCertificationVerdict.Rejected -> buildString {
        appendLine(
            "promotion certification REJECTED: ${violations.size} violation(s)",
        )
        violations.forEach { appendLine("  - ${it.code}: ${it.detail}") }
        append(
            "A rejected certification MUST NOT promote the candidate. Certify the missing " +
                "parts of THIS candidate and hand the certification back; do not edit it by " +
                "hand.",
        )
    }
}
