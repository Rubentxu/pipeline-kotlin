package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pre-S3.1 proof that `core.echo` is **LEGACY_UNREACHABLE**.
 *
 * The structural switch on the durable spine is the closed [StructuralStepFamily] token
 * (LegacyCore | Registry), classified by [StructuralFamilyResolver] using
 * [CanonicalCoreStepCommand.ALL_PLUGIN_IDS] as the closed legacy authority and the open
 * [InMemoryStepRegistry] as the registry authority.
 *
 * This test proves the precondition for S3.1:
 *
 * 1. `core.echo` is NOT in the closed legacy authority (it was removed in B1.2c3-S2.2);
 * 2. with a non-null registry, `core.echo` classifies as [StructuralStepFamily.Registry];
 * 3. with the production registry seeded by [CoreStepRegistryFactory], the registry contains
 *    `core.echo` (open world Step semantics);
 * 4. therefore `LegacyExecutionBoundary.prepare(core.echo)` is unreachable on the durable spine
 *    in any production or characterization path that wires a registry — and EVERY path
 *    (production `Main.runCanonicalPipeline`, `CoordinatorFixture`, `PipelineRule`,
 *    `DurableProtocolInvocationCharacterizationTest`) wires [CoreStepRegistryFactory.registry].
 *
 * Once S3.1 removes the `CanonicalCoreStepCommand.Echo` data class, this test must continue
 * to pass: legacy decode + dispatcher can never route `core.echo` because the structural
 * switch classifies it to Registry, never LegacyCore.
 */
class LegacyEchoUnreachableProofTest {

    @Test
    fun `core echo is NOT in the closed legacy authority ALL_PLUGIN_IDS`() {
        assertTrue(
            "core.echo" !in CanonicalCoreStepCommand.ALL_PLUGIN_IDS,
            "core.echo must remain outside the closed legacy authority; regression would resurrect the legacy decode path",
        )
    }

    @Test
    fun `core echo classifies as Registry family when a registry is present`() {
        val registry = CoreStepRegistryFactory.registry()
        val family = StructuralFamilyResolver.classify(PluginStepId("core.echo"), registry)
        assertEquals(
            StructuralStepFamily.Registry,
            family,
            "core.echo with a non-null registry must classify as Registry (structural switch, not legacy)",
        )
    }

    @Test
    fun `production registry contains core echo as an open step definition`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(
            registry.contains(CoreEchoStep.KEY),
            "CoreStepRegistryFactory must register core.echo via the open StepRegistry mechanism",
        )
        assertEquals(
            CoreEchoStep.KEY.value,
            "core.echo",
            "CoreEchoStep.KEY must remain the canonical plugin id",
        )
    }

    @Test
    fun `structural switch never classifies core echo as LegacyCore with the production registry`() {
        // The closed legacy authority (ALL_PLUGIN_IDS) is the ONLY source of LegacyCore classification
        // in the structural switch. Since core.echo is excluded from it, LegacyCore is unreachable for
        // core.echo with a registry. We assert this by checking every name in ALL_PLUGIN_IDS would
        // classify as LegacyCore (NOT core.echo).
        val registry = CoreStepRegistryFactory.registry()
        for (legacyId in CanonicalCoreStepCommand.ALL_PLUGIN_IDS) {
            assertEquals(
                StructuralStepFamily.LegacyCore,
                StructuralFamilyResolver.classify(PluginStepId(legacyId), registry),
                "Legacy authority id '$legacyId' must classify as LegacyCore (closed world invariant)",
            )
        }
        assertNotEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(PluginStepId("core.echo"), registry),
            "core.echo must never classify as LegacyCore (proves the legacy decode path is unreachable)",
        )
    }
}
