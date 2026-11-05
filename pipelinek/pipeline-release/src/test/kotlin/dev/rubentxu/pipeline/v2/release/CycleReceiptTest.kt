package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the receipt of a cycle has to bind, and what it refuses to bind.
 *
 * The guarantee under test is the one the card asks for: the documents of a
 * cycle end up in ONE document that says which artifact they are all about,
 * and that document is NOT written when they disagree about it. A receipt
 * that binds a request about one candidate to a certification of another
 * would say something false about both, and it would say it in the one
 * place somebody goes looking for the truth about an artifact.
 *
 * The second half of the guarantee is what the receipt does NOT refuse: a
 * certification that failed is an outcome of the cycle, and it is written
 * down with its violation named. A receipt that only existed for the happy
 * path would leave every failure in a console log.
 *
 * Pure: no filesystem, no clock, no subprocess. The documents are values the
 * test builds.
 */
class CycleReceiptTest {

    private val version = ProductVersion.parseOrThrow("0.44.0")

    /** 64 hex, so the candidate identity is a digest and not a name. */
    private val digest = "a".repeat(64)

    /** The digest of a DIFFERENT candidate: another artifact's documents. */
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
        candidateDigest: String = digest,
        scenarios: List<String> = listOf("alpha", "beta", "gamma"),
        correction: CandidateCorrection? = null,
    ): CertificationRequest {
        val outcome = buildCertificationRequest(
            handoff = handoff(candidateDigest),
            catalogue = CertificationBatteryCatalogue(
                id = "harness-certification-battery",
                version = "1",
                scenarios = scenarios,
            ),
            requestedAt = instant,
            correction = correction,
        )
        return assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
    }

    private fun receipt(
        candidateDigest: String = digest,
        decision: CertificationDecision = CertificationDecision.CERTIFIED,
        runs: List<ScenarioExecution> = listOf(
            ScenarioExecution("alpha", SCENARIO_VERDICT_PASSED),
            ScenarioExecution("beta", SCENARIO_VERDICT_PASSED),
            ScenarioExecution("gamma", SCENARIO_VERDICT_PASSED),
        ),
    ): CertificationReceipt = CertificationReceipt(
        resultId = "0123456789abcdef",
        candidateId = CandidateId.fromDigest(candidateDigest),
        decision = decision,
        battery = CertifiedBattery(
            id = "harness-certification-battery",
            version = "1",
            scenarioCount = 3,
        ),
        runs = runs,
    )

    private fun promotion(candidateDigest: String = digest): PromotionCertification = PromotionCertification(
        candidateId = CandidateId.fromDigest(candidateDigest),
        decision = CertificationDecision.CERTIFIED,
        parts = listOf(
            CertifiedPart(
                part = CertificationPart.CANARIES,
                battery = CertifiedBattery(id = "canaries", version = "1", scenarioCount = 1),
                status = CertificationPartStatus.CERTIFIED,
                runs = listOf(ScenarioExecution("canary-0", SCENARIO_VERDICT_PASSED)),
            ),
            CertifiedPart(
                part = CertificationPart.SCENARIOS,
                battery = CertifiedBattery(id = "harness-certification-battery", version = "1", scenarioCount = 3),
                status = CertificationPartStatus.CERTIFIED,
                runs = listOf(
                    ScenarioExecution("alpha", SCENARIO_VERDICT_PASSED),
                    ScenarioExecution("beta", SCENARIO_VERDICT_PASSED),
                    ScenarioExecution("gamma", SCENARIO_VERDICT_PASSED),
                ),
            ),
        ),
    )

    /** The footprint of a defect, as the receiver that observed it stated it. */
    private fun fingerprint(scenario: String = "scenario.beta") = DefectFingerprint(
        contract = "contract.alpha",
        scenario = scenario,
        failureType = DefectFailureType.REPRODUCIBLE_DEFECT,
        signature = "sig-123",
    )

    private fun defect(
        candidateDigest: String = digest,
        scenario: String = "scenario.beta",
    ) = CandidateDefect(
        candidateId = CandidateId.fromDigest(candidateDigest),
        defect = fingerprint(scenario),
    )

    private fun accepted(outcome: CycleReceiptOutcome): CycleReceipt =
        assertInstanceOf(CycleReceiptOutcome.Accepted::class.java, outcome).receipt

    private fun refused(outcome: CycleReceiptOutcome): String =
        assertInstanceOf(CycleReceiptOutcome.Refused::class.java, outcome).reason

    @Test
    fun `the receipt binds the candidate, the request, the certification, the admission, the defects and the promotion`() {
        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(),
            certification = receipt(),
            defects = listOf(defect()),
            promotion = promotion(),
        )

        val receipt = accepted(outcome)
        assertEquals(CandidateId.fromDigest(digest), receipt.candidateId)
        assertEquals(CandidateId.fromDigest(digest), receipt.candidate.candidateId)
        assertEquals(CandidateId.fromDigest(digest), receipt.request.candidateId)
        assertEquals(CandidateId.fromDigest(digest), receipt.certification.candidateId)
        assertEquals(CandidateId.fromDigest(digest), receipt.promotion?.candidateId)
        assertTrue(receipt.admission.admitted, "the admission must admit a receipt that matches the request")
        assertEquals(emptyList<CertificationAdmissionViolation>(), receipt.admission.violations)
        assertEquals(1, receipt.defects.size)
        assertEquals("scenario.beta", receipt.defects.first().defect.scenario)
    }

    @Test
    fun `a certification about another candidate is not written, and the reason names both`() {
        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(),
            certification = receipt(candidateDigest = otherDigest),
            defects = emptyList(),
            promotion = null,
        )

        val reason = refused(outcome)
        assertTrue(
            reason.contains("the certification receipt"),
            "the reason must name the document that disagrees, got: $reason",
        )
        assertTrue(reason.contains(otherDigest), "the reason must name the candidate that arrived, got: $reason")
        assertTrue(reason.contains(digest), "the reason must name the candidate that was asked about, got: $reason")
    }

    @Test
    fun `a request about another candidate is not written`() {
        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(candidateDigest = otherDigest),
            certification = receipt(),
            defects = emptyList(),
            promotion = null,
        )

        val reason = refused(outcome)
        assertTrue(reason.contains("the request"), "the reason must name the document that disagrees, got: $reason")
        assertTrue(reason.contains(otherDigest), "the reason must name the candidate that arrived, got: $reason")
    }

    @Test
    fun `a defect observed against another candidate is not a defect of this one`() {
        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(),
            certification = receipt(),
            defects = listOf(defect(candidateDigest = otherDigest)),
            promotion = null,
        )

        val reason = refused(outcome)
        assertTrue(
            reason.contains("the defect report"),
            "the reason must name the document that disagrees, got: $reason",
        )
        assertTrue(reason.contains(otherDigest), "the reason must name the candidate it was observed against, got: $reason")
    }

    @Test
    fun `a promotion about another candidate is not written`() {
        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(),
            certification = receipt(),
            defects = emptyList(),
            promotion = promotion(candidateDigest = otherDigest),
        )

        val reason = refused(outcome)
        assertTrue(
            reason.contains("the promotion certification"),
            "the reason must name the document that disagrees, got: $reason",
        )
    }

    @Test
    fun `a certification that failed is written down, with the violation named`() {
        val failing = receipt(
            decision = CertificationDecision.CERTIFICATION_FAILED,
            runs = listOf(
                ScenarioExecution("alpha", SCENARIO_VERDICT_PASSED),
                ScenarioExecution("beta", "EXECUTED_FAIL"),
                ScenarioExecution("gamma", SCENARIO_VERDICT_PASSED),
            ),
        )

        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(),
            certification = failing,
            defects = emptyList(),
            promotion = null,
        )

        val receipt = accepted(outcome)
        assertFalse(receipt.admission.admitted, "a certification that failed does not admit the candidate")
        assertEquals(
            listOf(
                CertificationAdmissionViolationCode.DECISION_NOT_CERTIFIED,
                CertificationAdmissionViolationCode.SCENARIO_NOT_PASSED,
            ),
            receipt.admission.violations.map { it.code },
            "the receipt must name EVERY violation, not only the first: a reader that fixes one " +
                "and reads again must not find the next one waiting",
        )
        assertEquals(
            CertificationDecision.CERTIFICATION_FAILED,
            receipt.certification.decision,
            "the receipt must keep the verdict the certifier reached",
        )
    }

    @Test
    fun `the same defect reported twice is one defect`() {
        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(),
            certification = receipt(),
            defects = listOf(defect(), defect()),
            promotion = null,
        )

        val reason = refused(outcome)
        assertTrue(
            reason.contains("scenario.beta"),
            "the reason must name the defect that was counted twice, got: $reason",
        )
    }

    @Test
    fun `a candidate that answers a defect carries the trace, and the defect it answers is not its own`() {
        // The correction names the defect and the candidate the defect was seen
        // against. That candidate is another one, on purpose: a correction
        // answers a defect of the PREVIOUS candidate, so the defect is not a
        // defect of this one and does not break the coherence of the receipt.
        val correction = CandidateCorrection(
            defect = fingerprint(),
            observedAgainst = CandidateId.fromDigest(otherDigest),
        )

        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(correction = correction),
            certification = receipt(),
            defects = emptyList(),
            promotion = null,
        )

        val receipt = accepted(outcome)
        assertEquals(
            "scenario.beta",
            receipt.request.correction?.defect?.scenario,
            "the receipt must carry the defect the candidate answers",
        )
        assertEquals(
            CandidateId.fromDigest(otherDigest),
            receipt.request.correction?.observedAgainst,
            "the receipt must carry the candidate the defect was seen against",
        )
        assertEquals(emptyList<CandidateDefect>(), receipt.defects)
    }

    @Test
    fun `a receipt that carries a field this schema does not define is refused by name`() {
        val tampered = CycleReceiptCodec.encode(composed())
            .replaceFirst("{", "{\n  \"unexpected\": \"surprise\",")

        val result = CycleReceiptCodec.decode(tampered)

        val rejected = assertInstanceOf(CycleReceiptDecodeResult.Rejected::class.java, result)
        val unknown = assertInstanceOf(CertificationRejection.UnknownField::class.java, rejected.rejection)
        assertEquals(listOf("unexpected"), unknown.fields)
    }

    @Test
    fun `a receipt that declares another schema version is refused`() {
        val tampered = CycleReceiptCodec.encode(composed())
            .replace(CycleReceipt.SCHEMA_VERSION, "pipelinek-cycle-receipt-2")

        val result = CycleReceiptCodec.decode(tampered)

        val rejected = assertInstanceOf(CycleReceiptDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a receipt that carries a nested document of another schema is refused`() {
        val tampered = CycleReceiptCodec.encode(composed())
            .replace(CertificationRequest.SCHEMA_VERSION, "pipelinek-certification-request-3")

        val result = CycleReceiptCodec.decode(tampered)

        val rejected = assertInstanceOf(CycleReceiptDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a well formed receipt decodes to the value that was encoded`() {
        val receipt = composed()

        val result = CycleReceiptCodec.decode(CycleReceiptCodec.encode(receipt))

        val accepted = assertInstanceOf(CycleReceiptDecodeResult.Accepted::class.java, result)
        assertEquals(receipt, accepted.receipt)
    }

    @Test
    fun `the same receipt always encodes to the same bytes`() {
        val receipt = composed()

        assertEquals(
            CycleReceiptCodec.encode(receipt),
            CycleReceiptCodec.encode(receipt),
        )
    }

    @Test
    fun `a receipt without a promotion says so`() {
        val outcome = composeCycleReceipt(
            candidate = handoff(),
            request = request(),
            certification = receipt(),
            defects = emptyList(),
            promotion = null,
        )

        assertNull(accepted(outcome).promotion)
    }

    private fun composed(): CycleReceipt = accepted(
        composeCycleReceipt(
            candidate = handoff(),
            request = request(),
            certification = receipt(),
            defects = listOf(defect()),
            promotion = promotion(),
        ),
    )
}
