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

    @Disabled("Historical S2-A6/G4 snapshot: S2-A9/G5 (2026-09-13) physically removed core.milestone; the 6-key count is superseded by `G5 milestone - core dot writeFile counter post-S2-A9-G5` below. Preserved verbatim for traceability.")
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

    // S2-A9 / G5 (2026-09-13): core.milestone physically removed (LEGACY_REMOVED). The
    // historical S2-A6/G4 snapshot above is preserved verbatim for traceability. The
    // post-S2-A9/G5 counter for core.file.writeFile (still registry-primary) converges to 4.
    @Disabled("Historical S2-A9/G5 snapshot: S2-A10/G4 (2026-09-13) REGISTRY_PRIMARY-flipped core.cleanWs; the 4-key count is superseded by `G4 flip - core dot writeFile stays absent post-S2-A10-G4` below. Preserved verbatim for traceability.")
    @Test
    fun `G5 milestone - core dot writeFile stays absent from LEGACY_PLUGIN_IDS post-S2-A9-G5`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.file.writeFile must remain routed via the registry post-S2-A9/G5 flip of core.milestone",
        )
        assertEquals(
            4,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G5 counter: LEGACY_PLUGIN_IDS converges 6 (post-S2-A6/G4) -> 4 (post-S2-A9/G5)",
        )
    }

    // S2-A10 / G4 (2026-09-13): core.cleanWs flipped to REGISTRY_PRIMARY; legacy decoder
    // branch / dispatcher file / metadata row remain physically present (UNREACHABLE in
    // production) until S2-A10 / G5 closes this lane. Counter converges 4/4/4 -> 3/4/4.
    @Disabled("Historical S2-A10/G4 snapshot: S2-B10/G5 (2026-09-13) physically removed core.archiveArtifacts; the 3-key count is superseded by `G5 LEGACY_REMOVED post-S2-B10-G5 - core dot writeFile stays absent`. Preserved verbatim for traceability.")
    @Test
    fun `G4 flip - core dot writeFile stays absent post-S2-A10-G4`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.file.writeFile must remain routed via the registry post-S2-A10/G4 flip of core.cleanWs",
        )
        assertEquals(
            3,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G4 counter: LEGACY_PLUGIN_IDS converges 4 (post-S2-A9/G5) -> 3 (post-S2-A10/G4)",
        )
    }

    // S2-A10 / G5 (2026-09-13): core.cleanWs legacy forms physically removed (LEGACY_REMOVED).
    // The historical S2-A10/G4 snapshot above is preserved verbatim for traceability. The
    // post-S2-A10/G5 counter converges to 3/3/3 (LEGACY_REMOVED closed).
    @Disabled("Historical S2-A10/G5 snapshot: S2-B10/G5 (2026-09-13) physically removed core.archiveArtifacts; the 3-key count is superseded by `G5 LEGACY_REMOVED post-S2-B10-G5 - core dot writeFile stays absent`. Preserved verbatim for traceability.")
    @Test
    fun `G5 LEGACY_REMOVED - core dot writeFile stays absent post-S2-A10-G5`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.file.writeFile must remain routed via the registry post-S2-A10/G5 LEGACY_REMOVED of core.cleanWs",
        )
        assertEquals(
            3,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G5 counter: LEGACY_PLUGIN_IDS converges 3 (post-S2-A10/G4) -> 3 (post-S2-A10/G5, LEGACY_REMOVED closed)",
        )
    }

    // S2-B10 / G5 (2026-09-13): core.archiveArtifacts legacy forms physically removed
    // (LEGACY_REMOVED). Counter converges 3/3/3 -> 2/2/2. Historical S2-A10/G4 and S2-A10/G5
    // snapshots above preserved verbatim for traceability.
    @Test
    fun `G5 LEGACY_REMOVED post-S2-B10-G5 - core dot writeFile stays absent`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.file.writeFile MUST remain routed via the registry post-S2-B10/G5 LEGACY_REMOVED of core.archiveArtifacts",
        )
        assertEquals(
            2,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G5 counter: LEGACY_PLUGIN_IDS converges 3 (post-S2-A10/G5) -> 2 (post-S2-B10/G5, LEGACY_REMOVED closed)",
        )
        assertEquals(
            setOf("core.load", "core.waitUntil"),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "post-S2-B10/G5: the residual is exactly the two keys still awaiting their own G4/G5 lanes",
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
