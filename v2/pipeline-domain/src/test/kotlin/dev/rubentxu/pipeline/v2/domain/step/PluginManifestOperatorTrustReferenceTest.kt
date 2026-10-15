package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * B3.2 — the `operatorTrustReference` URI field on the manifest document.
 *
 * ## Why this test is for
 *
 * The codec has a closed grammar and four required sections, and B3.2 adds ONE optional
 * field. Three things can go wrong: the field is required when it should be optional
 * (a backward-incompatible change), the URI is silently dropped when malformed (a
 * fail-open), or the codec forgets to write the field on encode (so round-trip
 * doesn't preserve the URI). Each row here pins one of those.
 *
 * ## Backward compat
 *
 * The four shipped plugin manifests at `v2/pipeline-step-sdk/{http,scm-git,junit,utilities}/
 * build/resources/main/META-INF/pipelinek/plugin-manifest.json` do NOT carry the field.
 * A pre-B3.2 manifest must still decode cleanly with the new codec: round-trip without
 * the field MUST yield `operatorTrustReference = null`, NOT a decode error.
 */
@DisplayName("B3.2 operatorTrustReference is optional; malformed URIs are refused, not dropped")
class PluginManifestOperatorTrustReferenceTest {

    private val samplePlugin = dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs.plugin("test", "x")
    private val baseRelease = PluginReleaseRef(samplePlugin, SemVer(0, 1, 0), Digest("sha256:" + "a".repeat(64)))

    @Test
    @DisplayName("a manifest without the field decodes with operatorTrustReference = null (backward compat)")
    fun absentFieldDecodesAsNull() {
        val manifest = PluginManifest(
            schemaVersion = ManifestSchemaVersion.CURRENT,
            plugin = samplePlugin,
            release = baseRelease,
            apiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 70, 0)),
            publisher = "test",
            families = setOf(PluginFamily.TESTING),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified(),
            contributions = PluginContributions(
                capabilities = setOf(StepCapability("test.capability")),
            ),
        )
        val encoded = PluginManifestCodec.encode(manifest)
        val decoded = PluginManifestCodec.decode(encoded)
        val accepted = (decoded as? PluginManifestDecodeResult.Accepted)?.manifest
        assertNotNull(accepted, "a manifest without the field MUST decode cleanly; got $decoded")
        assertNull(accepted!!.operatorTrustReference, "absent field MUST decode as null, not blank-string")
    }

    @Test
    @DisplayName("a manifest with a valid URI round-trips with the URI preserved")
    fun validUriRoundTrips() {
        val uri = "https://operator.example.com/pipelinek-trust/v0.61.0"
        val manifest = PluginManifest(
            schemaVersion = ManifestSchemaVersion.CURRENT,
            plugin = samplePlugin,
            release = baseRelease,
            apiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 70, 0)),
            publisher = "test",
            families = setOf(PluginFamily.TESTING),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified(),
            contributions = PluginContributions(
                capabilities = setOf(StepCapability("test.capability")),
            ),
            operatorTrustReference = uri,
        )
        val encoded = PluginManifestCodec.encode(manifest)
        val decoded = PluginManifestCodec.decode(encoded)
        val accepted = (decoded as? PluginManifestDecodeResult.Accepted)?.manifest
        assertNotNull(accepted, "a manifest with a valid URI MUST decode cleanly; got $decoded")
        assertEquals(uri, accepted!!.operatorTrustReference, "the URI MUST round-trip exactly")
    }

    @Test
    @DisplayName("a manifest with a malformed URI is refused as MalformedDocument, not silently dropped")
    fun malformedUriIsRefused() {
        val encoded = PluginManifestCodec.encode(minimalManifest()) +
            // Insert a malformed URI into the encoded JSON. The codec encodes the field
            // before the families array, so splice the field in there.
            ""
        // Easier: hand-craft a manifest text with a malformed URI in the field.
        val text = """
            {
              "codec": "${PluginManifestCodec.CODEC}",
              "schemaVersion": "manifest/v1",
              "plugin": "${samplePlugin.canonicalText()}",
              "releaseVersion": "0.1.0",
              "releaseDigest": "sha256:${"a".repeat(64)}",
              "apiRange": "[0.47.0, 0.70.0)",
              "publisher": "test",
              "delivery": "EXTERNAL_REFERENCE",
              "trust": "unverified",
              "operatorTrustReference": "not a valid uri with spaces and \u0000 control",
              "families": ["TESTING"],
              "steps": [],
              "directives": [],
              "events": [],
              "capabilities": ["test.capability"]
            }
        """.trimIndent()

        val decoded = PluginManifestCodec.decode(text)
        val rejected = decoded as? PluginManifestDecodeResult.Rejected
        assertNotNull(rejected, "a malformed URI MUST be refused; got $decoded")
        assertTrue(
            rejected!!.rejection is PluginManifestRejection.MalformedDocument,
            "expected MalformedDocument, got ${rejected.rejection}",
        )
        // The error message MUST name the bad field so an operator can find it.
        val detail = (rejected.rejection as PluginManifestRejection.MalformedDocument).detail
        assertTrue(
            detail.contains("operatorTrustReference"),
            "the refusal MUST name the bad field; got '$detail'",
        )
    }

    @Test
    @DisplayName("a manifest with a wrong-typed field (not a string) is refused as MalformedDocument")
    fun wrongTypedFieldIsRefused() {
        val text = """
            {
              "codec": "${PluginManifestCodec.CODEC}",
              "schemaVersion": "manifest/v1",
              "plugin": "${samplePlugin.canonicalText()}",
              "releaseVersion": "0.1.0",
              "releaseDigest": "sha256:${"a".repeat(64)}",
              "apiRange": "[0.47.0, 0.70.0)",
              "publisher": "test",
              "delivery": "EXTERNAL_REFERENCE",
              "trust": "unverified",
              "operatorTrustReference": 42,
              "families": ["TESTING"],
              "steps": [],
              "directives": [],
              "events": [],
              "capabilities": ["test.capability"]
            }
        """.trimIndent()

        val decoded = PluginManifestCodec.decode(text)
        val rejected = decoded as? PluginManifestDecodeResult.Rejected
        assertNotNull(rejected, "a wrong-typed field MUST be refused; got $decoded")
    }

    private fun minimalManifest(): PluginManifest = PluginManifest(
        schemaVersion = ManifestSchemaVersion.CURRENT,
        plugin = samplePlugin,
        release = baseRelease,
        apiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 70, 0)),
        publisher = "test",
        families = setOf(PluginFamily.TESTING),
        delivery = Delivery.EXTERNAL_REFERENCE,
        trust = TrustMetadata.Unverified(),
        contributions = PluginContributions(
            capabilities = setOf(StepCapability("test.capability")),
        ),
    )
}
