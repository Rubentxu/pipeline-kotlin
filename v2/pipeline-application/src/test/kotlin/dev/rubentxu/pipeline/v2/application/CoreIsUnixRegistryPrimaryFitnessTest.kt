package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A5 / G4 — REGISTRY_PRIMARY fitness for `core.isUnix`.
 *
 * Proves the eight post-flip properties stated by the user:
 *   1. core.isUnix is registered (G1 candidate alive in the registry)
 *   2. core.isUnix is absent from LEGACY_PLUGIN_IDS (the G4 flip itself)
 *   3. StructuralFamilyResolver classifies core.isUnix as Registry
 *   4. The production registry resolves core.isUnix to CoreIsUnixStep.definition
 *   5. Legacy routing cannot select core.isUnix (no LegacyCore fallback)
 *   6. Registry execution produces typed IsUnixOutput via CoreIsUnixStep
 *   7. PlatformIdentity capability admission is preserved
 *   8. R4B scripted path cross-reference (verified by sibling suites; here we only
 *      assert that the registry descriptor still declares the capability R4B consumes)
 *
 * The fitness deliberately does NOT assert that the legacy forms are gone — that is
 * the G5 work. At G4 the legacy source code (CanonicalIsUnixNodeDispatcher.kt,
 * CanonicalCoreStepCommand.IsUnix subtype, IS_UNIX_PLUGIN_ID decoder branch,
 * CanonicalCoreStepMetadata["core.isUnix"] row) is physically present but unreachable
 * through production routing.
 */
@Timeout(30)
class CoreIsUnixRegistryPrimaryFitnessTest {

    private val key = PluginStepId("core.isUnix")

    private fun factoryRegistry() = CoreStepRegistryFactory.registry()

    @Test
    fun `G4 flip — core dot isUnix is registered in the production registry`() {
        val registry = factoryRegistry()
        assertTrue(
            registry.contains(key),
            "G1 candidate MUST remain registered through the G4 flip",
        )
        assertSame(
            CoreIsUnixStep.definition,
            registry.definition(key),
            "registry MUST resolve core.isUnix to the canonical CoreIsUnixStep.definition",
        )
    }

    @Test
    fun `G4 flip — core dot isUnix is absent from LEGACY_PLUGIN_IDS`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.isUnix MUST be removed from LEGACY_PLUGIN_IDS; flip is the single point of structural change",
        )
        assertEquals(
            7,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G4 counter: LEGACY_PLUGIN_IDS converges 8 (post-S2-A4) -> 7 (post-S2-A5/G4)",
        )
    }

    @Test
    fun `G4 flip — StructuralFamilyResolver classifies isUnix as Registry with the production registry`() {
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(key, factoryRegistry()),
            "core.isUnix MUST route as Registry family post-flip",
        )
    }

    @Test
    fun `G4 flip — legacy routing cannot select isUnix (no LegacyCore fallback)`() {
        // Direct proof: with core.isUnix absent from LEGACY_PLUGIN_IDS, the composite
        // metadata resolver MUST NOT fall back to CanonicalCoreStepMetadata. Asking the
        // legacy row directly still returns a value (G4 keeps it type-loadable), but the
        // production path is the registry descriptor — not the legacy row.
        val resolver = RegistryStepMetadataResolver.composite(factoryRegistry())
        val descriptor = CoreIsUnixStep.definition.contract.descriptor
        // Production resolver returns the registry descriptor (byte-equivalent).
        assertEquals(
            descriptor.effects.toSet(),
            resolver.resolve(key)!!.effects,
        )
        // Resolution via an EMPTY registry fails closed — proves that the legacy row
        // is NOT consulted as a fallback by the production path.
        val emptyRegistry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        val emptyComposite = RegistryStepMetadataResolver.composite(emptyRegistry)
        assertThrows(EngineInvariantViolation::class.java) {
            emptyComposite.resolve(key)
        }
    }

    @Test
    fun `G4 flip — registry execution produces typed IsUnixOutput via CoreIsUnixStep`() {
        // Direct execution path: registry preparation + boundary coexecute must succeed
        // and produce the classifier answer. The legacy dispatcher is NOT called.
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = factoryRegistry(),
                key = key,
                encodedInput = CoreIsUnixStep.definition.contract.inputCodec.encode(
                    IsUnixInput
                ),
                availableCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            )
            val ready = preparation as ExecutionPreparation.Ready
            val ctx = CanonicalRuntimeContext(
                OpId("g4-isUnix", 0, 0),
                "g4-isUnix", "g4", 0, 0,
                ShOptions.EMPTY,
                java.nio.file.Files.createTempDirectory("g4-isUnix"),
                InMemoryEventStore(),
            )
            val result = RegistryExecutionBoundary.coexecute(
                ready.prepared as PreparedRegistryExecution, ctx,
            )
            assertEquals(dev.rubentxu.pipeline.v2.domain.StepOutcome.Success, result.outcome)
            val output = CoreIsUnixStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
            assertNotNull(output, "registry MUST produce a typed IsUnixOutput")
        }
    }

    @Test
    fun `G4 flip — PlatformIdentity capability admission is preserved`() {
        // The contract MUST still declare PLATFORM_IDENTITY; missing capability fails closed.
        assertTrue(
            PLATFORM_IDENTITY_CAPABILITY in
                CoreIsUnixStep.definition.contract.requiredCapabilities,
            "PlatformIdentity capability admission MUST remain declared",
        )
        // No capability -> admission rejected (returns Rejected, handler never runs).
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = factoryRegistry(),
                key = key,
                encodedInput = CoreIsUnixStep.definition.contract.inputCodec.encode(
                    IsUnixInput
                ),
                availableCapabilities = emptySet(),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing capabilities MUST fail closed at prepare-time (no handler execution)",
            )
            // Defensive: the Rejection message names the missing capability.
            val rejected = preparation as ExecutionPreparation.Rejected
            assertTrue(
                rejected.reason.contains(PLATFORM_IDENTITY_CAPABILITY.key),
                "Rejection MUST identify the missing PlatformIdentity capability",
            )
        }
    }

    @Test
    fun `G4 flip — registry descriptor still declares the capability R4B consumes`() {
        // Defensive regression check: the LFC-2R / R4B wiring (ScriptedFrontendRunner,
        // ScriptedRegistryInvoker, ScriptedRuntime) that consumes the isUnix registry
        // seam MUST still see the same capability contract. Sibling suites
        // ScriptedIsUnixRuntimeTest (13/0) and ScriptedRegistryInvokerTest (10/0) prove
        // the wiring end-to-end; here we only assert the contract cross-reference.
        assertTrue(
            CoreIsUnixStep.definition.contract.requiredCapabilities.contains(
                PLATFORM_IDENTITY_CAPABILITY,
            ),
            "registry descriptor still declares the capability R4B consumes",
        )
    }

    @Test
    @org.junit.jupiter.api.Disabled(
        "S2-A5 / G5 historical — G4 row 8 preserved verbatim as REGISTRY_PRIMARY evidence " +
            "in CoreIsUnixRegistryPrimaryFitnessTestRow8Fixture.kt.txt. The original body " +
            "references CanonicalCoreStepCommand.IsUnix and CanonicalIsUnixNodeDispatcher, " +
            "both physically removed at G5 (LEGACY_REMOVED) — would not compile here. " +
            "Source-level absence proof lives in S3IsUnixLegacyRemovedFitnessTest.kt."
    )
    fun `G4 flip — legacy source code is physically present (LEGACY_UNREACHABLE, not LEGACY_REMOVED)`() {
        // G5 historical stub. Verbatim body archived in
        // CoreIsUnixRegistryPrimaryFitnessTestRow8Fixture.kt.txt.
    }
}