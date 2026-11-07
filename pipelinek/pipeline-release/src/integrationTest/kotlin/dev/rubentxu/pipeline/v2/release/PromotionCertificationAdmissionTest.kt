package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The promotion door, over a real file, with the real codec and the real
 * verdict.
 *
 * ## The guarantee
 * A candidate is promoted to a stable release only against a certification
 * this build can read, that is about THAT candidate, and that covers every
 * part the promotion demands. Everything else refuses, and the refusal names
 * the part that is missing rather than saying that something is missing.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The unit lane covers the codec and the verdict as pure values: what a
 * document says, and what that means. What no unit test of this module can
 * see is whether the three FIT each other over a file — the codec writes
 * bytes, the interpreter reads a path and hands the bytes to the codec, and
 * the verdict judges what the codec returned. A mismatch between any two of
 * them is invisible to every one of them, because each of them is correct
 * on its own. And the case that matters most for a door — a certification
 * that is NOT THERE — is exactly the case the unit lane cannot write, because
 * it has no filesystem.
 *
 * ## What is faked
 * The world outside the process and nothing else. The certification is a real
 * file in a directory the test owns, which is where the effect that leaves
 * the process goes. No clock, no subprocess and no network are involved: the
 * door has none.
 */
class PromotionCertificationAdmissionTest {

    /** The directory the certification is written into. */
    @TempDir
    lateinit var directory: Path

    private val digest = "a".repeat(64)
    private val candidate = CandidateId.fromDigest(digest)

    private fun certified(part: CertificationPart, scenarios: Int = 2): CertifiedPart = CertifiedPart(
        part = part,
        battery = CertifiedBattery(id = "${part.name.lowercase()}-battery", version = "1", scenarioCount = scenarios),
        status = CertificationPartStatus.CERTIFIED,
        runs = List(scenarios) { index ->
            ScenarioExecution(
                scenario = "${part.name.lowercase()}-$index",
                verdict = SCENARIO_VERDICT_PASSED,
            )
        },
    )

    private fun complete(): PromotionCertification = PromotionCertification(
        candidateId = candidate,
        decision = CertificationDecision.CERTIFIED,
        parts = listOf(
            certified(CertificationPart.CANARIES),
            certified(CertificationPart.SCENARIOS),
            CertifiedPart(
                part = CertificationPart.REVALIDATION,
                battery = null,
                status = CertificationPartStatus.NOT_APPLICABLE,
                runs = emptyList(),
                reason = "the candidate fixes no reported defect",
            ),
        ),
    )

    private fun write(document: String): Path {
        val path = directory.resolve("certification.json")
        Files.writeString(path, document)
        return path
    }

    @Test
    fun `a certification that covers every part admits the candidate`() {
        val path = write(PromotionCertificationCodec.encode(complete()))

        val outcome = assertInstanceOf(
            PromotionCertificationOutcome.Admitted::class.java,
            PromotionCertificationAdmission.admit(path, candidate),
        )
        assertTrue(outcome.summary.contains("ADMISSIBLE"))
    }

    @Test
    fun `a certification that is not there refuses the promotion`() {
        val outcome = assertInstanceOf(
            PromotionCertificationOutcome.Refused::class.java,
            PromotionCertificationAdmission.admit(directory.resolve("absent.json"), candidate),
        )
        assertTrue(outcome.reason.contains("does not exist"))
    }

    @Test
    fun `a certification that covers only the canaries refuses and names the part`() {
        val partial = PromotionCertification(
            candidateId = candidate,
            decision = CertificationDecision.CERTIFIED,
            parts = listOf(
                certified(CertificationPart.CANARIES),
                CertifiedPart(
                    part = CertificationPart.REVALIDATION,
                    battery = null,
                    status = CertificationPartStatus.NOT_APPLICABLE,
                    runs = emptyList(),
                ),
            ),
        )
        val path = write(PromotionCertificationCodec.encode(partial))

        val outcome = assertInstanceOf(
            PromotionCertificationOutcome.Refused::class.java,
            PromotionCertificationAdmission.admit(path, candidate),
        )
        assertTrue(outcome.reason.contains("scenarios"))
    }

    @Test
    fun `a certification of another candidate refuses the promotion`() {
        val other = complete().copy(candidateId = CandidateId.fromDigest("b".repeat(64)))
        val path = write(PromotionCertificationCodec.encode(other))

        val outcome = assertInstanceOf(
            PromotionCertificationOutcome.Refused::class.java,
            PromotionCertificationAdmission.admit(path, candidate),
        )
        assertTrue(outcome.reason.contains("CANDIDATE_IS_ANOTHER"))
    }

    @Test
    fun `a document that is not a certification refuses the promotion`() {
        val path = write("{\"not\": \"a certification\"}")

        val outcome = assertInstanceOf(
            PromotionCertificationOutcome.Refused::class.java,
            PromotionCertificationAdmission.admit(path, candidate),
        )
        assertTrue(outcome.reason.contains("not a certification"))
    }

    /**
     * The document the certification harness writes, in the shape that says
     * WHY a part did not apply.
     *
     * It is written as text and not through the product's own encoder: the
     * document crosses the boundary from another repository, and a witness
     * that built it with the product's encoder would only certify that the
     * product agrees with itself.
     */
    private fun currentShapeDocument(reason: String?): String {
        val reasonLine = if (reason == null) "" else ",\n      \"reason\": \"$reason\""
        return """
            {
              "schema_version": "pipelinek-promotion-certification-2",
              "candidate_id": "sha256:$digest",
              "decision": "CERTIFIED",
              "parts": [
                {
                  "part": "CANARIES",
                  "battery": {
                    "id": "canaries",
                    "version": "1",
                    "scenario_count": 2
                  },
                  "status": "CERTIFIED",
                  "runs": [
                    {
                      "scenario": "canary-smoke",
                      "verdict": "EXECUTED_PASS"
                    },
                    {
                      "scenario": "canary-sandbox",
                      "verdict": "EXECUTED_PASS"
                    }
                  ]
                },
                {
                  "part": "SCENARIOS",
                  "battery": {
                    "id": "uat",
                    "version": "2026.11",
                    "scenario_count": 1
                  },
                  "status": "CERTIFIED",
                  "runs": [
                    {
                      "scenario": "har-001-smoke",
                      "verdict": "EXECUTED_PASS"
                    }
                  ]
                },
                {
                  "part": "REVALIDATION",
                  "battery": null,
                  "status": "NOT_APPLICABLE",
                  "runs": []$reasonLine
                }
              ]
            }
        """.trimIndent() + "\n"
    }

    @Test
    fun `a certification that says why a part did not apply admits the candidate`() {
        val path = write(
            currentShapeDocument(
                "the candidate fixes no reported defect, so there is nothing to re-validate",
            ),
        )

        val outcome = assertInstanceOf(
            PromotionCertificationOutcome.Admitted::class.java,
            PromotionCertificationAdmission.admit(path, candidate),
        )
        assertTrue(outcome.summary.contains("ADMISSIBLE"))
        assertTrue(
            outcome.summary.contains("the candidate fixes no reported defect"),
            "the reason has to reach the verdict the operator reads, got: ${outcome.summary}",
        )
    }

    @Test
    fun `a certification that does not say why a part did not apply refuses and names the part`() {
        val path = write(currentShapeDocument(null))

        val outcome = assertInstanceOf(
            PromotionCertificationOutcome.Refused::class.java,
            PromotionCertificationAdmission.admit(path, candidate),
        )
        assertTrue(
            outcome.reason.contains("PART_REASON_MISSING"),
            "the refusal must name the rule that was broken, got: ${outcome.reason}",
        )
        assertTrue(
            outcome.reason.contains("revalidation"),
            "the refusal must name the part that does not say why, got: ${outcome.reason}",
        )
    }
}
