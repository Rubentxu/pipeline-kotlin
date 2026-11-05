package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The producer of the receipt of a cycle, over real files.
 *
 * ## The guarantee
 * The documents of a cycle end up in ONE document that says which artifact
 * they are all about, and that document is NOT written when they disagree
 * about it. Over files, the disagreement is not a value passed to a pure
 * function: it is a certification receipt on disk that names another
 * candidate, and the receipt has to be refused where the files are, before
 * any file says anything false.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The unit lane covers the receipt as a value and the composition as a pure
 * decision. What no unit test of this module can see is whether the pieces
 * FIT over a file: the producer reads four documents of different shapes,
 * classifies the extras by what they say, hands the values to the
 * composition, and writes the receipt. A mismatch between any two of them
 * is invisible to each of them, because each of them is correct on its own.
 *
 * ## What is faked
 * The world outside the process and nothing else. The documents are real files
 * in a directory the test owns, which is where the effect that leaves the
 * process goes. No clock, no subprocess and no network are involved: the
 * producer has none.
 */
class CycleReceiptProducerTest {

    /** The directory the documents are written into. */
    @TempDir
    lateinit var directory: Path

    private val digest = "a".repeat(64)

    /** The candidate the cycle is about. */
    private val candidate = CandidateId.fromDigest(digest)

    /** Another candidate: the one whose documents do not belong here. */
    private val other = CandidateId.fromDigest("b".repeat(64))

    private val instant = Instant.parse("2026-11-05T18:04:00Z")

    private fun handoff(): CandidateHandoff = CandidateHandoff(
        candidateId = candidate,
        releaseTrain = ProductVersion.parseOrThrow("0.44.0"),
        candidateSequence = 1,
        productVersion = ProductVersion.parseOrThrow("0.44.0"),
        sourceCommit = "0".repeat(40),
        artifact = CandidateArtifact(
            name = "pipelinek-0.44.0.zip",
            sha256 = digest,
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

    private fun request(): CertificationRequest {
        val outcome = buildCertificationRequest(
            handoff = handoff(),
            catalogue = CertificationBatteryCatalogue(
                id = "harness-certification-battery",
                version = "1",
                scenarios = listOf("alpha", "beta", "gamma"),
            ),
            requestedAt = instant,
        )
        return assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
    }

    private fun certification(candidateDigest: String = digest): CertificationReceipt = CertificationReceipt(
        resultId = "0123456789abcdef",
        candidateId = CandidateId.fromDigest(candidateDigest),
        decision = CertificationDecision.CERTIFIED,
        battery = CertifiedBattery(id = "harness-certification-battery", version = "1", scenarioCount = 3),
        runs = listOf(
            ScenarioExecution("alpha", SCENARIO_VERDICT_PASSED),
            ScenarioExecution("beta", SCENARIO_VERDICT_PASSED),
            ScenarioExecution("gamma", SCENARIO_VERDICT_PASSED),
        ),
    )

    private fun promotion(candidateDigest: String = digest): PromotionCertification = PromotionCertification(
        candidateId = CandidateId.fromDigest(candidateDigest),
        decision = CertificationDecision.CERTIFIED,
        parts = listOf(
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

    /**
     * The report of the defect, as the receiver that observed it wrote it.
     *
     * This product never writes defect reports — it receives them — so the
     * document is written by hand here, the way the other side writes it.
     */
    private fun defectReport(against: String = candidate.canonical): String = """
        {
          "schema_version": "pipelinek-defect-reported-1",
          "name": "defect.reported",
          "protocol_version": "1",
          "candidate_id": "$against",
          "defect": {
            "contract": "contract.alpha",
            "scenario": "scenario.beta",
            "failure_type": "REPRODUCIBLE_DEFECT",
            "signature": "sig-123"
          },
          "evidence": {
            "product_version": "0.44.0",
            "product_commit": "${"0".repeat(40)}",
            "harness_revision": "abc123",
            "commands": ["pipelinek run scenario.beta"],
            "expected": "the scenario passes",
            "observed": "the scenario failed",
            "logs": ["line one"],
            "severity": "HIGH",
            "blocks_release": true
          },
          "observed_at": "2026-11-05T18:04:00Z"
        }
    """.trimIndent()

    private fun write(name: String, text: String): Path =
        directory.resolve(name).also { Files.writeString(it, text) }

    private fun produce(
        out: Path,
        certificationDigest: String = digest,
        extras: List<Path> = emptyList(),
        requestText: String? = null,
    ): CycleReceiptOutcome = CycleReceiptProducer.produce(
        candidatePath = write("candidate-handoff.json", CandidateHandoffCodec.encode(handoff())),
        requestPath = write(
            "request.json",
            requestText ?: CertificationRequestCodec.encode(request()),
        ),
        certificationPath = write(
            "certification.json",
            CertificationReceiptCodec.encode(certification(certificationDigest)),
        ),
        outPath = out,
        extraPaths = extras,
    )

    @Test
    fun `the receipt binds the documents of the cycle and is written where the caller asked`() {
        val out = directory.resolve("cycle-receipt.json")

        val outcome = produce(out)

        assertInstanceOf(CycleReceiptOutcome.Accepted::class.java, outcome)
        assertTrue(Files.exists(out), "the receipt must be written where the caller asked for it")
        val decoded = assertInstanceOf(
            CycleReceiptDecodeResult.Accepted::class.java,
            CycleReceiptCodec.decode(Files.readString(out)),
        )
        assertEquals(candidate, decoded.receipt.candidateId)
        assertTrue(decoded.receipt.admission.admitted, "a receipt that matches the request admits")
        assertNull(decoded.receipt.promotion, "no promotion was handed over, so none is stated")
    }

    @Test
    fun `a certification about another candidate leaves no receipt behind`() {
        val out = directory.resolve("cycle-receipt.json")

        val outcome = produce(out, certificationDigest = other.digest)

        val refused = assertInstanceOf(CycleReceiptOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains(other.digest), "the reason must name the candidate that arrived")
        assertFalse(Files.exists(out), "a refused composition must leave no receipt behind")
    }

    @Test
    fun `a defect report and a promotion certification are classified by what they say`() {
        val out = directory.resolve("cycle-receipt.json")
        val report = write("defect.reported.json", defectReport())
        val promotionCertification = write(
            "promotion.json",
            PromotionCertificationCodec.encode(promotion()),
        )

        val outcome = produce(out, extras = listOf(report, promotionCertification))

        assertInstanceOf(CycleReceiptOutcome.Accepted::class.java, outcome)
        val decoded = assertInstanceOf(
            CycleReceiptDecodeResult.Accepted::class.java,
            CycleReceiptCodec.decode(Files.readString(out)),
        )
        assertEquals(1, decoded.receipt.defects.size)
        assertEquals("scenario.beta", decoded.receipt.defects.first().defect.scenario)
        assertEquals(candidate, decoded.receipt.promotion?.candidateId)
    }

    @Test
    fun `a document that is neither a defect nor a promotion is refused, and the reason says both`() {
        val out = directory.resolve("cycle-receipt.json")
        val neither = write("neither.json", "{ \"note\": \"I am neither\" }")

        val outcome = produce(out, extras = listOf(neither))

        val refused = assertInstanceOf(CycleReceiptOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("neither a defect report nor a promotion"))
        assertFalse(Files.exists(out))
    }

    @Test
    fun `a request that cannot be read produces no receipt and says why`() {
        val out = directory.resolve("cycle-receipt.json")

        val outcome = produce(out, requestText = "{ not a request")

        val refused = assertInstanceOf(CycleReceiptOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("certification request"))
        assertFalse(Files.exists(out))
    }

    @Test
    fun `a defect observed against another candidate leaves no receipt behind`() {
        val out = directory.resolve("cycle-receipt.json")
        val report = write("defect.reported.json", defectReport(against = other.canonical))

        val outcome = produce(out, extras = listOf(report))

        val refused = assertInstanceOf(CycleReceiptOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("defect report"), "the reason must name the document that disagrees")
        assertFalse(Files.exists(out))
    }
}
