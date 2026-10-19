package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * The family-routing decision for a pipeline step's effective execution boundary.
 * Replaces the implicit `if (stepRegistry != null)` inside
 * [buildDefaultExecutionBoundary] with an exhaustive, named, sealed ADT that the future
 * `ExecutionBoundaryFactory.build(...)` will `when`-match.
 * Hexagonal  (strict typed functional design): a sealed ADT, not a boolean coupled with a
 * nullable. Each case carries the data it needs.
 * - [LegacyOnly] means: route this step through the legacy boundary alone. The step is either a
 *   pre-`core.registry` step, or the registry does not own its key.
 * - [SeamedRouting] means: the registry owns the key, so the factory MUST compose the legacy and
 *   the registry boundaries behind the seamed router. Both boundaries are pre-built so the caller
 *   does not re-decide the structural choice.
*/
sealed interface FamilyRoutingDecision {
    /** Use the legacy boundary alone (no registry, or registry does not own the step key). */
    data object LegacyOnly : FamilyRoutingDecision

    /**
     * Registry owns the step key: compose the two structural execution families behind
     * [SeamedExecutionRouter] with the pre-built legacy and registry boundaries.
*/
    data class SeamedRouting(
        val legacy: CommonExecutionBoundary,
        val registry: CommonExecutionBoundary,
    ) : FamilyRoutingDecision
}

/**
 * Pure decision function (no I/O, no `runBlocking`, no clock).
 * Returns [FamilyRoutingDecision.LegacyOnly] when no registry is supplied. When a registry IS
 * supplied AND the caller knows the step key AND the registry owns that key, returns
 * [FamilyRoutingDecision.SeamedRouting] with both pre-built boundaries so the factory only
 * has to apply the seamed router. When a registry is supplied but the key is unknown or the
 * registry does not own it, returns [FamilyRoutingDecision.LegacyOnly] (the seamed router is
 * never applied if the registry would not receive the call).
 * Known limitation: [dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary.adapt]
 * is currently a no-arg factory and does not embed the registry at construction time; the
 * registry reachability is established by the future prepare-time contract and re-checked
 * inside the boundary. This seam only encodes the *family-routing* decision (legacy vs
 * seamed), not the registry reachability check.
*/
object FamilyRouter {
    fun decide(
        dispatcher: CanonicalNodeDispatcher,
        invocationExecutor: CanonicalInvocationExecutor?,
        stepRegistry: StepRegistry?,
        stepKey: PluginStepId? = null,
    ): FamilyRoutingDecision {
        // The legacy canonical command family is LEGACY_REMOVED,
        // so the dispatcher fallback cannot route a real Step. The LegacyOnly boundary is
        // preserved only for binary compatibility: when the legacy fallback is reached WITHOUT
        // an invocationExecutor, it MUST fail closed (FailureKind.ENGINE) instead of returning a
        // silent Success — a silent Success for a step that never ran is a false green. Reaching
        // this path is unreachable in production by design; the typed failure is the audit-trail
        // witness that the invariant holds.
        val legacyBoundary = LegacyExecutionAdapter.adapt(
            invocationExecutor ?: CanonicalInvocationExecutor { _, _ ->
                StepOutcome.Failure(
                    dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                        kind = dev.rubentxu.pipeline.v2.domain.FailureKind.ENGINE,
                        message = "LegacyOnly fallback reached without invocationExecutor; " +
                            "this path is unreachable in production by design — reaching it " +
                            "indicates a programming error in the canonical coordinator",
                    ),
                )
            },
        )

        val registry = stepRegistry ?: return FamilyRoutingDecision.LegacyOnly

        // Without a known step key, the per-step decision happens in the prepare/boundary
        // Layer; the routing decision is LegacyOnly. The factory that needs per-step
        // routing will pass the key explicitly.
        if (stepKey == null) return FamilyRoutingDecision.LegacyOnly

        return if (registry.contains(stepKey)) {
            FamilyRoutingDecision.SeamedRouting(
                legacy = legacyBoundary,
                registry = RegistryExecutionBoundary.adapt(
                    milestoneStateStore = null,
                    artifactIndex = null,
                ),
            )
        } else {
            FamilyRoutingDecision.LegacyOnly
        }
    }
}
