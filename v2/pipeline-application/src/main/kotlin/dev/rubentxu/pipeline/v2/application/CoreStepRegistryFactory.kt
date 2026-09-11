package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry

/**
 * Single production authority for the core [StepDefinition]s registered into a [StepRegistry]
 * (B1.2c3-S2.2).
 *
 * The registry is the ONE composition point for Step semantics (closed structure, open Steps). Core
 * registers its public [StepDefinition]s through the SAME mechanism an external plugin uses
 * ([CoreEchoStep] today, more core Steps as they migrate off their legacy dispatch). The coordinator
 * and dispatcher never hardcode which definitions are registered here and never mutate it.
 *
 * Compose external plugin definitions by registering them onto the returned mutable registry at the
 * composition root:
 * ```
 * val registry = CoreStepRegistryFactory.registry()
 * externalPlugin.registerInto(registry)
 * CanonicalDurableRunCoordinator(..., stepRegistry = registry)
 * ```
 *
 * Deterministic and fresh per call; no global/singleton registry. The caller owns the returned
 * registry's lifecycle.
 */
object CoreStepRegistryFactory {

    /** A fresh [StepRegistry] seeded with every registered core [StepDefinition]. */
    fun registry(): InMemoryStepRegistry = InMemoryStepRegistry().apply {
        CoreEchoStep.registerInto(this)
        // LB-02 / A4 WU2: register CoreShellStep alongside CoreEchoStep.
        // After the structural flip (WU3, removal of "core.sh" from LEGACY_PLUGIN_IDS),
        // CoreShellStep is the production routing authority for `sh(...)` invocations.
        // The classifier `StructuralFamilyResolver.classify("core.sh", registry)` returns
        // `Registry` because the key is no longer in the legacy set; this composes the
        // typed input/output codecs and the SHELL_OPERATIONS_CAPABILITY declaration.
        // Single composition authority — no per-call-site wiring.
        CoreShellStep.registerInto(this)
        // LFC-2E1-S2-A1 / G2: register CoreErrorStep alongside CoreEchoStep and CoreShellStep.
        // IMPORTANT: this is REGISTRATION only, not a production routing flip.
        // While "core.error" remains in LEGACY_PLUGIN_IDS, StructuralFamilyResolver.classify
        // returns StructuralStepFamily.LegacyCore for this key (legacy membership wins per
        // the resolver contract). Production behavior is UNCHANGED at G2. The flip to
        // Registry family is G5, after G3 parity proof and G4 architecture fitness.
        // Counter invariant at G2: LEGACY_PLUGIN_IDS == 12, metadata rows == 12,
        // dispatcher classes == 12. The legacy decoder/dispatcher/metadata row are not
        // mutated by this edit — they remain the production authority until G6.
        CoreErrorStep.registerInto(this)
    }
}
