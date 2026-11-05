package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The emission of the certification request event, and the guarantees a reader
 * may take from it.
 *
 * Pure: no filesystem, no clock, no subprocess. The moment the event declares
 * is supplied, not read.
 */
class CertificationRequestedEventEmissionTest {

    private val digest = "a".repeat(64)

    private val publishedAt = Instant.parse("2026-11-05T18:04:00Z")

    /** The request is made BEFORE the publication, and that difference is visible. */
    private val requestedAt = Instant.parse("2026-11-05T17:00:00Z")

    private fun handoff(candidateDigest: String = digest): CandidateHandoff = CandidateHandoff(
        candidateId = CandidateId.fromDigest(candidateDigest),
        releaseTrain = ProductVersion.parseOrThrow("0.44.0"),
        candidateSequence = 1,
        productVersion = ProductVersion.parseOrThrow("0.44.0"),
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
            sha256 = candidateDigest,
            builtAt = "2026-11-05T17:00:00Z",
        ),
        sbom = null,
        sdk = null,
        bom = null,
    )

    private fun handoffText(candidateDigest: String = digest): String =
        CandidateHandoffCodec.encode(handoff(candidateDigest))

    private fun requestText(
        candidateDigest: String = digest,
        batteryId: String = "harness-certification-battery",
        batteryVersion: String = "1",
    ): String {
        val outcome = buildCertificationRequest(
            handoff = handoff(candidateDigest),
            catalogue = CertificationBatteryCatalogue(
                id = batteryId,
                version = batteryVersion,
                scenarios = listOf("alpha", "beta", "gamma"),
            ),
            requestedAt = requestedAt,
        )
        val request = assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
        return CertificationRequestCodec.encode(request)
    }

    private fun publicationReceipt(
        tag: String = "v0.44.0-rc1",
        prerelease: Int = 1,
    ): String = """
        github-release: PUBLISHED tag=$tag prerelease=$prerelease assets=6 remote=origin
          signature verified against AAAA (PipelineK release key)
          the tag was published before the release; gh was asked to verify it, never to create it
          every staged asset was re-measured against SHA256SUMS (6 entries)
    """.trimIndent()

    private fun emission(
        handoff: String = handoffText(),
        request: String = requestText(),
        receipt: String = publicationReceipt(),
        publishedAt: Instant = this.publishedAt,
        previousEmission: (CandidateId) -> String? = { null },
    ) = certificationRequestedEmission(
        handoffText = handoff,
        requestText = request,
        publicationReceipt = receipt,
        publishedAt = publishedAt,
        previousEmission = previousEmission,
    )

    private fun decodedEvent(document: String): CertificationRequestedEvent {
        val decoded = assertInstanceOf(
            CertificationEventDecode.Accepted::class.java,
            CertificationRequestedEventCodec.decode(document),
        )
        return decoded.event
    }

    @Test
    fun `a published release emits one event naming the candidate and the battery`() {
        val ready = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        val event = decodedEvent(ready.document)
        assertEquals("sha256:$digest", event.candidateId.canonical)
        assertEquals("v0.44.0-rc1", event.release.tag)
        assertEquals("rc.certification.requested", event.name)
        assertEquals("harness-certification-battery", event.request.battery.id)
    }

    @Test
    fun `the battery the event names is sized by the inventory it was built from`() {
        val ready = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        assertEquals(3, decodedEvent(ready.document).request.battery.scenarioCount)
    }

    @Test
    fun `the event carries the request inside, with the same candidate`() {
        val ready = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        val event = decodedEvent(ready.document)
        assertEquals(event.candidateId, event.request.candidateId)
        assertEquals(digest, event.request.artifact.sha256)
    }

    @Test
    fun `the request that travels inside is the request that was stated`() {
        val ready = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        val event = decodedEvent(ready.document)
        assertEquals(requestText(), CertificationRequestCodec.encode(event.request))
    }

    @Test
    fun `the event says when the release was published`() {
        val ready = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        assertEquals("2026-11-05T18:04:00Z", decodedEvent(ready.document).occurredAt.canonical)
    }

    @Test
    fun `the request keeps the moment it was made, which is not the moment of the publication`() {
        val ready = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        val event = decodedEvent(ready.document)
        assertEquals("2026-11-05T17:00:00Z", event.request.requestedAt.canonical)
    }

    @Test
    fun `a release that was never published emits nothing`() {
        val refused = assertInstanceOf(
            CertificationEmission.Refused::class.java,
            emission(receipt = "the build finished; nothing was published\n"),
        )
        assertTrue(refused.reason.contains("not published"))
    }

    @Test
    fun `a stable release emits nothing`() {
        val refused = assertInstanceOf(
            CertificationEmission.Refused::class.java,
            emission(receipt = publicationReceipt(tag = "v0.44.0", prerelease = 0)),
        )
        assertTrue(refused.reason.contains("not a release candidate"))
    }

    @Test
    fun `a handoff that cannot be read emits nothing`() {
        val refused = assertInstanceOf(
            CertificationEmission.Refused::class.java,
            emission(handoff = "{\"not\": \"a handoff\"}"),
        )
        assertTrue(refused.reason.contains("handoff could not be read"))
    }

    @Test
    fun `a request that carries a field this build does not know emits nothing`() {
        val tampered = requestText().replaceFirst("{", "{\n  \"unexpected\": true,")

        val refused = assertInstanceOf(CertificationEmission.Refused::class.java, emission(request = tampered))
        assertTrue(refused.reason.contains("unknown field"))
    }

    @Test
    fun `a request that carries an unknown field inside the artifact emits nothing`() {
        val tampered = requestText().replaceFirst("\"artifact\": {", "\"artifact\": { \"unexpected\": true,")

        val refused = assertInstanceOf(CertificationEmission.Refused::class.java, emission(request = tampered))
        assertTrue(refused.reason.contains("could not be read"))
    }

    @Test
    fun `a request about another candidate emits nothing`() {
        val refused = assertInstanceOf(
            CertificationEmission.Refused::class.java,
            emission(request = requestText(candidateDigest = "b".repeat(64))),
        )
        assertTrue(refused.reason.contains("another") || refused.reason.contains("were not published"))
    }

    @Test
    fun `the same emission twice leaves one event, not two`() {
        val first = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        val second = assertInstanceOf(
            CertificationEmission.AlreadyEmitted::class.java,
            emission(previousEmission = { first.document }),
        )
        assertEquals(first.receipt, second.receipt)
    }

    @Test
    fun `the same question asked at a later instant is still the same question`() {
        val first = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        // A publication that is replayed publishes the same bytes under the
        // same tag. The moment changes and the question does not, so the
        // emitter must not deny its own successful retry.
        val later = assertInstanceOf(
            CertificationEmission.Ready::class.java,
            certificationRequestedEmission(
                handoffText = handoffText(),
                requestText = requestText(),
                publicationReceipt = publicationReceipt(),
                publishedAt = Instant.parse("2026-11-05T19:30:00Z"),
            ),
        )
        assertTrue(first.document != later.document)

        val again = assertInstanceOf(
            CertificationEmission.AlreadyEmitted::class.java,
            emission(
                previousEmission = { first.document },
                publishedAt = Instant.parse("2026-11-05T19:30:00Z"),
            ),
        )
        // The receipt describes what is on disk, not what was about to be
        // written: nothing was rewritten, so the first moment is the truth.
        assertTrue(again.receipt.contains("occurred_at: 2026-11-05T18:04:00Z"))
        assertTrue(!again.receipt.contains("2026-11-05T19:30:00Z"))
    }

    @Test
    fun `the same candidate published under another tag is another question`() {
        val first = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        val refused = assertInstanceOf(
            CertificationEmission.Refused::class.java,
            emission(
                previousEmission = { first.document },
                receipt = publicationReceipt(tag = "v0.44.0-rc2"),
            ),
        )
        assertTrue(refused.reason.contains("already emitted"))
        assertTrue(refused.reason.contains("v0.44.0-rc1"))
        assertTrue(refused.reason.contains("v0.44.0-rc2"))
    }

    @Test
    fun `the same candidate certified against another battery is another question`() {
        val first = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        val refused = assertInstanceOf(
            CertificationEmission.Refused::class.java,
            emission(
                previousEmission = { first.document },
                request = requestText(batteryId = "other-battery"),
            ),
        )
        assertTrue(refused.reason.contains("already emitted"))
        assertTrue(refused.reason.contains("harness-certification-battery"))
        assertTrue(refused.reason.contains("other-battery"))
    }

    @Test
    fun `a document that cannot be read is not overwritten`() {
        val refused = assertInstanceOf(
            CertificationEmission.Refused::class.java,
            emission(previousEmission = { "{\"not\": \"an event\"}" }),
        )
        assertTrue(refused.reason.contains("cannot read"))
    }

    @Test
    fun `the receipt says what was emitted and when`() {
        val ready = assertInstanceOf(CertificationEmission.Ready::class.java, emission())

        assertTrue(ready.receipt.contains("event_name: rc.certification.requested"))
        assertTrue(ready.receipt.contains("candidate_id: sha256:$digest"))
        assertTrue(ready.receipt.contains("release_tag: v0.44.0-rc1"))
        assertTrue(ready.receipt.contains("occurred_at: 2026-11-05T18:04:00Z"))
        assertTrue(ready.receipt.contains("requested_at: 2026-11-05T17:00:00Z"))
        assertTrue(
            ready.receipt.contains("battery: harness-certification-battery 1 (3 scenarios)"),
        )
    }
}
