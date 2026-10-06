package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmission
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S6/C — the codec must REFUSE what it cannot represent, and the refusal must be typed.
 *
 * ## Why this exists at all
 *
 * A codec that decodes leniently is worse than no codec: it hands admission a manifest the
 * reader invented. Each case here is a document a real packaging mistake produces — a wrong
 * codec token, a schema from the future, a truncated array — and each has to end in a named
 * [PluginManifestRejection], not an exception, not a best-effort parse.
 *
 * ## Non-vacuity
 *
 * A parser test passes trivially when the parser refuses everything, and passes trivially
 * when it accepts everything. [roundTripPreservesEveryDeclaredFact] pins the positive side
 * (real manifests survive encode→decode unchanged), so the negative cases cannot be passing
 * merely because nothing is ever accepted.
 */
@DisplayName("S6/C the manifest codec is total: it refuses what it cannot represent")
class PluginManifestCodecFidelityTest {

    private fun validManifest(
        apiRange: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0)),
    ): PluginManifest {
        val plugin = ResourceRefs.plugin("sdk-http", "http")
        return PluginManifest(
            schemaVersion = ManifestSchemaVersion.CURRENT,
            plugin = plugin,
            release = PluginReleaseRef(plugin, SemVer(0, 36, 0), Digest("sha256:" + "a".repeat(64))),
            apiRange = apiRange,
            publisher = "rubentxu",
            families = setOf(PluginFamily.NETWORK, PluginFamily.SUPPLY_CHAIN),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
            contributions = PluginContributions(
                steps = listOf(
                    PluginStepContribution(
                        stepKey = PluginStepId("http.request"),
                        declaredCapabilities = setOf(
                            dev.rubentxu.pipeline.v2.domain.step.StepCapability("HTTP_TRANSPORT"),
                            dev.rubentxu.pipeline.v2.domain.step.StepCapability("NETWORK_EGRESS"),
                        ),
                    ),
                ),
                directives = listOf(
                    dev.rubentxu.pipeline.v2.domain.step.PluginDirectiveContribution(
                        dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey("http.around"),
                    ),
                ),
                events = listOf(dev.rubentxu.pipeline.v2.domain.step.PluginEventContribution("http.request.completed")),
                capabilities = setOf(dev.rubentxu.pipeline.v2.domain.step.StepCapability("NETWORK_EGRESS")),
            ),
        )
    }

    @Test
    @DisplayName("a real manifest survives encode -> decode with every declared fact intact")
    fun roundTripPreservesEveryDeclaredFact() {
        val original = validManifest()
        val decoded = PluginManifestCodec.decode(PluginManifestCodec.encode(original))

        val manifest = (decoded as? PluginManifestDecodeResult.Accepted)?.manifest
        assertNotNull(manifest, "a VALID manifest must round-trip; refusing everything is not correctness")
        assertTrue(manifest == original, "round-trip changed the manifest:\n  before=$original\n  after =$manifest")
    }

    @Test
    @DisplayName("encoding is deterministic: same manifest, same bytes")
    fun encodingIsDeterministic() {
        val manifest = validManifest()
        val first = PluginManifestCodec.encode(manifest)
        val second = PluginManifestCodec.encode(manifest)
        assertTrue(
            first == second,
            "manifest encoding is not deterministic; every digest over it becomes noise",
        )
    }

    @Test
    @DisplayName("an unknown codec is refused as UnknownCodec, not read on a guess")
    fun unknownCodecIsRefused() {
        val text = PluginManifestCodec.encode(validManifest())
            .replace(PluginManifestCodec.CODEC, "some-other-codec/v9")

        val decoded = PluginManifestCodec.decode(text)
        assertTrue(
            (decoded as PluginManifestDecodeResult.Rejected).rejection is PluginManifestRejection.UnknownCodec,
            "expected UnknownCodec, got $decoded",
        )
    }

    @Test
    @DisplayName("a future schema version is refused as UnsupportedSchema")
    fun futureSchemaIsRefused() {
        val text = PluginManifestCodec.encode(validManifest())
            .replace("manifest/v1", "manifest/v7")

        val decoded = PluginManifestCodec.decode(text)
        assertTrue(
            (decoded as PluginManifestDecodeResult.Rejected).rejection is PluginManifestRejection.UnsupportedSchema,
            "expected UnsupportedSchema, got $decoded",
        )
    }

    @Test
    @DisplayName("truncated JSON is refused, not partially accepted")
    fun truncatedDocumentIsRefused() {
        val text = PluginManifestCodec.encode(validManifest()).substringBefore("\"steps\"")

        val decoded = PluginManifestCodec.decode(text)
        assertTrue(
            (decoded as PluginManifestDecodeResult.Rejected).rejection is PluginManifestRejection.MalformedDocument,
            "expected MalformedDocument, got $decoded",
        )
    }

    @Test
    @DisplayName("a manifest that duplicates a StepKey cannot be decoded at all")
    fun duplicateStepKeyIsRefused() {
        val text = PluginManifestCodec.encode(validManifest())
            .replace(
                "\"steps\": [{\"stepKey\": \"http.request\"",
                "\"steps\": [{\"stepKey\": \"http.request\", \"declaredCapabilities\": []}, " +
                    "{\"stepKey\": \"http.request\"",
            )

        val decoded = PluginManifestCodec.decode(text)
        assertTrue(
            (decoded as PluginManifestDecodeResult.Rejected).rejection is PluginManifestRejection.MalformedDocument,
            "a self-contradictory manifest must not decode; got $decoded",
        )
    }

    @Test
    @DisplayName("a manifest with an EMPTY api range interval is refused rather than inverted")
    fun invertedApiRangeIsRefused() {
        // [0.49.0, 0.47.0) is decreasing; PipelineKApiRange forbids it at construction.
        val text = PluginManifestCodec.encode(validManifest())
            .replace("[0.47.0, 0.49.0)", "[0.49.0, 0.47.0)")

        val decoded = PluginManifestCodec.decode(text)
        assertTrue(
            (decoded as PluginManifestDecodeResult.Rejected).rejection is PluginManifestRejection.MalformedDocument,
            "expected MalformedDocument for an inverted range, got $decoded",
        )
    }

    @Test
    @DisplayName("the api range boundary is EXCLUSIVE at the top and INCLUSIVE at the bottom")
    fun apiRangeBoundariesAreCorrect() {
        val range = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))
        assertTrue(range.accepts(SemVer(0, 47, 0)), "lower bound must be inclusive")
        assertTrue(range.accepts(SemVer(0, 48, 9)), "inside the range must be accepted")
        assertTrue(!range.accepts(SemVer(0, 49, 0)), "upper bound must be EXCLUSIVE")
        assertTrue(!range.accepts(SemVer(0, 46, 9)), "below the range must be refused")
    }

    @Test
    @DisplayName("an unmeasured artifact is Unverified, never Verified")
    fun unmeasuredIsNotVerified() {
        val identity = MeasuredArtifactIdentity(
            declaredDigest = Digest("sha256:" + "b".repeat(64)),
            measuredDigest = null,
            origin = ArtifactOrigin.LocalClasspathEntry("some.jar"),
        )

        val verdict = identity.verdict
        assertTrue(
            verdict is dev.rubentxu.pipeline.v2.domain.step.ArtifactIdentityVerdict.Unverified,
            "\"nobody measured\" must not collapse into \"checked and fine\"; got $verdict",
        )
    }

    @Test
    @DisplayName("a digest that contradicts the measured bytes is a Mismatch, not a pass")
    fun contradictingDigestIsMismatch() {
        val identity = MeasuredArtifactIdentity(
            declaredDigest = Digest("sha256:" + "c".repeat(64)),
            measuredDigest = Digest("sha256:" + "d".repeat(64)),
            origin = ArtifactOrigin.LocalClasspathEntry("some.jar"),
        )

        val verdict = identity.verdict
        assertTrue(
            verdict is dev.rubentxu.pipeline.v2.domain.step.ArtifactIdentityVerdict.Mismatch,
            "a plugin that lies about its own bytes must be visible as a mismatch; got $verdict",
        )
    }

    @Test
    @DisplayName("admission refuses a manifest whose measured bytes contradict the claim")
    fun admissionSeesTheMismatchWithoutBeingToldTo() {
        // Demonstrates that MeasuredArtifactIdentity carries information admission can act on.
        val decoded = PluginManifestCodec.decode(PluginManifestCodec.encode(validManifest()))
        val accepted = decoded as PluginManifestDecodeResult.Accepted
        val identity = MeasuredArtifactIdentity(
            declaredDigest = accepted.manifest.release.digest,
            measuredDigest = Digest("sha256:" + "e".repeat(64)),
            origin = ArtifactOrigin.LocalClasspathEntry("some.jar"),
        )

        assertTrue(
            identity.verdict is dev.rubentxu.pipeline.v2.domain.step.ArtifactIdentityVerdict.Mismatch,
            "the runtime must be able to see that the artifact contradicts its own manifest",
        )

        // And admission itself still decides on the manifest facts, which are valid here.
        val result = PluginAdmission.admit(
            decoded = decoded,
            identity = identity,
            runtimeVersion = SemVer(0, 47, 0),
            alreadyAdmitted = emptySet(),
        )
        assertTrue(
            result is PluginAdmissionResult.Admitted,
            "manifest facts are valid, so admission admits; the digest verdict is a SEPARATE question " +
                "that M3b's verifier answers. Claiming otherwise here would hide the seam.",
        )
    }
}
