package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A3 / G4 — REGISTRY_PRIMARY fitness for `core.file.writeFile`.
 *
 * Proves that production routing for `core.file.writeFile` now classifies as
 * [StructuralStepFamily.Registry] on every production wiring:
 *
 *  - the key is OUT of `LEGACY_PLUGIN_IDS` (G4 flip),
 *  - `StructuralFamilyResolver.classify` returns Registry with the production registry,
 *  - the production registry resolves the key to the canonical `CoreWriteFileStep.definition`,
 *  - the descriptor metadata is equivalent to the frozen legacy row
 *    (`WRITES_WORKSPACE` + `MEMOIZED`), so fingerprints stay byte-stable across the flip,
 *  - the composite metadata resolver reads the descriptor as the effective authority.
 */
@Timeout(30)
class CoreWriteFileRegistryPrimaryFitnessTest {

    private val key = PluginStepId("core.file.writeFile")

    @Disabled("Historical S2-A5/G4 snapshot: S2-A6/G4 (2026-09-12) flipped core.pwd too; the 7-key counter is superseded by `G4 flip post-S2-A6-G4 - 6 residual legacy keys remain` below. Preserved verbatim for traceability.")
    @Test
    fun `G4 flip — core dot file dot writeFile is NOT in LEGACY_PLUGIN_IDS`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.file.writeFile must be routed via the registry after the G4 flip",
        )
        assertEquals(
            7,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G4->G5 counter: LEGACY_PLUGIN_IDS converges 10 -> 9 (writeFile) -> 8 (emitEvent) -> 7 (isUnix, S2-A5/G5)",
        )
    }

    @Test
    fun `G4 flip post-S2-A6-G4 - 6 residual legacy keys remain`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.file.writeFile must remain routed via the registry post-S2-A6/G4 flip",
        )
        assertEquals(
            6,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G4->G5 counter post-S2-A6/G4: LEGACY_PLUGIN_IDS converges 7 -> 6 (core.pwd, S2-A6/G4)",
        )
    }

    @Test
    fun `G4 flip — StructuralFamilyResolver classifies writeFile as Registry with the production registry`() {
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(key, CoreStepRegistryFactory.registry()),
        )
    }

    @Test
    fun `G4 flip — production registry resolves writeFile to the canonical CoreWriteFileStep definition`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(key))
        assertEquals(
            CoreWriteFileStep.KEY,
            registry.definition(key)?.contract?.key,
        )
    }

    @Test
    fun `G4 flip — descriptor metadata is byte-equivalent to the frozen legacy row`() {
        val descriptor = CoreWriteFileStep.definition.contract.descriptor
        assertEquals(setOf(Effect.WRITES_WORKSPACE), descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, descriptor.replayPolicy)
    }

    @Test
    fun `G4 flip — composite metadata resolver reads the descriptor as effective authority`() {
        val resolver = RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())
        val metadata = resolver.resolve(key)
        assertNotNull(metadata, "production resolver MUST know core.file.writeFile")
        assertEquals(setOf(Effect.WRITES_WORKSPACE), metadata!!.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, metadata.replayPolicy)
    }
}
