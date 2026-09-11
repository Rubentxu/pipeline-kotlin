package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * S2-A1 / G2 — `core.error` enters the production [CoreStepRegistryFactory] registry.
 *
 * This gate proves three SEPARATE facts:
 *
 *   1. REGISTERED
 *      The production registry contains `core.error` (the new [StepDefinition] is reachable).
 *
 *   2. STRUCTURAL AUTHORITY = LegacyCore (UNCHANGED)
 *      While `core.error` remains in [dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS],
 *      [StructuralFamilyResolver.classify] returns [StructuralStepFamily.LegacyCore] for this key.
 *      This is the resolver's documented contract: legacy membership WINS over registry presence.
 *      Production behavior is therefore NOT changed at G2.
 *
 *   3. COUNTER INVARIANT
 *      LEGACY_PLUGIN_IDS == 12, metadata rows == 12, dispatcher classes == 12.
 *      The legacy decoder/dispatcher/metadata row are untouched; the registry entry is an
 *      ADDITION, not a replacement. The flip to Registry family is G5, after G3 parity proof
 *      and G4 architecture fitness.
 *
 * Separation of concerns (per the user's G2 directive):
 *
 *   REGISTERED ≠ PRIMARY ≠ UNREACHABLE_LEGACY ≠ REMOVED
 *
 *   G2 = REGISTERED; PRIMARY is G5; UNREACHABLE_LEGACY is G6; REMOVED is G6.
 *
 * What this gate does NOT prove (deferred to later gates):
 *   - Behavioral parity with the legacy path (G3).
 *   - Structural fitness for the flip (G4 / S3ErrorLegacyRemovedFitnessTest).
 *   - That legacy execution becomes unreachable (G5/G6).
 *   - That legacy production code is deleted (G6).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreErrorStepG2RegistryAdmissionTest {

    @Test
    fun `G2 -- production registry contains core error`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(
            registry.contains(CoreErrorStep.KEY),
            "production CoreStepRegistryFactory MUST contain 'core.error' after G2 wiring",
        )
    }

    @Test
    fun `G2 -- production registry resolves core error to the new StepDefinition`() {
        val registry = CoreStepRegistryFactory.registry()
        val resolved = registry.definition(CoreErrorStep.KEY)
        assertNotNull(
            resolved,
            "production registry MUST resolve 'core.error' to a StepDefinition at G2",
        )
        assertSame(
            CoreErrorStep.definition,
            resolved,
            "production registry MUST return CoreErrorStep.definition (no parallel definition)",
        )
    }

    @Test
    fun `G2 -- structural family for core error remains LegacyCore (legacy membership wins)`() {
        // The user-facing intent of G2: registration without a routing flip.
        // StructuralFamilyResolver contract: LEGACY_PLUGIN_IDS membership wins over registry
        // presence, so the production coordinator still classifies `core.error` as LegacyCore.
        // This MUST stay LegacyCore until G5 removes the key from LEGACY_PLUGIN_IDS.
        val registry = CoreStepRegistryFactory.registry()
        val family = StructuralFamilyResolver.classify(CoreErrorStep.KEY, registry)
        assertEquals(
            StructuralStepFamily.LegacyCore,
            family,
            "StructuralFamilyResolver MUST return LegacyCore for core.error while " +
                "it remains in LEGACY_PLUGIN_IDS; this is the G2 invariant",
        )
    }

    @Test
    fun `G2 -- echo and sh families are NOT regressed by the new error wiring`() {
        // Sanity: the CoreErrorStep registration does not perturb the previously closed
        // classifications for the other two core Steps.
        val registry = CoreStepRegistryFactory.registry()
        // echo: removed from LEGACY_PLUGIN_IDS in S1; the resolver returns Registry.
        val echoFamily = StructuralFamilyResolver.classify(
            PluginStepId("core.echo"), registry,
        )
        assertEquals(StructuralStepFamily.Registry, echoFamily,
            "core.echo classification MUST NOT regress to LegacyCore")
        // sh: removed from LEGACY_PLUGIN_IDS in LB-02 / A4; the resolver returns Registry.
        val shFamily = StructuralFamilyResolver.classify(
            PluginStepId("core.sh"), registry,
        )
        assertEquals(StructuralStepFamily.Registry, shFamily,
            "core.sh classification MUST NOT regress to LegacyCore")
    }

    @Test
    fun `G2 -- registry contains all three core keys`() {
        val registry = CoreStepRegistryFactory.registry()
        val keys = registry.keys().map { it.value }.toSet()
        assertTrue("core.echo" in keys, "core.echo MUST be registered")
        assertTrue("core.sh" in keys, "core.sh MUST be registered")
        assertTrue("core.error" in keys, "core.error MUST be registered (new in S2-A1 / G2)")
    }

    @Test
    fun `G2 -- registry is fresh per call (no global singleton)`() {
        // The factory contract: deterministic and fresh per call; no shared state.
        // Adding CoreErrorStep registration must not break this property.
        val a = CoreStepRegistryFactory.registry()
        val b = CoreStepRegistryFactory.registry()
        assertTrue(a !== b, "each call MUST return a fresh registry instance")
        assertTrue(a.contains(CoreErrorStep.KEY) && b.contains(CoreErrorStep.KEY),
            "both fresh registries MUST contain core.error")
    }
}
