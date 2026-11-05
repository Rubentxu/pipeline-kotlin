package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What a receipt has to say for a candidate to be published, and what the
 * product does with a receipt that says something else.
 *
 * The guarantee under test is the one the publication gate rests on: a receipt
 * admits a candidate only when it is about THESE bytes, it names the battery
 * that was asked for, it covers that battery whole, and it reports that every
 * scenario passed. Every refusal names which of those facts is missing, because
 * a refusal an operator cannot act on is a refusal nobody fixes.
 *
 * Pure: no filesystem, no clock, no subprocess. The request and the receipt are
 * values the test builds.
 */
class CertificationReceiptTest {

    private val version = ProductVersion.parseOrThrow("0.44.0")

    /** 64 hex, so the candidate identity is a digest and not a name. */
    private val digest = "a".repeat(64)

    /** The digest of a DIFFERENT candidate: the receipt of another. */
    private val otherDigest = "b".repeat(64)

    private val instant = Instant.parse("2026-11-05T18:04:00Z")

    private fun handoff(candidateDigest: String = digest): CandidateHandoff = CandidateHandoff(
        candidateId = CandidateId.fromDigest(candidateDigest),
        releaseTrain = version,
        candidateSequence = 1,
        productVersion = version,
        sourceCommit = "0".repeat(40),
        artifact = CandidateArtifact(
            name = "pipelinek-0.44.0.zip",
            sha256 = candidateDigest,
            size = 4096,
            archiveRoot = "pipelinek-0.44.0",
            implementationVersion = "0.44.0",
        ),
        distributionManifest = CandidateFileRef(
            name = "distribution-manifest.json",
            sha256 = digest,
            builtAt = "2026-11-05T18:04:00Z",
        ),
        sbom = null,
        sdk = null,
        bom = null,
    )

    private fun request(
        scenarios: List<String> = listOf("alpha", "beta", "gamma"),
    ): CertificationRequest {
        val outcome = buildCertificationRequest(
            handoff = handoff(),
            catalogue = CertificationBatteryCatalogue(
                id = "harness-certification-battery",
                version = "1",
                scenarios = scenarios,
            ),
            requestedAt = instant,
        )
        return assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
    }

    private fun receipt(
        candidateDigest: String = digest,
        batteryId: String = "harness-certification-battery",
        batteryVersion: String = "1",
        declaredCount: Int = 3,
        decision: CertificationDecision = CertificationDecision.CERTIFIED,
        runs: List<ScenarioExecution> = listOf(
            ScenarioExecution("alpha", "EXECUTED_PASS"),
            ScenarioExecution("beta", "EXECUTED_PASS"),
            ScenarioExecution("gamma", "EXECUTED_PASS"),
        ),
    ): CertificationReceipt = CertificationReceipt(
        resultId = "0123456789abcdef",
        candidateId = CandidateId.fromDigest(candidateDigest),
        decision = decision,
        battery = CertifiedBattery(
            id = batteryId,
            version = batteryVersion,
            scenarioCount = declaredCount,
        ),
        runs = runs,
    )

    private fun violations(verdict: CertificationAdmissionVerdict): List<CertificationAdmissionViolation> {
        val rejected = assertInstanceOf(CertificationAdmissionVerdict.Rejected::class.java, verdict)
        return rejected.violations
    }

    private fun codes(verdict: CertificationAdmissionVerdict): List<CertificationAdmissionViolationCode> =
        violations(verdict).map { it.code }

    @Test
    fun `a receipt that matches the request is admissible`() {
        val verdict = evaluateCertificationReceipt(request(), receipt())

        val admissible = assertInstanceOf(CertificationAdmissionVerdict.Admissible::class.java, verdict)
        assertEquals(CandidateId.fromDigest(digest), admissible.receipt.candidateId)
    }

    @Test
    fun `a receipt that names another candidate is rejected`() {
        val verdict = evaluateCertificationReceipt(request(), receipt(candidateDigest = otherDigest))

        val found = violations(verdict)
        assertEquals(listOf(CertificationAdmissionViolationCode.CANDIDATE_IS_ANOTHER), found.map { it.code })
        assertTrue(found.first().detail.contains(digest), "the rejection must name the candidate that was asked for")
        assertTrue(found.first().detail.contains(otherDigest), "the rejection must name the candidate that arrived")
    }

    @Test
    fun `a receipt that names another battery is rejected`() {
        val verdict = evaluateCertificationReceipt(
            request(),
            receipt(batteryId = "another-battery", batteryVersion = "2"),
        )

        val found = violations(verdict)
        assertEquals(listOf(CertificationAdmissionViolationCode.BATTERY_IS_ANOTHER), found.map { it.code })
        assertTrue(found.first().detail.contains("harness-certification-battery"))
        assertTrue(found.first().detail.contains("another-battery"))
    }

    @Test
    fun `a receipt that declares an incomplete battery is rejected and names the missing count`() {
        // Thirteen scenarios were asked for; the receipt declares a battery of
        // five. The rejection has to say how many are missing, because "your
        // battery is incomplete" is not something an operator can act on.
        val requested = request((1..13).map { "scenario-$it" })
        val short = receipt(
            declaredCount = 5,
            runs = (1..5).map { ScenarioExecution("scenario-$it", "EXECUTED_PASS") },
        )

        val verdict = evaluateCertificationReceipt(requested, short)

        val found = violations(verdict)
        assertEquals(listOf(CertificationAdmissionViolationCode.BATTERY_COUNT_DISAGREES), found.map { it.code })
        assertTrue(
            found.first().detail.contains("8"),
            "the rejection must name the missing count, got: ${found.first().detail}",
        )
    }

    @Test
    fun `a receipt that declares more scenarios than it executed is rejected`() {
        // The receipt declares thirteen and carries three runs: it contradicts
        // itself, and the coverage it claims is not the coverage it carries.
        val requested = request((1..13).map { "scenario-$it" })
        val overclaimed = receipt(
            declaredCount = 13,
            runs = listOf(
                ScenarioExecution("alpha", "EXECUTED_PASS"),
                ScenarioExecution("beta", "EXECUTED_PASS"),
                ScenarioExecution("gamma", "EXECUTED_PASS"),
            ),
        )

        val verdict = evaluateCertificationReceipt(requested, overclaimed)

        assertEquals(
            listOf(CertificationAdmissionViolationCode.DECLARED_COUNT_DISAGREES_WITH_EXECUTED),
            codes(verdict),
        )
    }

    @Test
    fun `a scenario that did not pass is rejected and is named`() {
        val failing = receipt(
            runs = listOf(
                ScenarioExecution("alpha", "EXECUTED_PASS"),
                ScenarioExecution("beta", "EXECUTED_FAIL"),
                ScenarioExecution("gamma", "EXECUTED_PASS"),
            ),
        )

        val verdict = evaluateCertificationReceipt(request(), failing)

        val found = violations(verdict)
        assertEquals(listOf(CertificationAdmissionViolationCode.SCENARIO_NOT_PASSED), found.map { it.code })
        assertTrue(found.first().detail.contains("beta"), "the rejection must name the scenario that failed")
        assertTrue(found.first().detail.contains("EXECUTED_FAIL"), "the rejection must name the verdict it reported")
    }

    @Test
    fun `a verdict this build does not know does not pass`() {
        val unknown = receipt(
            runs = listOf(
                ScenarioExecution("alpha", "EXECUTED_PASS"),
                ScenarioExecution("beta", "SOMETHING_ELSE"),
                ScenarioExecution("gamma", "EXECUTED_PASS"),
            ),
        )

        val verdict = evaluateCertificationReceipt(request(), unknown)

        assertEquals(listOf(CertificationAdmissionViolationCode.SCENARIO_NOT_PASSED), codes(verdict))
    }

    @Test
    fun `a decision that is not certified is rejected`() {
        val failed = receipt(decision = CertificationDecision.CERTIFICATION_FAILED)

        val verdict = evaluateCertificationReceipt(request(), failed)

        assertEquals(listOf(CertificationAdmissionViolationCode.DECISION_NOT_CERTIFIED), codes(verdict))
    }

    @Test
    fun `a receipt that carries a field this schema does not define is refused`() {
        val tampered = CertificationReceiptCodec.encode(receipt())
            .replaceFirst("{", "{\n  \"unexpected\": \"surprise\",")

        val result = CertificationReceiptCodec.decode(tampered)

        val rejected = assertInstanceOf(CertificationReceiptDecodeResult.Rejected::class.java, result)
        val unknown = assertInstanceOf(
            CertificationReceiptRejection.UnknownField::class.java,
            rejected.rejection,
        )
        assertEquals(listOf("unexpected"), unknown.fields)
    }

    @Test
    fun `a receipt that leaves out a field the schema requires is refused by name`() {
        val withoutBattery = """
            {
              "schema_version": "${CertificationReceipt.SCHEMA_VERSION}",
              "result_id": "0123456789abcdef",
              "candidate_id": "sha256:$digest",
              "decision": "CERTIFIED",
              "runs": []
            }
        """.trimIndent()

        val result = CertificationReceiptCodec.decode(withoutBattery)

        val rejected = assertInstanceOf(CertificationReceiptDecodeResult.Rejected::class.java, result)
        val missing = assertInstanceOf(
            CertificationReceiptRejection.MissingField::class.java,
            rejected.rejection,
        )
        assertEquals(listOf("battery"), missing.fields)
    }

    @Test
    fun `a candidate id that is a name instead of a digest is refused`() {
        val tampered = CertificationReceiptCodec.encode(receipt())
            .replace("sha256:$digest", "release-candidate-7")

        val result = CertificationReceiptCodec.decode(tampered)

        val rejected = assertInstanceOf(CertificationReceiptDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationReceiptRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a nested unknown field is refused`() {
        val polluted = CertificationReceiptCodec.encode(receipt())
            .replaceFirst("\"battery\": {", "\"battery\": { \"unexpected\": true,")

        val result = CertificationReceiptCodec.decode(polluted)

        val rejected = assertInstanceOf(
            CertificationReceiptDecodeResult.Rejected::class.java,
            result,
            "a NESTED unknown field was accepted: the reader ignored a field it did not " +
                "understand, which is exactly what a closed shape forbids",
        )
        assertInstanceOf(CertificationReceiptRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a document that declares another schema version is refused`() {
        val tampered = CertificationReceiptCodec.encode(receipt())
            .replace(CertificationReceipt.SCHEMA_VERSION, "pipelinek-certification-receipt-2")

        val result = CertificationReceiptCodec.decode(tampered)

        val rejected = assertInstanceOf(CertificationReceiptDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationReceiptRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a receipt without an identity is refused`() {
        val withoutIdentity = CertificationReceiptCodec.encode(receipt())
            .replace("\"result_id\": \"0123456789abcdef\"", "\"result_id\": \"  \"")

        val result = CertificationReceiptCodec.decode(withoutIdentity)

        val rejected = assertInstanceOf(CertificationReceiptDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationReceiptRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a receipt that names no scenario is refused`() {
        val withoutScenario = CertificationReceiptCodec.encode(receipt())
            .replace("\"scenario\": \"alpha\"", "\"scenario\": \"\"")

        val result = CertificationReceiptCodec.decode(withoutScenario)

        val rejected = assertInstanceOf(CertificationReceiptDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationReceiptRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a well formed receipt decodes to the value that was encoded`() {
        val receipt = receipt()

        val result = CertificationReceiptCodec.decode(CertificationReceiptCodec.encode(receipt))

        val accepted = assertInstanceOf(CertificationReceiptDecodeResult.Accepted::class.java, result)
        assertEquals(receipt, accepted.receipt)
    }

    @Test
    fun `the same receipt always encodes to the same bytes`() {
        val receipt = receipt()

        assertEquals(
            CertificationReceiptCodec.encode(receipt),
            CertificationReceiptCodec.encode(receipt),
        )
    }

    @Test
    fun `a rejection names the violation and what it says`() {
        val verdict = evaluateCertificationReceipt(request(), receipt(candidateDigest = otherDigest))

        val rendered = verdict.render()
        assertTrue(
            rendered.contains("REJECTED"),
            "the operator-facing text must say the receipt was rejected, got: $rendered",
        )
        assertTrue(
            rendered.contains("CANDIDATE_IS_ANOTHER"),
            "the operator-facing text must name the violation, got: $rendered",
        )
        assertTrue(
            rendered.contains("0123456789abcdef"),
            "the operator-facing text must name WHICH receipt was refused, got: $rendered",
        )
    }
}
