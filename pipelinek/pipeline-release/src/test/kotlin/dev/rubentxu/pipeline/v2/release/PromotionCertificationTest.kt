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
            CertificationRequestRejection.UnknownField::class.java,
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
}
