package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What a receiver can conclude from a certification request, and what a
 * producer can promise about the number of scenarios the request declares.
 *
 * The guarantee under test is narrow and it is the one the cross-repo contract
 * depends on: the request names the candidate by the digest of the bytes that
 * were measured, and the scenario count it declares is the size of the
 * inventory the request was built from. The count is therefore a fact about an
 * inventory, never a constant written in a program — a program that says 26
 * because 26 was the plan promises nothing, and a program that says 13 because
 * the inventory holds 13 says something a receiver can check.
 *
 * Pure: no filesystem, no clock, no subprocess. The instant the request
 * declares is supplied, not read.
 *
 * The second guarantee under test is the CORRECTION: a candidate that is the
 * answer to a defect says which defect and the candidate the defect was seen
 * against, and a candidate that is not a correction says nothing about any
 * defect. That second one matters because the receiver revalidates the
 * scenario the defect was in: a request that carried a correction nobody can
 * place, or none at all when there is one, would leave the receiver with no
 * way to know which scenario to revalidate first.
 */
class CertificationRequestTest {

    private val version = ProductVersion.parseOrThrow("0.44.0")

    /** 64 hex, so the candidate identity is a digest and not a name. */
    private val digest = "a".repeat(64)

    private val instant = Instant.parse("2026-11-05T18:04:00Z")

    private fun handoff(): CandidateHandoff = CandidateHandoff(
        candidateId = CandidateId.fromDigest(digest),
        releaseTrain = version,
        candidateSequence = 1,
        productVersion = version,
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

    private fun catalogue(scenarios: List<String>) = CertificationBatteryCatalogue(
        id = "harness-certification-battery",
        version = "1",
        scenarios = scenarios,
    )

    /** The footprint of a defect, as the receiver that observed it stated it. */
    private fun fingerprint() = DefectFingerprint(
        contract = "contract.alpha",
        scenario = "scenario.beta",
        failureType = DefectFailureType.REPRODUCIBLE_DEFECT,
        signature = "sig-123",
    )

    /**
     * The correction of a defect that was seen against another candidate.
     *
     * The other candidate is a different one on purpose: a correction names
     * the candidate the defect was in, and that is never the candidate the
     * correction produces.
     */
    private fun correction(
        observedAgainst: CandidateId = CandidateId.fromDigest("b".repeat(64)),
    ) = CandidateCorrection(defect = fingerprint(), observedAgainst = observedAgainst)

    private fun request(
        scenarios: List<String> = listOf("alpha", "beta", "gamma"),
        correction: CandidateCorrection? = null,
    ): CertificationRequest {
        val outcome = buildCertificationRequest(
            handoff = handoff(),
            catalogue = catalogue(scenarios),
            requestedAt = instant,
            correction = correction,
        )
        return assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
    }

    @Test
    fun `the scenario count is the size of the inventory`() {
        assertEquals(3, request(listOf("alpha", "beta", "gamma")).battery.scenarioCount)
        assertEquals(26, request((1..26).map { "scenario-$it" }).battery.scenarioCount)
    }

    @Test
    fun `the request names the candidate by the digest of the handoff`() {
        val handoff = handoff()
        val outcome = buildCertificationRequest(
            handoff = handoff,
            catalogue = catalogue(listOf("alpha")),
            requestedAt = instant,
        )
        val accepted = assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome)

        assertEquals(handoff.candidateId, accepted.request.candidateId)
        assertEquals(handoff.artifact.sha256, accepted.request.artifact.sha256)
        assertEquals(version, accepted.request.productVersion)
        assertEquals("pipelinek-0.44.0.zip", accepted.request.artifact.name)
    }

    @Test
    fun `the request names the battery and its version`() {
        val battery = request().battery

        assertEquals("harness-certification-battery", battery.id)
        assertEquals("1", battery.version)
    }

    @Test
    fun `the request states the moment it was asked for`() {
        assertEquals("2026-11-05T18:04:00Z", request().requestedAt.canonical)
    }

    @Test
    fun `an inventory with no scenario produces no request`() {
        val outcome = buildCertificationRequest(
            handoff = handoff(),
            catalogue = catalogue(emptyList()),
            requestedAt = instant,
        )

        assertInstanceOf(CertificationRequestOutcome.Refused::class.java, outcome)
    }

    @Test
    fun `an inventory with a repeated scenario id produces no request`() {
        val outcome = buildCertificationRequest(
            handoff = handoff(),
            catalogue = catalogue(listOf("alpha", "beta", "alpha")),
            requestedAt = instant,
        )

        assertInstanceOf(CertificationRequestOutcome.Refused::class.java, outcome)
    }

    @Test
    fun `a request that carries a field this schema does not define is refused`() {
        val encoded = CertificationRequestCodec.encode(request())
        val tampered = encoded.replaceFirst("{", "{\n  \"unexpected\": \"surprise\",")

        val result = CertificationRequestCodec.decode(tampered)

        val rejected = assertInstanceOf(CertificationRequestDecodeResult.Rejected::class.java, result)
        val unknown = assertInstanceOf(
            CertificationRejection.UnknownField::class.java,
            rejected.rejection,
        )
        assertEquals(listOf("unexpected"), unknown.fields)
    }

    @Test
    fun `a well formed request decodes to the value that was encoded`() {
        val request = request()

        val result = CertificationRequestCodec.decode(CertificationRequestCodec.encode(request))

        val accepted = assertInstanceOf(CertificationRequestDecodeResult.Accepted::class.java, result)
        assertEquals(request, accepted.request)
    }

    @Test
    fun `a candidate id that is a name instead of a digest is refused`() {
        val encoded = CertificationRequestCodec.encode(request())
        val tampered = encoded.replace("sha256:$digest", "release-candidate-7")

        val result = CertificationRequestCodec.decode(tampered)

        val rejected = assertInstanceOf(CertificationRequestDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a requested_at that is not an instant is refused`() {
        val encoded = CertificationRequestCodec.encode(request())
        val tampered = encoded.replace("2026-11-05T18:04:00Z", "yesterday")

        val result = CertificationRequestCodec.decode(tampered)

        val rejected = assertInstanceOf(CertificationRequestDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `the same request always encodes to the same bytes`() {
        val request = request()

        val first = CertificationRequestCodec.encode(request)
        val second = CertificationRequestCodec.encode(request)

        assertEquals(first, second)
    }

    @Test
    fun `a nested unknown field is refused`() {
        // The check on the field names of the request covers the TOP level.
        // A field the reader does not know can also sit inside a value the
        // request carries, and there the reader's own flag is the only thing
        // that refuses it. Without this test, that flag is nobody's business:
        // a refactor could flip it and the reader would start swallowing
        // instructions it does not understand, in silence.
        val polluted = CertificationRequestCodec.encode(request())
            .replaceFirst("\"artifact\": {", "\"artifact\": { \"unexpected\": true,")

        val result = CertificationRequestCodec.decode(polluted)

        val rejected = assertInstanceOf(
            CertificationRequestDecodeResult.Rejected::class.java,
            result,
            "a NESTED unknown field was accepted: the reader ignored a field it did not " +
                "understand, which is exactly what a closed shape forbids",
        )
        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a document that declares another schema version is refused`() {
        val encoded = CertificationRequestCodec.encode(request())
        val tampered = encoded.replace(
            CertificationRequest.SCHEMA_VERSION,
            "pipelinek-certification-request-3",
        )

        val result = CertificationRequestCodec.decode(tampered)

        val rejected = assertInstanceOf(CertificationRequestDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejected.rejection)
    }

    @Test
    fun `a request that answers a defect names it and the candidate the defect was seen against`() {
        val request = request(correction = correction())

        val correction = assertInstanceOf(
            CandidateCorrection::class.java,
            request.correction,
        )
        assertEquals("contract.alpha", correction.defect.contract)
        assertEquals("scenario.beta", correction.defect.scenario)
        assertEquals(DefectFailureType.REPRODUCIBLE_DEFECT, correction.defect.failureType)
        assertEquals("sig-123", correction.defect.signature)
        assertEquals(CandidateId.fromDigest("b".repeat(64)).canonical, correction.observedAgainst.canonical)
    }

    @Test
    fun `a candidate that is not a correction says nothing about any defect`() {
        assertNull(request().correction)
    }

    @Test
    fun `a correction that names the candidate itself produces no request`() {
        val outcome = buildCertificationRequest(
            handoff = handoff(),
            catalogue = catalogue(listOf("alpha")),
            requestedAt = instant,
            correction = correction(observedAgainst = CandidateId.fromDigest(digest)),
        )

        val refused = assertInstanceOf(CertificationRequestOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("corrects itself"))
    }

    @Test
    fun `the correction survives the wire`() {
        val encoded = CertificationRequestCodec.encode(request(correction = correction()))

        val decoded = assertInstanceOf(
            CertificationRequestDecodeResult.Accepted::class.java,
            CertificationRequestCodec.decode(encoded),
        )

        assertEquals(correction(), decoded.request.correction)
    }

    @Test
    fun `a document that says a candidate corrects itself is refused`() {
        // The composition refuses to state such a request, but the READER has
        // to refuse it too: a document written elsewhere can carry anything,
        // and the rule that a candidate cannot correct itself is the reader's
        // to apply. The document is tampered rather than built, because the
        // value itself refuses to exist in that state.
        val encoded = CertificationRequestCodec.encode(request(correction = correction()))
        val tampered = encoded.replace(
            CandidateId.fromDigest("b".repeat(64)).canonical,
            CandidateId.fromDigest(digest).canonical,
        )

        val result = CertificationRequestCodec.decode(tampered)

        val rejected = assertInstanceOf(CertificationRequestDecodeResult.Rejected::class.java, result)
        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejected.rejection)
    }
}
