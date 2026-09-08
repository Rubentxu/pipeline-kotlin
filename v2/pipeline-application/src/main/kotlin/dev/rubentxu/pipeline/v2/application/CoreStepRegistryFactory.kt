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
    }
}
