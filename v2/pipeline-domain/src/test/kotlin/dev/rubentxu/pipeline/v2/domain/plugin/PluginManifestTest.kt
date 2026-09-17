package dev.rubentxu.pipeline.v2.domain.plugin

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

/**
 * LFC-2E2-PREP — tests for [PluginManifest], [PluginReleaseRef], and the
 * typed admission policy.
 *
 * The manifest is in-memory only (no serialization); the tests focus on
 * construction invariants, capability projection, and admission outcomes.
 */
@Timeout(10)
@DisplayName("LFC-2E2-PREP — PluginManifest + PluginAdmissionPolicy")
class PluginManifestTest {

    private val SHELL_CAP = StepCapability("shellOperations")
    private val EVENT_CAP = StepCapability("eventSink")

    @Test
    fun `manifest with one family declares one capability`() {
        val manifest = PluginManifest(
            coordinate = PluginCoordinate("pipeline.utilities.json"),
            version = PluginVersion(1, 0, 0),
            families = listOf(
                PluginFamily(
                    stepKey = PluginStepId("utilities.readJSON"),
                    requiredCapabilities = setOf(SHELL_CAP),
                ),
            ),
        )
        assertEquals(setOf(SHELL_CAP), manifest.declaredCapabilities)
        assertEquals(listOf(PluginStepId("utilities.readJSON")), manifest.stepKeys)
    }

    @Test
    fun `manifest with multiple families unions declared capabilities`() {
        val manifest = PluginManifest(
            coordinate = PluginCoordinate("pipeline.observability.metrics"),
            version = PluginVersion(0, 2, 1),
            families = listOf(
                PluginFamily(
                    stepKey = PluginStepId("observability.emit"),
                    requiredCapabilities = setOf(EVENT_CAP),
                ),
                PluginFamily(
                    stepKey = PluginStepId("observability.flush"),
                    requiredCapabilities = setOf(EVENT_CAP, SHELL_CAP),
                ),
            ),
        )
        assertEquals(setOf(EVENT_CAP, SHELL_CAP), manifest.declaredCapabilities)
        assertEquals(2, manifest.stepKeys.size)
    }

    @Test
    fun `manifest with duplicate StepKeys is rejected at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            PluginManifest(
                coordinate = PluginCoordinate("pipeline.observability.metrics"),
                version = PluginVersion(0, 2, 1),
                families = listOf(
                    PluginFamily(stepKey = PluginStepId("observability.emit")),
                    PluginFamily(stepKey = PluginStepId("observability.emit")),
                ),
            )
        }
        assertTrue(ex.message!!.contains("duplicate StepKeys"))
    }

    @Test
    fun `manifest with no families and no contributors is rejected`() {
        assertThrows<IllegalArgumentException> {
            PluginManifest(
                coordinate = PluginCoordinate("pipeline.empty"),
                version = PluginVersion(1, 0, 0),
            )
        }
    }

    @Test
    fun `PluginCoordinate validates characters`() {
        assertThrows<IllegalArgumentException> { PluginCoordinate("pipeline bad space") }
        assertThrows<IllegalArgumentException> { PluginCoordinate("") }
        // dot + dash + underscore are allowed
        PluginCoordinate("pipeline.utilities-json_v2")
    }

    @Test
    fun `PluginVersion parse round-trip`() {
        val v = PluginVersion.parse("1.2.3-rc.1+build.42")
        assertNotNull(v)
        assertEquals(1, v!!.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
        assertEquals("rc.1", v.preRelease)
        assertEquals("build.42", v.buildMetadata)
    }

    @Test
    fun `PluginVersion parse rejects malformed`() {
        assertNull(PluginVersion.parse("not.a.version"))
        assertNull(PluginVersion.parse("1.2"))
        assertNull(PluginVersion.parse("-1.2.3"))
    }

    @Test
    fun `PluginVersion toString is canonical semver`() {
        assertEquals("1.2.3", PluginVersion(1, 2, 3).toString())
        assertEquals("0.1.0-rc.1", PluginVersion(0, 1, 0, preRelease = "rc.1").toString())
        assertEquals(
            "1.0.0-beta+build.42",
            PluginVersion(1, 0, 0, preRelease = "beta", buildMetadata = "build.42").toString(),
        )
    }

    @Test
    fun `PluginReleaseRef parse round-trip`() {
        val parsed = PluginReleaseRef.parse("pipeline.utilities.json@1.2.3")!!
        assertEquals(PluginCoordinate("pipeline.utilities.json"), parsed.coordinate)
        assertEquals(PluginVersion(1, 2, 3), parsed.version)
        assertEquals("pipeline.utilities.json@1.2.3", parsed.toString())
    }

    @Test
    fun `PluginReleaseRef rejects malformed`() {
        assertNull(PluginReleaseRef.parse("pipeline.utilities.json")) // no @version
        assertNull(PluginReleaseRef.parse("@1.2.3"))                  // no coordinate
        assertNull(PluginReleaseRef.parse("pipeline.utilities.json@not.a.version"))
    }

    @Test
    fun `PluginReleaseRef toResourceRef is deterministic and uses PLUGIN_RELEASE kind`() {
        val ref = PluginReleaseRef(PluginCoordinate("pipeline.utilities.json"), PluginVersion(1, 2, 3))
        val resource = ref.toResourceRef()
        assertEquals(ResourceKind.PLUGIN_RELEASE, resource.kind)
        assertEquals(listOf("pipeline", "plugin-release", "pipeline.utilities.json", "1.2.3"), resource.segments)
    }

    @Test
    fun `ResourceRefs stepDefinition builder produces STEP_DEFINITION kind`() {
        val ref = ResourceRefs.stepDefinition("utilities.readJSON")
        assertEquals(ResourceKind.STEP_DEFINITION, ref.kind)
        assertEquals(listOf("pipeline", "step-definition", "utilities.readJSON"), ref.segments)
    }

    @Test
    fun `manifest with empty capabilities is admitted trivially`() {
        val manifest = PluginManifest(
            coordinate = PluginCoordinate("pipeline.pure"),
            version = PluginVersion(1, 0, 0),
            families = listOf(PluginFamily(stepKey = PluginStepId("pure.step"))),
        )
        val policy = resolvePluginAdmissionPolicy(manifest, suppliedCapabilities = emptySet())
        assertTrue(policy is PluginAdmissionPolicy.Ready)
    }

    @Test
    fun `manifest with declared but supplied capabilities returns Ready`() {
        val manifest = PluginManifest(
            coordinate = PluginCoordinate("pipeline.shell-user"),
            version = PluginVersion(1, 0, 0),
            families = listOf(
                PluginFamily(
                    stepKey = PluginStepId("shell.run"),
                    requiredCapabilities = setOf(SHELL_CAP),
                ),
            ),
        )
        val policy = resolvePluginAdmissionPolicy(manifest, suppliedCapabilities = setOf(SHELL_CAP, EVENT_CAP))
        assertTrue(policy is PluginAdmissionPolicy.Ready)
        assertEquals(setOf(SHELL_CAP), (policy as PluginAdmissionPolicy.Ready).declaredCapabilities)
    }

    @Test
    fun `manifest with missing capability returns MissingCapabilities`() {
        val manifest = PluginManifest(
            coordinate = PluginCoordinate("pipeline.shell-user"),
            version = PluginVersion(1, 0, 0),
            families = listOf(
                PluginFamily(
                    stepKey = PluginStepId("shell.run"),
                    requiredCapabilities = setOf(SHELL_CAP, EVENT_CAP),
                ),
            ),
        )
        val policy = resolvePluginAdmissionPolicy(manifest, suppliedCapabilities = setOf(SHELL_CAP))
        assertTrue(policy is PluginAdmissionPolicy.MissingCapabilities)
        val missing = policy as PluginAdmissionPolicy.MissingCapabilities
        assertEquals(setOf(EVENT_CAP), missing.missing)
        assertEquals(setOf(SHELL_CAP, EVENT_CAP), missing.declared)
    }

    @Test
    fun `manifestReady convenience function returns boolean from typed result`() {
        val manifest = PluginManifest(
            coordinate = PluginCoordinate("pipeline.event-emitter"),
            version = PluginVersion(1, 0, 0),
            families = listOf(
                PluginFamily(
                    stepKey = PluginStepId("event.emit"),
                    requiredCapabilities = setOf(EVENT_CAP),
                ),
            ),
        )
        assertTrue(manifestReady(manifest, setOf(EVENT_CAP)))
        assertFalse(manifestReady(manifest, setOf(SHELL_CAP)))
    }

    @Test
    fun `PluginFamilyCapabilityFamily classifies a workspace user`() {
        val family = PluginFamily(
            stepKey = PluginStepId("fs.write"),
            requiredCapabilities = setOf(StepCapability("workspaceOperations")),
        )
        assertEquals(
            PluginFamilyCapabilityFamily.WorkspaceUser,
            PluginFamilyCapabilityFamily.classify(family),
        )
    }

    @Test
    fun `PluginFamilyCapabilityFamily classifies a process executor`() {
        val family = PluginFamily(
            stepKey = PluginStepId("shell.run"),
            requiredCapabilities = setOf(SHELL_CAP),
        )
        assertEquals(
            PluginFamilyCapabilityFamily.ProcessExecutor,
            PluginFamilyCapabilityFamily.classify(family),
        )
    }

    @Test
    fun `PluginFamilyCapabilityFamily classifies multiple categories as Mixed`() {
        val family = PluginFamily(
            stepKey = PluginStepId("hybrid"),
            requiredCapabilities = setOf(SHELL_CAP, EVENT_CAP),
        )
        val classified = PluginFamilyCapabilityFamily.classify(family)
        assertTrue(classified is PluginFamilyCapabilityFamily.Mixed)
    }

    @Test
    fun `PluginFamilyCapabilityFamily classifies no capabilities as Pure`() {
        val family = PluginFamily(stepKey = PluginStepId("pure.step"))
        assertEquals(PluginFamilyCapabilityFamily.Pure, PluginFamilyCapabilityFamily.classify(family))
    }
}
