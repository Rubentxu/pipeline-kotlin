package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The event that asks a receiver to certify a published candidate, and the
 * guarantees a reader may take from it.
 *
 * The event crosses the boundary between two repositories, so its shape is a
 * contract: a receiver that cannot understand the whole document has to be
 * able to say so, and a receiver that CAN understand it has to be able to
 * trust that the candidate it names is the candidate the request asks about.
 *
 * Pure: no filesystem, no clock, no subprocess. The moment the event declares
 * is supplied, not read.
 */
class CertificationRequestedEventTest {

    private val version = ProductVersion.parseOrThrow("0.44.0")

    /** 64 hex, so the candidate identity is a digest and not a name. */
    private val digest = "a".repeat(64)

    private val publishedAt = Instant.parse("2026-11-05T18:04:00Z")

    private fun request(): CertificationRequest {
        val outcome = buildCertificationRequest(
            handoff = CandidateHandoff(
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
            ),
            catalogue = CertificationBatteryCatalogue(
                id = "harness-certification-battery",
                version = "1",
                scenarios = listOf("alpha", "beta", "gamma"),
            ),
            requestedAt = Instant.parse("2026-11-05T17:00:00Z"),
        )
        return assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
    }

    private fun release() = PublishedReleaseRef(tag = "v0.44.0-rc1", version = version)

    private fun event(): CertificationRequestedEvent {
        val outcome = buildCertificationRequestedEvent(
            request = request(),
            release = release(),
            occurredAt = publishedAt,
        )
        return assertInstanceOf(
            CertificationRequestedEventOutcome.Accepted::class.java,
            outcome,
        ).event
    }

    @Test
    fun `the event is named after the event the receiver listens for`() {
        assertEquals("rc.certification.requested", event().name)
        assertEquals("rc.certification.requested", CertificationRequestedEvent.NAME)
    }

    @Test
    fun `a well formed event decodes to the value that was encoded`() {
        val event = event()

        val result = CertificationRequestedEventCodec.decode(
            CertificationRequestedEventCodec.encode(event),
        )

        val decoded = assertInstanceOf(CertificationEventDecode.Accepted::class.java, result)
        assertEquals(event, decoded.event)
    }

    @Test
    fun `the event carries the request inside, with the same candidate`() {
        val request = request()

        val event = assertInstanceOf(
            CertificationRequestedEventOutcome.Accepted::class.java,
            buildCertificationRequestedEvent(request, release(), publishedAt),
        ).event

        assertEquals(request, event.request)
        assertEquals(request.candidateId, event.candidateId)
        assertEquals(request.artifact.sha256, event.request.artifact.sha256)
    }

    @Test
    fun `the event says when the release was published`() {
        assertEquals("2026-11-05T18:04:00Z", event().occurredAt.canonical)
    }

    @Test
    fun `the event names the published release`() {
        val release = event().release

        assertEquals("v0.44.0-rc1", release.tag)
        assertEquals(version, release.version)
    }

    @Test
    fun `a release with no tag produces no event`() {
        val outcome = buildCertificationRequestedEvent(
            request = request(),
            release = PublishedReleaseRef(tag = "  ", version = version),
            occurredAt = publishedAt,
        )

        assertInstanceOf(CertificationRequestedEventOutcome.Refused::class.java, outcome)
    }

    @Test
    fun `an event that carries a field this schema does not define is refused`() {
        val encoded = CertificationRequestedEventCodec.encode(event())
        val tampered = encoded.replaceFirst("{", "{\n  \"unexpected\": \"surprise\",")

        val result = CertificationRequestedEventCodec.decode(tampered)

        val refused = assertInstanceOf(CertificationEventDecode.Rejected::class.java, result)
        val unknown = assertInstanceOf(
            CertificationRequestRejection.UnknownField::class.java,
            refused.rejection,
        )
        assertEquals(listOf("unexpected"), unknown.fields)
    }

    @Test
    fun `an event that carries an unknown field inside the request is refused`() {
        val encoded = CertificationRequestedEventCodec.encode(event())
        val tampered = encoded.replaceFirst("\"artifact\": {", "\"artifact\": { \"unexpected\": true,")

        val result = CertificationRequestedEventCodec.decode(tampered)

        val refused = assertInstanceOf(CertificationEventDecode.Rejected::class.java, result)
        assertInstanceOf(
            CertificationRequestRejection.MalformedDocument::class.java,
            refused.rejection,
        )
    }

    @Test
    fun `an event that declares another schema version is refused`() {
        val encoded = CertificationRequestedEventCodec.encode(event())
        val tampered = encoded.replace(
            CertificationRequestedEvent.SCHEMA_VERSION,
            "pipelinek-certification-requested-2",
        )

        val result = CertificationRequestedEventCodec.decode(tampered)

        val refused = assertInstanceOf(CertificationEventDecode.Rejected::class.java, result)
        assertInstanceOf(
            CertificationRequestRejection.MalformedDocument::class.java,
            refused.rejection,
        )
    }

    @Test
    fun `an event that speaks another protocol version is refused`() {
        val encoded = CertificationRequestedEventCodec.encode(event())
        val tampered = encoded.replace(
            "\"protocol_version\": \"${CertificationRequestedEvent.PROTOCOL_VERSION}\"",
            "\"protocol_version\": \"2\"",
        )

        val result = CertificationRequestedEventCodec.decode(tampered)

        val refused = assertInstanceOf(CertificationEventDecode.Rejected::class.java, result)
        assertInstanceOf(
            CertificationRequestRejection.MalformedDocument::class.java,
            refused.rejection,
        )
    }

    @Test
    fun `an event that carries another name is refused`() {
        val encoded = CertificationRequestedEventCodec.encode(event())
        val tampered = encoded.replace(
            "\"name\": \"${CertificationRequestedEvent.NAME}\"",
            "\"name\": \"rc.certified\"",
        )

        val result = CertificationRequestedEventCodec.decode(tampered)

        val refused = assertInstanceOf(CertificationEventDecode.Rejected::class.java, result)
        assertInstanceOf(
            CertificationRequestRejection.MalformedDocument::class.java,
            refused.rejection,
        )
    }

    @Test
    fun `an event that names one candidate and carries a request for another is refused`() {
        val encoded = CertificationRequestedEventCodec.encode(event())
        // Only the FIRST occurrence: the document names the candidate twice, once
        // at the top level and once inside the request, and tampering both
        // would leave them agreeing — which is a different document entirely.
        val tampered = encoded.replaceFirst(
            "\"candidate_id\": \"sha256:$digest\"",
            "\"candidate_id\": \"sha256:${"b".repeat(64)}\"",
        )

        val result = CertificationRequestedEventCodec.decode(tampered)

        val refused = assertInstanceOf(CertificationEventDecode.Rejected::class.java, result)
        assertInstanceOf(
            CertificationRequestRejection.MalformedDocument::class.java,
            refused.rejection,
        )
    }
}
