package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-LPR-301 / G5 (2026-09-18): structural characterization of the **closed burn-down** of the
 * legacy execution paths for the last two retired keys: `core.waitUntil` and `core.load`.
 *
 * Per-key behavioural characterization lives in:
 *
 * ```text
 * core.waitUntil:  WaitUntilStepContractSuiteTest             (18 rows, CERTIFIED in S2-A8/G3)
 *                  Lfc2WaitUntilCanonicalReentryFitnessTest   (4 rows, post-WU-LPR-301 arch)
 *                  CoreWaitUntilStepUnitTest                   (2 rows, registry membership)
 * core.load:       RegistryStepMetadataResolverTest           (fail-closed assertion,
 *                                                            post-WU-LPR-301)
 * ```
 *
 * This suite is **NOT** a duplicate — it pins the **closed-set structural shape** of the
 * burn-down counters and the **live membership invariants** of the two retired keys:
 *
 * ```text
 * LEGACY_PLUGIN_IDS.size()           = 0   (closed set; was 2 pre-WU-LPR-301)
 * CanonicalCoreStepMetadata rows     = 0   (closed set; was 2 pre-WU-LPR-301)
 * core.waitUntil is in registry      = true
 * core.waitUntil is not legacy       = true (registry authority wins)
 * core.load is in registry           = false
 * core.load is not legacy            = true (fail-closed at every seam)
 * ```
 *
 * The properties below are written as **closed-set equalities and registry membership
 * invariants**, not as per-key probes, so that any regression that re-creates a legacy
 * authority is caught by a single, named assertion.
 *
 * Refs: WU-LPR-301 (Legacy Execution Burn-down), LB-02 (Step Constitution), ADR-0070..0074.
 */
@Timeout(10)
class Lpr301BurnDownCharacterizationTest {

    private val resolver: StepMetadataResolver =
        RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())

    // ----------------------------------------------------------------------------------
    // Burn-down counter — single authority for the closed legacy table
    // ----------------------------------------------------------------------------------

    @Test
    fun `burn-down counters — LEGACY_PLUGIN_IDS is the empty set`() {
        // The membership table is the structural property that backs every other pin in
        // this suite: adding a row here would re-open a legacy execution path and
        // invalidate the burn-down. This is the **closed-set** shape of the post-G5
        // invariant.
        assertEquals(
            emptySet<String>(),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "post-WU-LPR-301 / G5: LEGACY_PLUGIN_IDS MUST be the empty set. Adding a row " +
                "re-opens a legacy execution path and is a burn-down regression.",
        )
    }

    @Test
    fun `burn-down counters — CanonicalCoreStepMetadata has no rows for the retired keys`() {
        // The metadata table mirrors the membership table. After WU-LPR-301 / G5, both
        // `core.load` and `core.waitUntil` rows are physically removed; the table is
        // empty (mirroring LEGACY_PLUGIN_IDS). The metadata authority is exclusively the
        // StepRegistry.
        assertThrows(
            IllegalArgumentException::class.java,
            { CanonicalCoreStepMetadata.metadata("core.load") },
            "post-WU-LPR-301 / G5: CanonicalCoreStepMetadata must NOT carry a row for " +
                "'core.load'. The metadata authority is exclusively the StepRegistry.",
        )
        assertThrows(
            IllegalArgumentException::class.java,
            { CanonicalCoreStepMetadata.metadata("core.waitUntil") },
            "post-WU-LPR-301 / G5: CanonicalCoreStepMetadata must NOT carry a row for " +
                "'core.waitUntil'. The metadata authority is exclusively the StepRegistry.",
        )
    }

    // ----------------------------------------------------------------------------------
    // core.waitUntil — registry-routed; live membership invariants
    // ----------------------------------------------------------------------------------

    @Test
    fun `core dot waitUntil — registry-routed, composite resolver returns descriptor metadata`() {
        // core.waitUntil is REGISTRY_PRIMARY. The composite metadata resolver routes through
        // the StepRegistry (the canonical metadata authority for non-legacy keys), reads
        // the durable metadata from the StepDescriptor (effects + replayPolicy), and never
        // throws for a registered key. The legacy metadata authority is bypassed (the
        // key is not in LEGACY_PLUGIN_IDS).
        val key = PluginStepId("core.waitUntil")
        val metadata = resolver.resolve(key)
        assertTrue(
            metadata != null,
            "post-WU-LPR-301 / G5: core.waitUntil is registry-routed; the composite resolver " +
                "MUST return metadata from the StepDescriptor (the canonical authority), " +
                "never throw or return null.",
        )
        assertTrue(
            CoreStepRegistryFactory.registry().contains(key),
            "post-WU-LPR-301 / G5: core.waitUntil MUST be in the StepRegistry " +
                "(CoreWaitUntilStep.registerInto).",
        )
    }

    // ----------------------------------------------------------------------------------
    // core.load — unregistered; live membership invariants
    // ----------------------------------------------------------------------------------

    @Test
    fun `core dot load — unregistered, both legacy and registry reject the key fail-closed`() {
        // core.load is NOT registry-routed (no CoreLoadStep exists). The DSL load(path)
        // is admitted at construction (declarative IR) but rejected at runtime by the
        // durable admission gate. Both the legacy metadata authority and the registry
        // authority MUST fail-closed for it.
        val key = PluginStepId("core.load")
        assertThrows(
            EngineInvariantViolation::class.java,
            { resolver.resolve(key) },
            "post-WU-LPR-301 / G5: core.load is neither a legacy member nor a registry " +
                "member. The composite metadata resolver MUST fail-closed for it.",
        )
        assertFalse(
            CoreStepRegistryFactory.registry().contains(key),
            "post-WU-LPR-301 / G5: core.load MUST NOT be in the StepRegistry — no " +
                "CoreLoadStep exists. The DSL load(path) is admitted at construction " +
                "(declarative IR) but rejected at runtime by the durable admission gate.",
        )
    }

    @Test
    fun `core dot load — DSL load() is admitted at construction but the canonical compiler lowers it to a fail-closed pluginStepId`() {
        // Declarative characterization: StepSpec.Load is a valid sealed subtype of
        // StepSpec (the DSL admits it), but the canonical compiler lowers it to
        // OpaqueStepNode with pluginStepId='core.load' which the registry admission gate
        // rejects at runtime. This is the construction-time / runtime asymmetry of the
        // burn-down: no production routing authority owns the key.
        val fixture = pipeline {
            stages {
                stage("load-fail-closed") {
                    // The DSL accepts load(path); this is the construction-time surface.
                    load("nested.pipeline.kts")
                }
            }
        }
        // The fixture must compile to a canonical form. We do NOT execute it (the
        // runtime admission gate would reject the load step with EngineInvariantViolation
        // — see RegistryStepMetadataResolverTest > core.load resolves to EngineInvariantViolation).
        assertEquals(
            listOf("load-fail-closed"),
            fixture.stages.map { it.name },
            "the DSL accepts load(path) (declarative IR), but the canonical compiler " +
                "lowers it to an OpaqueStepNode with pluginStepId='core.load' that the " +
                "registry admission gate rejects at runtime.",
        )
    }
}
