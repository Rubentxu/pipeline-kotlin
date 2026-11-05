package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether a certification admits a candidate to a stable release, and what
 * the refusal names when it does not.
 *
 * Pure: no filesystem, no clock, no subprocess.
 */
class PromotionCertificationValidationTest {

    private val digest = "a".repeat(64)
    private val candidate = CandidateId.fromDigest(digest)

    private fun certified(
        part: CertificationPart,
        scenarios: Int = 2,
        batteryId: String = "${part.name.lowercase()}-battery",
        verdicts: List<String> = List(scenarios) { SCENARIO_VERDICT_PASSED },
    ): CertifiedPart = CertifiedPart(
        part = part,
        battery = CertifiedBattery(id = batteryId, version = "1", scenarioCount = scenarios),
        status = CertificationPartStatus.CERTIFIED,
        runs = verdicts.mapIndexed { index, verdict ->
            ScenarioExecution(scenario = "${part.name.lowercase()}-$index", verdict = verdict)
        },
    )

    private fun notApplicable(part: CertificationPart): CertifiedPart = CertifiedPart(
        part = part,
        battery = null,
        status = CertificationPartStatus.NOT_APPLICABLE,
        runs = emptyList(),
    )

    private fun certification(
        candidateId: CandidateId = candidate,
        decision: CertificationDecision = CertificationDecision.CERTIFIED,
        parts: List<CertifiedPart> = listOf(
            certified(CertificationPart.CANARIES),
            certified(CertificationPart.SCENARIOS),
            notApplicable(CertificationPart.REVALIDATION),
        ),
    ): PromotionCertification = PromotionCertification(
        candidateId = candidateId,
        decision = decision,
        parts = parts,
    )

    private fun violationsOf(certification: PromotionCertification): List<PromotionCertificationViolation> {
        val verdict = assertInstanceOf(
            PromotionCertificationVerdict.Rejected::class.java,
            evaluatePromotionCertification(certification, candidate),
        )
        return verdict.violations
    }

    @Test
    fun `a certification that covers every part admits the candidate`() {
        val verdict = assertInstanceOf(
            PromotionCertificationVerdict.Admissible::class.java,
            evaluatePromotionCertification(certification(), candidate),
        )
        assertEquals(candidate, verdict.certification.candidateId)
    }

    @Test
    fun `a revalidation that was certified also admits the candidate`() {
        val parts = listOf(
            certified(CertificationPart.CANARIES),
            certified(CertificationPart.SCENARIOS),
            certified(CertificationPart.REVALIDATION),
        )

        val verdict = assertInstanceOf(
            PromotionCertificationVerdict.Admissible::class.java,
            evaluatePromotionCertification(certification(parts = parts), candidate),
        )
        assertEquals(3, verdict.certification.parts.size)
    }

    @Test
    fun `a certification of another candidate is refused`() {
        val other = CandidateId.fromDigest("b".repeat(64))

        val violations = violationsOf(certification(candidateId = other))
        assertTrue(violations.any { it.code == PromotionCertificationViolationCode.CANDIDATE_IS_ANOTHER })
    }

    @Test
    fun `a certification that covers only the canaries is refused and names the part`() {
        val parts = listOf(
            certified(CertificationPart.CANARIES),
            notApplicable(CertificationPart.REVALIDATION),
        )

        val violations = violationsOf(certification(parts = parts))
        val missing = violations.filter { it.code == PromotionCertificationViolationCode.PART_MISSING }
        assertEquals(1, missing.size)
        assertTrue(missing.first().detail.contains("scenarios"))
    }

    @Test
    fun `a part stated twice is refused`() {
        val parts = listOf(
            certified(CertificationPart.CANARIES),
            certified(CertificationPart.CANARIES),
            certified(CertificationPart.SCENARIOS),
            notApplicable(CertificationPart.REVALIDATION),
        )

        val violations = violationsOf(certification(parts = parts))
        assertTrue(
            violations.any { it.code == PromotionCertificationViolationCode.PART_DUPLICATED },
        )
    }

    @Test
    fun `a part that did not pass is refused`() {
        val parts = listOf(
            certified(CertificationPart.CANARIES),
            CertifiedPart(
                part = CertificationPart.SCENARIOS,
                battery = CertifiedBattery(id = "scenarios-battery", version = "1", scenarioCount = 1),
                status = CertificationPartStatus.FAILED,
                runs = listOf(ScenarioExecution(scenario = "scenarios-0", verdict = "EXECUTED_FAIL")),
            ),
            notApplicable(CertificationPart.REVALIDATION),
        )

        val violations = violationsOf(certification(parts = parts))
        assertTrue(
            violations.any { it.code == PromotionCertificationViolationCode.PART_NOT_CERTIFIED },
        )
    }

    @Test
    fun `a part that was certified and names no battery is refused`() {
        val parts = listOf(
            CertifiedPart(
                part = CertificationPart.CANARIES,
                battery = null,
                status = CertificationPartStatus.CERTIFIED,
                runs = listOf(ScenarioExecution(scenario = "canaries-0", verdict = SCENARIO_VERDICT_PASSED)),
            ),
            certified(CertificationPart.SCENARIOS),
            notApplicable(CertificationPart.REVALIDATION),
        )

        val violations = violationsOf(certification(parts = parts))
        val disagreeing = violations.filter {
            it.code == PromotionCertificationViolationCode.PART_COVERAGE_DISAGREES
        }
        assertEquals(1, disagreeing.size)
        assertTrue(disagreeing.first().detail.contains("names no battery"))
    }

    @Test
    fun `a part that declares one coverage and carries another is refused`() {
        val parts = listOf(
            CertifiedPart(
                part = CertificationPart.CANARIES,
                battery = CertifiedBattery(id = "canaries-battery", version = "1", scenarioCount = 13),
                status = CertificationPartStatus.CERTIFIED,
                runs = listOf(ScenarioExecution(scenario = "canaries-0", verdict = SCENARIO_VERDICT_PASSED)),
            ),
            certified(CertificationPart.SCENARIOS),
            notApplicable(CertificationPart.REVALIDATION),
        )

        val violations = violationsOf(certification(parts = parts))
        assertTrue(
            violations.any { it.code == PromotionCertificationViolationCode.PART_COVERAGE_DISAGREES },
        )
    }

    @Test
    fun `a scenario that did not pass is refused`() {
        val parts = listOf(
            certified(
                CertificationPart.CANARIES,
                verdicts = listOf(SCENARIO_VERDICT_PASSED, "EXECUTED_FAIL"),
            ),
            certified(CertificationPart.SCENARIOS),
            notApplicable(CertificationPart.REVALIDATION),
        )

        val violations = violationsOf(certification(parts = parts))
        val failed = violations.filter { it.code == PromotionCertificationViolationCode.SCENARIO_NOT_PASSED }
        assertEquals(1, failed.size)
        assertTrue(failed.first().detail.contains("EXECUTED_FAIL"))
    }

    @Test
    fun `a verdict nobody has heard of is not a pass`() {
        val parts = listOf(
            certified(CertificationPart.CANARIES, verdicts = listOf("SOMETHING_NEW")),
            certified(CertificationPart.SCENARIOS),
            notApplicable(CertificationPart.REVALIDATION),
        )

        val violations = violationsOf(certification(parts = parts))
        assertTrue(
            violations.any { it.code == PromotionCertificationViolationCode.SCENARIO_NOT_PASSED },
        )
    }

    @Test
    fun `a decision that is not a certification is refused`() {
        val violations = violationsOf(certification(decision = CertificationDecision.NOT_RUN))
        assertTrue(
            violations.any { it.code == PromotionCertificationViolationCode.DECISION_NOT_CERTIFIED },
        )
    }

    @Test
    fun `the canaries are not optional`() {
        val parts = listOf(
            notApplicable(CertificationPart.CANARIES),
            certified(CertificationPart.SCENARIOS),
            notApplicable(CertificationPart.REVALIDATION),
        )

        val violations = violationsOf(certification(parts = parts))
        assertTrue(
            violations.any { it.code == PromotionCertificationViolationCode.PART_NOT_CERTIFIED },
        )
    }

    @Test
    fun `a revalidation that does not apply may not carry coverage`() {
        val parts = listOf(
            certified(CertificationPart.CANARIES),
            certified(CertificationPart.SCENARIOS),
            CertifiedPart(
                part = CertificationPart.REVALIDATION,
                battery = CertifiedBattery(id = "fix-battery", version = "1", scenarioCount = 1),
                status = CertificationPartStatus.NOT_APPLICABLE,
                runs = listOf(ScenarioExecution(scenario = "fix-0", verdict = SCENARIO_VERDICT_PASSED)),
            ),
        )

        val violations = violationsOf(certification(parts = parts))
        assertTrue(
            violations.any { it.code == PromotionCertificationViolationCode.PART_COVERAGE_DISAGREES },
        )
    }

    @Test
    fun `every violation is named, not only the first`() {
        val other = CandidateId.fromDigest("b".repeat(64))
        val parts = listOf(certified(CertificationPart.CANARIES))

        val violations = violationsOf(
            certification(candidateId = other, decision = CertificationDecision.NOT_RUN, parts = parts),
        )
        val codes = violations.map { it.code }.toSet()
        assertTrue(PromotionCertificationViolationCode.CANDIDATE_IS_ANOTHER in codes)
        assertTrue(PromotionCertificationViolationCode.DECISION_NOT_CERTIFIED in codes)
        assertTrue(PromotionCertificationViolationCode.PART_MISSING in codes)
    }
}
