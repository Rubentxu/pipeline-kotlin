package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reading of a defect report: a document this product did not write.
 *
 * The document is the contract with the harness, so the reader has to be able
 * to say what it does NOT understand, and it has to say it in words: a
 * receiver that swallowed an unknown field would claim to have understood a
 * message that carried an instruction it did not read.
 */
class DefectReportedEventReaderTest {

    private fun document(
        schema: String = "pipelinek-defect-reported-1",
        name: String = "defect.reported",
        protocol: String = "1",
        candidate: String = "sha256:${"a".repeat(64)}",
        failureType: String = "REPRODUCIBLE_DEFECT",
        observedAt: String = "2026-11-05T18:04:00Z",
        extra: String = "",
    ): String = """
        {
          "schema_version": "$schema",
          "name": "$name",
          "protocol_version": "$protocol",
          "candidate_id": "$candidate",
          "defect": {
            "contract": "contract.alpha",
            "scenario": "scenario.beta",
            "failure_type": "$failureType",
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
          "observed_at": "$observedAt"$extra
        }
    """.trimIndent()

    private fun read(text: String) =
        assertInstanceOf(DefectReportedEventDecode.Accepted::class.java, DefectReportedEventReader.decode(text))

    private fun rejected(text: String) =
        assertInstanceOf(DefectReportedEventDecode.Rejected::class.java, DefectReportedEventReader.decode(text))

    @Test
    fun `a report this build understands gives the candidate, the footprint and the evidence`() {
        val event = read(document()).event

        assertEquals("pipelinek-defect-reported-1", event.schemaVersion)
        assertEquals("defect.reported", event.name)
        assertEquals("1", event.protocolVersion)
        assertEquals("sha256:${"a".repeat(64)}", event.candidateId.canonical)
        assertEquals("contract.alpha", event.defect.contract)
        assertEquals("scenario.beta", event.defect.scenario)
        assertEquals(DefectFailureType.REPRODUCIBLE_DEFECT, event.defect.failureType)
        assertEquals("sig-123", event.defect.signature)
        assertEquals("0.44.0", event.evidence.productVersion.toString())
        assertEquals("abc123", event.evidence.harnessRevision)
        assertEquals("the scenario failed", event.evidence.observed)
        assertEquals(DefectSeverity.HIGH, event.evidence.severity)
        assertTrue(event.evidence.blocksRelease)
        assertEquals("2026-11-05T18:04:00Z", event.observedAt.canonical)
    }

    @Test
    fun `a field this schema does not define is refused by name`() {
        val read = rejected(document(extra = ",\n  \"extra_field\": 1"))

        val rejection = assertInstanceOf(CertificationRejection.UnknownField::class.java, read.rejection)
        assertEquals(listOf("extra_field"), rejection.fields)
    }

    @Test
    fun `a document that declares another schema version is refused`() {
        val read = rejected(document(schema = "pipelinek-defect-reported-2"))

        val rejection = assertInstanceOf(CertificationRejection.MalformedDocument::class.java, read.rejection)
        assertTrue(rejection.detail.contains("pipelinek-defect-reported-1"))
    }

    @Test
    fun `a document that speaks another protocol version is refused`() {
        val read = rejected(document(protocol = "2"))

        val rejection = assertInstanceOf(CertificationRejection.MalformedDocument::class.java, read.rejection)
        assertTrue(rejection.detail.contains("protocol"))
    }

    @Test
    fun `a document that carries another name is refused`() {
        val read = rejected(document(name = "defect.ignored"))

        val rejection = assertInstanceOf(CertificationRejection.MalformedDocument::class.java, read.rejection)
        assertTrue(rejection.detail.contains("defect.reported"))
    }

    @Test
    fun `a failure type outside the vocabulary is refused`() {
        val read = rejected(document(failureType = "MAYBE_A_DEFECT"))

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, read.rejection)
    }

    @Test
    fun `a candidate that is a name instead of a digest is refused`() {
        val read = rejected(document(candidate = "v0.44.0-rc1"))

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, read.rejection)
    }

    @Test
    fun `a report whose moment is not an instant is refused`() {
        val read = rejected(document(observedAt = "yesterday"))

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, read.rejection)
    }

    @Test
    fun `a document that is not an object is refused`() {
        val read = rejected("[]")

        val rejection = assertInstanceOf(CertificationRejection.MalformedDocument::class.java, read.rejection)
        assertTrue(rejection.detail.contains("JSON object"))
    }
}
