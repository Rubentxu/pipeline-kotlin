package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The producer of a certification request, over real files.
 *
 * ## The guarantee
 * A request that answers a defect says WHICH defect and the candidate the
 * defect was seen against, and the two facts come from the report somebody
 * wrote, not from what the operator typed. A report that cannot be read
 * produces NO request and says why: answering "this candidate is not a
 * correction" when the operator asked for a correction is a lie that reaches
 * the receiver as a fact.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The unit lane covers the request as a pure value: what it says and what a
 * correction in it means. What no unit test of this module can see is whether
 * the pieces FIT over a file — the producer reads the handoff, reads the
 * catalogue, reads the defect report, hands the correction to the composition,
 * and writes the request. A mismatch between any two of them is invisible to
 * each of them, because each of them is correct on its own. And the case that
 * matters most here — the report that cannot be read — is exactly the case
 * the unit lane cannot write, because it has no filesystem.
 *
 * ## What is faked
 * The world outside the process and nothing else. The documents are real files
 * in a directory the test owns, which is where the effect that leaves the
 * process goes. No clock, no subprocess and no network are involved: the
 * producer has none.
 */
class CertificationRequestProducerTest {

    /** The directory the documents are written into. */
    @TempDir
    lateinit var directory: Path

    private val digest = "a".repeat(64)

    /** The candidate that is being certified: the one that answers the defect. */
    private val candidate = CandidateId.fromDigest(digest)

    /** The candidate the defect was observed against: the previous one. */
    private val observedAgainst = CandidateId.fromDigest("b".repeat(64))

    private val instant = Instant.parse("2026-11-05T18:04:00Z")

    private fun handoff(): CandidateHandoff = CandidateHandoff(
        candidateId = candidate,
        releaseTrain = ProductVersion.parseOrThrow("0.44.0"),
        candidateSequence = 2,
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

    private fun catalogue(): String = """
        {
          "schema_version": "pipelinek-certification-battery-1",
          "id": "harness-certification-battery",
          "version": "1",
          "scenarios": ["alpha", "beta", "gamma"]
        }
    """.trimIndent()

    /**
     * The report of the defect, as the receiver that observed it wrote it.
     */
    private fun defectReport(against: String = observedAgainst.canonical): String = """
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

    private fun produce(out: Path, report: Path?): CertificationRequestOutcome =
        CertificationRequestProducer.produce(
            handoffPath = write("candidate-handoff.json", CandidateHandoffCodec.encode(handoff())),
            cataloguePath = write("battery.json", catalogue()),
            requestedAt = instant,
            outPath = out,
            correctionReportPath = report,
        )

    @Test
    fun `the request states the defect the candidate answers and the candidate it was seen against`() {
        val out = directory.resolve("request.json")
        val report = write("defect.reported.json", defectReport())

        val outcome = produce(out, report)

        assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome)
        val request = CertificationRequestCodec.decode(Files.readString(out))
        val accepted = assertInstanceOf(CertificationRequestDecodeResult.Accepted::class.java, request)
        val correction = accepted.request.correction
        assertEquals("scenario.beta", correction?.defect?.scenario)
        assertEquals(observedAgainst.canonical, correction?.observedAgainst?.canonical)
    }

    @Test
    fun `a candidate that is not a correction states a request that says nothing about a defect`() {
        val out = directory.resolve("request.json")

        val outcome = produce(out, report = null)

        assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome)
        val accepted = assertInstanceOf(
            CertificationRequestDecodeResult.Accepted::class.java,
            CertificationRequestCodec.decode(Files.readString(out)),
        )
        assertNull(accepted.request.correction)
    }

    @Test
    fun `a report that cannot be read produces no request and says why`() {
        val out = directory.resolve("request.json")
        val report = write("defect.reported.json", "{ not a report")

        val outcome = produce(out, report)

        val refused = assertInstanceOf(CertificationRequestOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("defect report"))
        assertTrue(!Files.exists(out))
    }

    @Test
    fun `a report that names the candidate being certified produces no request`() {
        val out = directory.resolve("request.json")
        val report = write("defect.reported.json", defectReport(against = candidate.canonical))

        val outcome = produce(out, report)

        val refused = assertInstanceOf(CertificationRequestOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("corrects itself"))
    }
}
