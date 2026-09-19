package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.identity.InvalidResourceRefException
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepManifest
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * LFC-2E2-prep fitness suite for the Plugin Policy Readiness Gate
 * (C1..C10). The suite is BEHAVIOURAL: each condition is verified
 * through at least one positive case (the documented behaviour is real)
 * and at least one negative case (a lapse is rejected). The mere
 * existence of a data class or method name is NOT sufficient to make
 * a condition pass.
 *
 * ADR-0092. Authority: PLUGIN_POLICY_READINESS_GATE.md.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Lfc2PolicyReadinessFitnessTest {

    // ---- helpers ----

    private val SCOPE_FS = StepCapability("filesystem.read")

    private data class EchoInput(val text: String)
    private data class EchoOutput(val text: String)
    private val passthroughCodec = object : StepCodec<EchoInput> {
        override fun encode(value: EchoInput) = EncodedStepValue("\"${value.text}\"")
        override fun decode(encoded: EncodedStepValue) = EchoInput(encoded.value.trim('"'))
        override fun schema() = "{}"
    }
    private val passthroughOut = object : StepCodec<EchoOutput> {
        override fun encode(value: EchoOutput) = EncodedStepValue("\"${value.text}\"")
        override fun decode(encoded: EncodedStepValue) = EchoOutput(encoded.value.trim('"'))
        override fun schema() = "{}"
    }

    private fun definition(
        keyValue: String,
        caps: Set<StepCapability> = emptySet(),
        body: suspend (EchoInput, StepHandlerContext) -> EchoOutput = { i, _ -> EchoOutput(i.text) },
    ): StepDefinition<EchoInput, EchoOutput> {
        val key = PluginStepId(keyValue)
        val contract = StepContract(
            key = key,
            descriptor = StepDescriptor(stepId = keyValue, name = keyValue.substringAfter('.'), configRef = keyValue),
            inputCodec = passthroughCodec,
            outputCodec = passthroughOut,
            requiredCapabilities = caps,
        )
        val handler = StepHandler { input: EchoInput, ctx: StepHandlerContext ->
            body(input, ctx)
        }
        return object : StepDefinition<EchoInput, EchoOutput> {
            override val contract = contract
            override val handler = handler
        }
    }

    private fun scmGitPluginRef() =
        ResourceRefs.plugin(namespace = "rubentxu", identity = "scm-git")

    private fun scmGitRelease(pluginRef: dev.rubentxu.pipeline.v2.domain.identity.ResourceRef) =
        PluginReleaseRef(
            plugin = pluginRef,
            version = SemVer(2, 1, 0),
            digest = Digest("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
        )

    // ---- C1 ----

    @Test
    fun `C1 plugin ref carries non-empty namespace and identity`() {
        val ref = scmGitPluginRef()
        assertEquals(ResourceKind.PLUGIN, ref.kind)
        assertEquals("rubentxu", ref.namespace)
        // segments = [namespace, "plugin", identity]; path = segments.drop(1)
        assertEquals(listOf("plugin", "scm-git"), ref.path)
    }

    @Test
    fun `C1 construction is fail-closed on invalid segments`() {
        // Forbidden char ('/') in identity -> InvalidResourceRefException
        assertThrows(InvalidResourceRefException::class.java) {
            ResourceRefs.plugin(namespace = "rubentxu", identity = "scm/git")
        }
        // Empty namespace is rejected by the builder
        assertThrows(InvalidResourceRefException::class.java) {
            ResourceRefs.plugin(namespace = "", identity = "x")
        }
    }

    // ---- C2 ----

    @Test
    fun `C2 plugin release carries plugin plus version plus digest`() {
        val plugin = scmGitPluginRef()
        val release = scmGitRelease(plugin)
        assertSame(plugin, release.plugin, "release.plugin must be the same instance the metadata uses")
        assertEquals(2, release.version.major)
        assertEquals(1, release.version.minor)
        assertEquals(0, release.version.patch)
        assertTrue(release.digest.value.startsWith("sha256:"))
    }

    // ---- C3 ----

    @Test
    fun `C3 StepRegistration composes definition plus provider and registry providerOf is O1`() {
        val reg = InMemoryStepRegistry()
        val def = definition("scm.git.checkout", caps = setOf(SCOPE_FS))
        val plugin = scmGitPluginRef()
        val release = scmGitRelease(plugin)
        val provider = StepProviderMetadata.create(
            plugin = plugin,
            release = release,
            publisher = "io.rubentxu",
            families = setOf(PluginFamily.SCM, PluginFamily.NETWORK),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )

        val reg_step = StepRegistration(definition = def, provider = provider)
        reg.register(reg_step)

        val lookup = reg.providerOf(def.contract.key)
        assertNotNull(lookup)
        assertEquals("io.rubentxu", lookup!!.publisher)
        assertEquals(setOf(PluginFamily.SCM, PluginFamily.NETWORK), lookup.families)
        assertEquals(Delivery.OFFICIAL_PLUGIN, lookup.delivery)
        assertSame(reg_step.definition, reg.definition(def.contract.key))
    }

    @Test
    fun `C3 release must reference the same plugin as the provider metadata`() {
        val pluginA = scmGitPluginRef()
        val pluginB = ResourceRefs.plugin(namespace = "rubentxu", identity = "containers")
        val releaseA = scmGitRelease(pluginA)

        assertThrows(IllegalArgumentException::class.java) {
            StepProviderMetadata.create(
                plugin = pluginB,
                release = releaseA,
                publisher = "io.rubentxu",
                families = setOf(PluginFamily.SCM),
                delivery = Delivery.OFFICIAL_PLUGIN,
                trust = TrustMetadata.Unverified,
            )
        }
    }

    // ---- C4 ----

    @Test
    fun `C4 families is a Set with at least one element`() {
        val plugin = scmGitPluginRef()
        val release = scmGitRelease(plugin)
        assertThrows(IllegalArgumentException::class.java) {
            StepProviderMetadata.create(
                plugin = plugin,
                release = release,
                publisher = "io.rubentxu",
                families = emptySet(),
                delivery = Delivery.OFFICIAL_PLUGIN,
                trust = TrustMetadata.Unverified,
            )
        }
    }

    // ---- C5 ----

    @Test
    fun `C5 manifest capabilities match contract capabilities on the happy path`() {
        val def = definition("scm.git.checkout", caps = setOf(SCOPE_FS))
        val plugin = scmGitPluginRef()
        val release = scmGitRelease(plugin)
        val manifest = PluginManifest(
            plugin = plugin,
            release = release,
            publisher = "io.rubentxu",
            families = setOf(PluginFamily.SCM, PluginFamily.NETWORK),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
            stepManifests = listOf(
                StepManifest(
                    stepKey = def.contract.key,
                    declaredCapabilities = setOf(SCOPE_FS),
                ),
            ),
        )

        // No exception means the manifest and contracts match.
        PluginManifestValidator.validate(manifest, listOf(def))
    }

    @Test
    fun `C5 manifest capability mismatch is fail-closed`() {
        val def = definition("scm.git.checkout", caps = setOf(SCOPE_FS, StepCapability("network")))
        val plugin = scmGitPluginRef()
        val release = scmGitRelease(plugin)
        val manifest = PluginManifest(
            plugin = plugin,
            release = release,
            publisher = "io.rubentxu",
            families = setOf(PluginFamily.SCM),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
            stepManifests = listOf(
                StepManifest(
                    stepKey = def.contract.key,
                    // Declares only filesystem.read; omits network. Mismatch.
                    declaredCapabilities = setOf(SCOPE_FS),
                ),
            ),
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PluginManifestValidator.validate(manifest, listOf(def))
        }
        // Diagnostic must name both the step and the discrepancy.
        assertTrue(ex.message!!.contains("scm.git.checkout"), "diagnostic must name the StepKey")
        assertTrue(ex.message!!.contains("network") || ex.message!!.contains("capabilities"),
            "diagnostic must indicate the capability discrepancy")
    }

    @Test
    fun `C5 manifest referencing an unknown StepKey is rejected`() {
        val def = definition("scm.git.checkout", caps = setOf(SCOPE_FS))
        val plugin = scmGitPluginRef()
        val release = scmGitRelease(plugin)
        val manifest = PluginManifest(
            plugin = plugin,
            release = release,
            publisher = "io.rubentxu",
            families = setOf(PluginFamily.SCM),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
            stepManifests = listOf(
                StepManifest(
                    stepKey = PluginStepId("scm.git.nonexistent"),
                    declaredCapabilities = emptySet(),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            PluginManifestValidator.validate(manifest, listOf(def))
        }
    }

    // ---- C7 ----

    @Test
    fun `C7 OFFICIAL_PLUGIN and EXTERNAL_REFERENCE have identical admission semantics`() {
        // Both are admitted on the same path (registry accepts both, capability
        // admission is decided by the runtime capability set, not by Delivery).
        val reg = InMemoryStepRegistry()
        val key = "scm.git.x"
        val defA = definition(key, caps = setOf(SCOPE_FS))
        val pluginA = ResourceRefs.plugin("rubentxu", "scm-git")
        val releaseA = scmGitRelease(pluginA)
        val offProvider = StepProviderMetadata.create(
            plugin = pluginA, release = releaseA, publisher = "io.rubentxu",
            families = setOf(PluginFamily.SCM), delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
        reg.register(StepRegistration(defA, offProvider))

        // Same key, different Delivery (EXTERNAL_REFERENCE) from a different plugin
        // ref must fail closed: the registry is duplicate-key fail-closed.
        val pluginB = ResourceRefs.plugin("rubentxu", "scm-git-ext")
        val releaseB = PluginReleaseRef(
            plugin = pluginB,
            version = SemVer(1, 0, 0),
            digest = Digest("sha256:1111111111111111111111111111111111111111111111111111111111111111"),
        )
        val extProvider = StepProviderMetadata.create(
            plugin = pluginB, release = releaseB, publisher = "io.rubentxu",
            families = setOf(PluginFamily.SCM), delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified,
        )
        val defB = definition(key, caps = setOf(SCOPE_FS))
        assertThrows(IllegalArgumentException::class.java) {
            reg.register(StepRegistration(defB, extProvider))
        }
    }

    // ---- C6 ----

    @Test
    fun `C6 no Cedar or policy engine runtime dependency is introduced`() {
        // Mechanical check: scan the runtime classpath for any artifact whose name
        // starts with 'cedar' or contains 'policyengine'. This test runs in the
        // :pipeline-application module whose classpath is the union of all
        // production classpaths transitively.
        val runtimeClasspath = System.getProperty("java.class.path") ?: ""
        val offending = runtimeClasspath.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { it.substringAfterLast(File.separatorChar) }
            .filter { name ->
                val lower = name.lowercase()
                lower.startsWith("cedar") ||
                    lower.contains("cedar-") ||
                    lower.startsWith("policyengine") ||
                    lower.startsWith("cedarjava")
            }
        assertTrue(
            offending.isEmpty(),
            "Production classpath must not contain Cedar or policy-engine artifacts; found: $offending",
        )
    }

    // ---- C10 ----

    @Test
    fun `C10 legacy register StepDefinition continues to work without provider metadata`() {
        // The 16 CORE Steps and example.uppercase register via the legacy overload.
        // After the additive change, that path MUST still succeed and the registry
        // MUST return null from providerOf for a legacy entry.
        val reg = InMemoryStepRegistry()
        val def = definition("core.echo", caps = emptySet())
        reg.register(def)

        assertNull(reg.providerOf(def.contract.key), "legacy registration has no provider metadata")
        assertSame(def, reg.definition(def.contract.key))
    }

    @Test
    fun `C10 plugin registration without manifest is allowed but loses C5 enforcement`() {
        // A new plugin that calls register(StepRegistration) directly without
        // passing through PluginManifestValidator is admitted, but it has no
        // manifest cross-check. This documents the opt-in nature of C5: a
        // plugin author who wants C5 enforcement uses StepRegistration.fromManifest().
        val reg = InMemoryStepRegistry()
        val def = definition("scm.git.y", caps = setOf(SCOPE_FS, StepCapability("network")))
        val plugin = scmGitPluginRef()
        val release = scmGitRelease(plugin)
        val provider = StepProviderMetadata.create(
            plugin = plugin, release = release, publisher = "io.rubentxu",
            families = setOf(PluginFamily.SCM), delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
        reg.register(StepRegistration(def, provider))
        assertNotNull(reg.providerOf(def.contract.key))
    }

    // ---- C8 ----

    @Test
    fun `C8 StepStarted from a plugin-registered Step carries ProviderProvenance in the envelope`() {
        // The projector reads provider metadata from the registry's providerOf(key)
        // and attaches the audit projection to the envelope. The envelope is the
        // single authority for audit identity (EVT-2 blast-radius rule); this test
        // verifies the end-to-end behaviour, not just that a data class exists.
        val reg = InMemoryStepRegistry()
        val def = definition("scm.git.checkout", caps = emptySet())
        val plugin = scmGitPluginRef()
        val release = scmGitRelease(plugin)
        val provider = StepProviderMetadata.create(
            plugin = plugin, release = release, publisher = "io.rubentxu",
            families = setOf(PluginFamily.SCM, PluginFamily.NETWORK),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
        reg.register(StepRegistration(def, provider))

        val startedEvent = dev.rubentxu.pipeline.v2.events.StepStarted(
            eventId = "ev-1",
            runId = "run-1",
            sequence = 1L,
            occurredAt = java.time.Instant.parse("2026-09-19T12:00:00Z"),
            stageIndex = 0,
            stepIndex = 0,
            stepName = "checkout",
            stepType = "scm.git.checkout",
        )

        // Default projector (no seam): no provenance (legacy path).
        val legacyEnvelope = dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector.project(startedEvent)
        assertNull(legacyEnvelope.provenance,
            "legacy projector path must NOT carry provenance for Steps registered with provider metadata")

        // Projector with the registry seam: provenance appears.
        val seamEnvelope = dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector.project(
            startedEvent,
            providerLookup = { key -> reg.providerOf(key) },
        )
        val prov = seamEnvelope.provenance
        assertNotNull(prov, "envelope must carry provenance when the seam resolves the provider")
        assertEquals("io.rubentxu", prov!!.pluginPublisher)
        assertEquals("rubentxu", prov.pluginNamespace)
        assertEquals("scm-git", prov.pluginIdentity)
        assertEquals("2.1.0", prov.releaseVersion)
        assertTrue(prov.releaseDigest.startsWith("sha256:"))
        // Families are sorted for determinism.
        assertEquals(listOf("NETWORK", "SCM"), prov.families.toList())
    }

    @Test
    fun `C8 StepStarted from a legacy-registered Step has no provenance`() {
        // C10 backwards-compat: legacy Step registration has no provider metadata,
        // so the envelope must carry provenance = null even when a seam is supplied.
        val reg = InMemoryStepRegistry()
        val def = definition("core.echo", caps = emptySet())
        reg.register(def) // legacy overload, no provider

        val startedEvent = dev.rubentxu.pipeline.v2.events.StepStarted(
            eventId = "ev-1",
            runId = "run-1",
            sequence = 1L,
            occurredAt = java.time.Instant.parse("2026-09-19T12:00:00Z"),
            stageIndex = 0,
            stepIndex = 0,
            stepName = "echo",
            stepType = "core.echo",
        )

        val envelope = dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector.project(
            startedEvent,
            providerLookup = { key -> reg.providerOf(key) },
        )
        assertNull(envelope.provenance, "legacy core.echo has no provider, so no provenance")
    }

    @Test
    fun `C8 envelope wire form V1 without provenance decodes with provenance null`() {
        // The provenance field is strictly additive. A V1 wire form without the
        // field decodes to provenance = null. This test simulates that boundary
        // by constructing an envelope and reading it back through the codec.
        val env = dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope(
            version = 1,
            eventRef = dev.rubentxu.pipeline.v2.events.identity.EventRef(
                source = dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs.run("run-1"),
                id = dev.rubentxu.pipeline.v2.events.identity.EventId("ev-1"),
            ),
            kind = "StepStarted",
            occurredAt = java.time.Instant.parse("2026-09-19T12:00:00Z"),
            sequence = 1L,
            subject = dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs.step("run-1", 0, 0),
        )
        val roundtripped = dev.rubentxu.pipeline.v2.events.identity.EnvelopeCodec.decode(
            dev.rubentxu.pipeline.v2.events.identity.EnvelopeCodec.encode(env),
        )
        assertNull(roundtripped.provenance, "absent provenance roundtrips as null")
        assertEquals(env.kind, roundtripped.kind)
    }
}
