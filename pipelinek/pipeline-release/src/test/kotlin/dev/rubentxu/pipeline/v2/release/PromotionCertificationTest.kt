package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shape of the promotion certification: what it is, how it is written and
 * what a document that is not one says.
 */
class PromotionCertificationTest {

    private val digest = "a".repeat(64)
    private val candidate = CandidateId.fromDigest(digest)

    /**
     * The candidate of the pinned documents: the one the certification
     * harness certifies.
     */
    private val pinnedDigest = "b3f1c0d4e5a697886524130fdeadbeef00112233445566778899aabbccddeeff"

    /**
     * The bytes of the shape that came before, pinned.
     *
     * They are the bytes the certification harness writes for the same
     * certification, byte for byte: the two repositories encode the same
     * wire format, so a document one of them wrote is a document the other
     * one reads without a translation.
     *
     * The pin exists because the shape that says WHY a part did not apply
     * arrived NEXT to this one, and a shape that arrives next to another
     * shape can change it without anybody noticing: the promotion of today
     * reads exactly these bytes.
     */
    private val legacyBytes = """
        {
          "schema_version": "pipelinek-promotion-certification-1",
          "candidate_id": "sha256:$pinnedDigest",
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
              "runs": []
            }
          ]
        }
    """.trimIndent() + "\n"

    /**
     * The bytes of the shape that says WHY a part did not apply: the same
     * document, with the reason on the part that did not apply.
     */
    private val currentBytes = """
        {
          "schema_version": "pipelinek-promotion-certification-2",
          "candidate_id": "sha256:$pinnedDigest",
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
              "runs": [],
              "reason": "the candidate fixes no reported defect, so there is nothing to re-validate"
            }
          ]
        }
    """.trimIndent() + "\n"

    /** The certification of the pinned documents, in the shape that came before. */
    private fun legacyCertification(): PromotionCertification = PromotionCertification(
        schemaVersion = "pipelinek-promotion-certification-1",
        candidateId = CandidateId.fromDigest(pinnedDigest),
        decision = CertificationDecision.CERTIFIED,
        parts = listOf(
            CertifiedPart(
                part = CertificationPart.CANARIES,
                battery = CertifiedBattery(id = "canaries", version = "1", scenarioCount = 2),
                status = CertificationPartStatus.CERTIFIED,
                runs = listOf(
                    ScenarioExecution(scenario = "canary-smoke", verdict = "EXECUTED_PASS"),
                    ScenarioExecution(scenario = "canary-sandbox", verdict = "EXECUTED_PASS"),
                ),
            ),
            CertifiedPart(
                part = CertificationPart.SCENARIOS,
                battery = CertifiedBattery(id = "uat", version = "2026.11", scenarioCount = 1),
                status = CertificationPartStatus.CERTIFIED,
                runs = listOf(
                    ScenarioExecution(scenario = "har-001-smoke", verdict = "EXECUTED_PASS"),
                ),
            ),
            CertifiedPart(
                part = CertificationPart.REVALIDATION,
                battery = null,
                status = CertificationPartStatus.NOT_APPLICABLE,
                runs = emptyList(),
            ),
        ),
    )

    private fun certification(
        schemaVersion: String = PromotionCertification.SCHEMA_VERSION,
        candidateId: CandidateId = candidate,
        parts: List<CertifiedPart> = listOf(
            CertifiedPart(
                part = CertificationPart.CANARIES,
                battery = CertifiedBattery(id = "canaries", version = "1", scenarioCount = 2),
                status = CertificationPartStatus.CERTIFIED,
                runs = listOf(
                    ScenarioExecution(scenario = "canary-0", verdict = SCENARIO_VERDICT_PASSED),
                    ScenarioExecution(scenario = "canary-1", verdict = SCENARIO_VERDICT_PASSED),
                ),
            ),
            CertifiedPart(
                part = CertificationPart.REVALIDATION,
                battery = null,
                status = CertificationPartStatus.NOT_APPLICABLE,
                runs = emptyList(),
                reason = "the candidate fixes no reported defect",
            ),
        ),
    ): PromotionCertification = PromotionCertification(
        schemaVersion = schemaVersion,
        candidateId = candidateId,
        decision = CertificationDecision.CERTIFIED,
        parts = parts,
    )

    @Test
    fun `a certification says what it covers`() {
        val document = PromotionCertificationCodec.encode(certification())

        val decoded = assertInstanceOf(
            PromotionCertificationDecode.Accepted::class.java,
            PromotionCertificationCodec.decode(document),
        )
        assertEquals(candidate, decoded.certification.candidateId)
        assertEquals(CertificationDecision.CERTIFIED, decoded.certification.decision)
        assertEquals(2, decoded.certification.parts.size)
        assertEquals(CertificationPart.CANARIES, decoded.certification.parts.first().part)
    }

    @Test
    fun `the same certification always yields the same bytes`() {
        val document = PromotionCertificationCodec.encode(certification())

        assertEquals(document, PromotionCertificationCodec.encode(certification()))
    }

    @Test
    fun `a document that carries a field this schema does not define is refused by name`() {
        val tampered = PromotionCertificationCodec.encode(certification())
            .replaceFirst("{", "{\n  \"unexpected\": true,")

        val rejected = assertInstanceOf(
            PromotionCertificationDecode.Rejected::class.java,
            PromotionCertificationCodec.decode(tampered),
        )
        val refusal = assertInstanceOf(
            CertificationRejection.UnknownField::class.java,
            rejected.rejection,
        )
        assertEquals(listOf("unexpected"), refusal.fields)
    }

    @Test
    fun `a document that carries an unknown field inside a part is refused`() {
        val tampered = PromotionCertificationCodec.encode(certification())
            .replaceFirst("\"battery\": {", "\"battery\": { \"unexpected\": true,")

        val rejected = assertInstanceOf(
            PromotionCertificationDecode.Rejected::class.java,
            PromotionCertificationCodec.decode(tampered),
        )
        assertTrue(rejected.render().contains("REJECTED"))
    }

    @Test
    fun `a document that declares another schema version is refused`() {
        val tampered = PromotionCertificationCodec.encode(certification())
            .replaceFirst(PromotionCertification.SCHEMA_VERSION, "pipelinek-promotion-certification-9")

        val rejected = assertInstanceOf(
            PromotionCertificationDecode.Rejected::class.java,
            PromotionCertificationCodec.decode(tampered),
        )
        assertTrue(rejected.render().contains("pipelinek-promotion-certification-9"))
    }

    @Test
    fun `a document that names a candidate that is not a digest is refused`() {
        val tampered = PromotionCertificationCodec.encode(certification())
            .replaceFirst("sha256:$digest", "v0.44.0-rc1")

        val rejected = assertInstanceOf(
            PromotionCertificationDecode.Rejected::class.java,
            PromotionCertificationCodec.decode(tampered),
        )
        assertTrue(rejected.render().contains("REJECTED"))
    }

    @Test
    fun `a document that names a part this build does not know is refused`() {
        val tampered = PromotionCertificationCodec.encode(certification())
            .replaceFirst("\"CANARIES\"", "\"SOMETHING_ELSE\"")

        val rejected = assertInstanceOf(
            PromotionCertificationDecode.Rejected::class.java,
            PromotionCertificationCodec.decode(tampered),
        )
        assertTrue(rejected.render().contains("REJECTED"))
    }

    @Test
    fun `a document that is not JSON at all is refused`() {
        val rejected = assertInstanceOf(
            PromotionCertificationDecode.Rejected::class.java,
            PromotionCertificationCodec.decode("this is not a certification"),
        )
        assertTrue(rejected.render().contains("REJECTED"))
    }

    @Test
    fun `a certification that covers nothing cannot be built`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            certification(parts = emptyList())
        }
        assertTrue(failure.message.orEmpty().contains("parts is empty"))
    }

    @Test
    fun `a battery that holds no scenario cannot be certified`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CertifiedBattery(id = "canaries", version = "1", scenarioCount = 0)
        }
        assertTrue(failure.message.orEmpty().contains("scenario_count"))
    }

    @Test
    fun `the shape that came before keeps its bytes`() {
        val document = PromotionCertificationCodec.encode(legacyCertification())

        assertEquals(
            legacyBytes,
            document,
            "the shape that came before must keep its bytes: the promotion of today reads them, " +
                "and the harness writes the same bytes for the same certification",
        )
    }

    @Test
    fun `a certification of the shape that says why is read, and the reason comes with it`() {
        val decoded = assertInstanceOf(
            PromotionCertificationDecode.Accepted::class.java,
            PromotionCertificationCodec.decode(currentBytes),
        )
        val revalidation = decoded.certification.parts.first {
            it.part == CertificationPart.REVALIDATION
        }
        assertEquals(
            "the candidate fixes no reported defect, so there is nothing to re-validate",
            revalidation.reason,
            "the reason a part did not apply has to travel with the part it is about",
        )
    }

    @Test
    fun `a certification of the shape that came before that carries the reason is refused by name`() {
        val tampered = legacyBytes.replaceFirst(
            "\"runs\": []",
            "\"runs\": [],\n      \"reason\": \"there was nothing to re-validate\"",
        )

        val rejected = assertInstanceOf(
            PromotionCertificationDecode.Rejected::class.java,
            PromotionCertificationCodec.decode(tampered),
        )
        val refusal = assertInstanceOf(
            CertificationRejection.UnknownField::class.java,
            rejected.rejection,
        )
        assertEquals(
            listOf("reason"),
            refusal.fields,
            "the shape that came before does not define the reason, and the refusal says which " +
                "field it does not define",
        )
    }
}
