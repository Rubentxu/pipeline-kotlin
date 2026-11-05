package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinitionAny
import dev.rubentxu.pipeline.v2.domain.step.AdmittedPlugin
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL
import java.nio.file.Path

/**
 * The rule that decides whether a contribution provider can be judged at all.
 *
 * The cross-check compares a plugin's manifest against what its OWN code provides, and it can
 * only do that per artifact: a contribution has to say which artifact it came from. The origin
 * of a provider is the code its class was loaded from, and when that origin cannot be measured
 * the contribution has no bucket — so it is invisible to the comparison in BOTH directions. A
 * plugin could then ship a Step that no manifest declares, and the comparison would not see it.
 *
 * A provider in that state is therefore REFUSED, by name. It is not attributed to every
 * artifact either: that would compare one plugin's manifest against a neighbour's contributions
 * and would hide a real drift behind somebody else's implementation.
 *
 * ## The origin names CONTENT, not only an address
 * The same artifact lives at more than one address by design. The installation bundles the
 * plugin in its own JAR, the runtime loads a VERIFIED COPY of it, and the delegation is
 * parent-first — so the provider's class is fetched by the parent and its code source is the
 * bundled JAR, while the artifact that was admitted is the copy. Two addresses holding the
 * same bytes are ONE artifact, and the only fact that says so is the digest of those bytes.
 * An origin that named only the address put the copy's contributions in one bucket and the
 * copy's manifest in another, and the comparison read that manifest against an empty bucket
 * and refused a plugin that declares exactly what it provides.
 *
 * The measurement is separated from the decision on purpose. The measurement reads the JVM's
 * own record of where a class came from and hashes the bytes it finds there, and the decision
 * is about a value. Keeping them apart is what lets the decision be exercised against
 * providers whose origin cannot be produced by any classpath — a class that hides its own
 * origin does not exist in the product's distribution, and a rule that can only be exercised by
 * an artifact nobody ships is a rule nobody checks.
 */
class PluginContributionProvenanceTest {

    @Test
    fun `a Step provider that cannot name its artifact is refused`() {
        val refusal = assertThrows(PluginContributionRefusal.Unattributed::class.java) {
            verify(steps = listOf(Attributed(StepProvider(), ProviderOrigin.Missing)))
        }

        assertTrue(
            refusal.providerClass.contains("StepProvider"),
            "the refusal names the provider that could not be attributed: ${refusal.providerClass}",
        )
    }

    @Test
    fun `a Directive provider that cannot name its artifact is refused`() {
        val refusal = assertThrows(PluginContributionRefusal.Unattributed::class.java) {
            verify(directives = listOf(Attributed(DirectiveProvider(), ProviderOrigin.Missing)))
        }

        assertTrue(
            refusal.providerClass.contains("DirectiveProvider"),
            "the refusal names the provider that could not be attributed: ${refusal.providerClass}",
        )
    }

    @Test
    fun `an Event provider that cannot name its artifact is refused`() {
        val refusal = assertThrows(PluginContributionRefusal.Unattributed::class.java) {
            verify(events = listOf(Attributed(EventProvider(), ProviderOrigin.Missing)))
        }

        assertTrue(
            refusal.providerClass.contains("EventProvider"),
            "the refusal names the provider that could not be attributed: ${refusal.providerClass}",
        )
    }

    @Test
    fun `a provider whose code source is not a file is refused`() {
        val refusal = assertThrows(PluginContributionRefusal.Unattributed::class.java) {
            verify(
                steps = listOf(
                    Attributed(
                        StepProvider(),
                        ProviderOrigin.Unreadable("jar:file:/opt/plugins/sample.jar!/", "not a file"),
                    ),
                ),
            )
        }

        assertTrue(
            refusal.reason.contains("jar:file:/opt/plugins/sample.jar!/"),
            "the refusal names the location that could not be read: ${refusal.reason}",
        )
    }

    @Test
    fun `a neighbour's contribution is not this artifact's business`() {
        // The admitted artifact provides exactly what it declares, and a neighbour that was
        // admitted too provides an extra Step. The extra Step belongs to the neighbour's bucket,
        // so it is not a drift of the admitted artifact and the composition goes through.
        assertDoesNotThrow {
            verify(
                admitted = listOf(
                    admittedArtifact(ADMITTED, ADMITTED_DIGEST, "sample.declared"),
                    admittedArtifact(NEIGHBOUR, NEIGHBOUR_DIGEST, "sample.neighbour"),
                ),
                steps = listOf(
                    Attributed(StepProvider("sample.declared"), ProviderOrigin.Resolved(ADMITTED, ADMITTED_DIGEST)),
                    Attributed(StepProvider("sample.neighbour"), ProviderOrigin.Resolved(NEIGHBOUR, NEIGHBOUR_DIGEST)),
                ),
            )
        }
    }

    @Test
    fun `a contribution the manifest does not declare is refused`() {
        val refusal = assertThrows(PluginContributionRefusal.Drift::class.java) {
            verify(
                steps = listOf(
                    Attributed(StepProvider("sample.declared"), ProviderOrigin.Resolved(ADMITTED, ADMITTED_DIGEST)),
                    Attributed(StepProvider("sample.extra"), ProviderOrigin.Resolved(ADMITTED, ADMITTED_DIGEST)),
                ),
            )
        }

        val rejection = refusal.rejection
        assertTrue(
            rejection is PluginManifestRejection.InvalidManifest,
            "an undeclared contribution is an invalid manifest: $rejection",
        )
        assertTrue(
            (rejection as PluginManifestRejection.InvalidManifest).detail.contains("sample.extra"),
            "the refusal names the contribution the manifest does not declare: ${rejection.detail}",
        )
    }

    @Test
    fun `a class that declares no code source has no origin`() {
        assertEquals(ProviderOrigin.Missing, ProviderProvenance.of(null))
    }

    @Test
    fun `a class whose code source is not a file has an unreadable origin`() {
        val origin = ProviderProvenance.of(URL("http://example.invalid/plugins/sample.jar"))

        assertTrue(
            origin is ProviderOrigin.Unreadable,
            "a location that is not a file cannot be read as one: $origin",
        )
    }

    @Test
    fun `a class loaded from a file has that file and its content as its origin`() {
        // The measurement is stubbed here because the ADDRESS is what this row is about: the
        // row that asserts what the measurement produces when it succeeds is the one about the
        // refusal, and the row that asserts the digest is the artifact's own content is the
        // one about the cross-check.
        val origin = ProviderProvenance.of(URL("file:///opt/plugins/sample.jar")) {
            PluginArtifactDigest.Outcome.Measured(SAMPLE_DIGEST)
        }

        assertEquals(
            ProviderOrigin.Resolved(Path.of("/opt/plugins/sample.jar"), SAMPLE_DIGEST),
            origin,
        )
    }

    @Test
    fun `a class whose file cannot be read has no origin`() {
        // The DEFAULT measurement is the subject of this row: a stub would only prove that the
        // stub answers what it was told. An artifact whose bytes cannot be read has no content,
        // and an address without content cannot name an artifact — so the origin says so
        // instead of claiming an identity it cannot establish.
        val origin = ProviderProvenance.of(URL("file:///opt/pipelinek/plugins/does-not-exist.jar"))

        assertTrue(
            origin is ProviderOrigin.Unreadable,
            "a file that cannot be read names no artifact: $origin",
        )
    }

    @Test
    fun `a plugin loaded from the bundled JAR is checked against the copy it was admitted as`() {
        // The installation bundles the plugin in its own JAR, and the runtime loads a VERIFIED
        // COPY of it. The delegation is parent-first, so the provider's class is fetched by the
        // PARENT and its origin is the bundled JAR's address, while the admitted artifact is the
        // copy's address. The BYTES are the same, so the contribution belongs to the admitted
        // artifact and the comparison has to find it there.
        assertDoesNotThrow {
            verify(
                admitted = listOf(admittedArtifact(COPY, BUNDLED_DIGEST)),
                steps = listOf(
                    Attributed(StepProvider("sample.declared"), ProviderOrigin.Resolved(BUNDLED, BUNDLED_DIGEST)),
                ),
            )
        }
    }

    @Test
    fun `a provider whose artifact was not admitted is refused`() {
        // A provider from an artifact that was not admitted has no manifest to be compared
        // against. Its contribution cannot be judged, and a contribution that cannot be judged
        // cannot be waved through either: it would be invisible to the comparison.
        val refusal = assertThrows(PluginContributionRefusal.Unattributed::class.java) {
            verify(
                steps = listOf(
                    Attributed(StepProvider("sample.declared"), ProviderOrigin.Resolved(ADMITTED, ADMITTED_DIGEST)),
                    Attributed(StepProvider("sample.outsider"), ProviderOrigin.Resolved(OUTSIDER, OUTSIDER_DIGEST)),
                ),
            )
        }

        assertTrue(
            refusal.providerClass.contains("StepProvider"),
            "the refusal names the provider that could not be attributed: ${refusal.providerClass}",
        )
    }

    // --- The fixture ----------------------------------------------------------

    private fun verify(
        admitted: List<AdmittedArtifact> = listOf(admittedArtifact()),
        steps: List<Attributed<StepDefinitionContributor>> = emptyList(),
        directives: List<Attributed<DirectiveContributor>> = emptyList(),
        events: List<Attributed<EventDefinitionContributor>> = emptyList(),
    ) = PluginContributionVerifier.verify(
        admitted = admitted,
        steps = steps,
        directives = directives,
        events = events,
    )

    /**
     * An admitted artifact whose manifest declares exactly one Step.
     *
     * The manifest is decoded with the product's own codec, so the document under test is the
     * shape the runtime reads out of a plugin JAR, and the identity carries the same digest on
     * both sides because the fixture never claims to have measured anything.
     */
    private fun admittedArtifact(
        path: Path = ADMITTED,
        digest: Digest = ADMITTED_DIGEST,
        stepKey: String = "sample.declared",
    ): AdmittedArtifact {
        val manifest = when (
            val decoded = PluginManifestCodec.decode(
                """
                {
                  "codec": "pipelinek-manifest-codec/v1",
                  "schemaVersion": "manifest/v1",
                  "plugin": "v1:plugin:example/sample",
                  "releaseVersion": "0.1.0",
                  "releaseDigest": "sha256:${"0".repeat(64)}",
                  "apiRange": "[0.0.0, 99.0.0)",
                  "publisher": "sample",
                  "delivery": "EXTERNAL_REFERENCE",
                  "trust": "unverified",
                  "families": ["UTILITIES"],
                  "steps": [{"stepKey": "$stepKey", "declaredCapabilities": []}],
                  "directives": [],
                  "events": [],
                  "capabilities": []
                }
                """.trimIndent(),
            )
        ) {
            is PluginManifestDecodeResult.Accepted -> decoded.manifest
            is PluginManifestDecodeResult.Rejected ->
                error("the fixture manifest was refused: ${decoded.rejection}")
        }
        return AdmittedArtifact(
            path = path,
            plugin = AdmittedPlugin(
                manifest = manifest,
                identity = MeasuredArtifactIdentity(
                    declaredDigest = manifest.release.digest,
                    measuredDigest = manifest.release.digest,
                    origin = ArtifactOrigin.LocalClasspathEntry("/fixtures/sample-plugin.jar"),
                ),
            ),
            measuredDigest = digest,
        )
    }

    private class StepProvider(private val key: String = "sample.declared") : StepDefinitionContributor {
        override val id: String = "test.step"

        override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(definition(key))
    }

    private class DirectiveProvider : DirectiveContributor {
        override fun definitions(): List<DirectiveDefinitionAny> = emptyList()
    }

    private class EventProvider : EventDefinitionContributor {
        override val id: String = "test.event"

        override fun definitions(): Iterable<EventDefinitionCreation<*>> = emptyList()
    }

    private companion object {
        val ADMITTED: Path = Path.of("/opt/pipelinek/plugins/sample-plugin.jar")
        val NEIGHBOUR: Path = Path.of("/opt/pipelinek/plugins/neighbour-plugin.jar")

        /** The COPY the runtime loads: the verified artifact, at the address it is loaded from. */
        val COPY: Path = Path.of("/opt/pipelinek/control/plugin-copies/sample-plugin.jar")

        /** The JAR the installation bundles: the same bytes, at the address they were read from. */
        val BUNDLED: Path = Path.of("/opt/pipelinek/lib/sample-plugin.jar")

        /** An artifact that was never admitted: no manifest of its own to compare against. */
        val OUTSIDER: Path = Path.of("/opt/pipelinek/lib/outsider-plugin.jar")

        /** The content of the admitted artifact, and of its neighbour: two different artifacts. */
        val ADMITTED_DIGEST: Digest = digestOf('a')
        val NEIGHBOUR_DIGEST: Digest = digestOf('b')
        val OUTSIDER_DIGEST: Digest = digestOf('c')
        val SAMPLE_DIGEST: Digest = digestOf('d')

        /**
         * The content of the bundled JAR and of the copy of it: ONE artifact at two addresses.
         */
        val BUNDLED_DIGEST: Digest = digestOf('e')

        fun digestOf(fill: Char): Digest = Digest("sha256:" + fill.toString().repeat(64))

        /** A Step whose contract is a name and nothing else: the cross-check compares names. */
        fun definition(key: String): StepDefinition<String, String> =
            object : StepDefinition<String, String> {
                override val contract = StepContract(
                    key = PluginStepId(key),
                    descriptor = StepDescriptor(stepId = key, name = key, configRef = "sample"),
                    inputCodec = StringCodec,
                    outputCodec = StringCodec,
                )
                override val handler = StepHandler<String, String> { _, _ -> "" }
            }

        object StringCodec : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }
    }
}
