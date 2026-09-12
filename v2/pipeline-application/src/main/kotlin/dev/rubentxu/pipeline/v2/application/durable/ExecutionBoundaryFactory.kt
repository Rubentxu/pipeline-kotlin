package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * S2.5.7 / B1.2c3: named producer seam for the [CommonExecutionBoundary] that a coordinator (or a
 * recording test decorator) routes through.
 *
 * Replaces the inline `if (stepRegistry != null)` previously hidden inside
 * [buildDefaultExecutionBoundary]. Routing logic lives in [FamilyRouter.decide] as an exhaustive
 * sealed ADT; this object only does the structural switch + the optional recorder wrapping.
 *
 * Hexagonal rule: this is an `object` seam, not an injected port. The factory consumes the same
 * inputs as [buildDefaultExecutionBoundary] plus an optional recorder; it never takes a
 * [CommonExecutionBoundary] as an argument it would then route through (that would reintroduce the
 * second-router drift ADR-0066 forbids).
 *
 * Recorder contract: when `recorder != null`, the returned boundary is a pass-through decorator
 * that increments an internal counter, delegates to the structurally-decided boundary, and returns
 * the delegated [StepOutcome] unchanged. The recorder MUST NOT reimplement family routing; if it
 * did, it would silently take over from the productive [SeamedExecutionRouter] authority and break
 * the recorded-vs-productive observation law.
 *
 * The free function [buildDefaultExecutionBoundary] is kept as a forwarder (modified in WU-4) so
 * existing callers — `CoordinatorFixture`, `Main`, the canonical coordinator — keep working
 * unchanged while the inline `if` migrates out.
 */
object ExecutionBoundaryFactory {

    /**
     * Produces the [CommonExecutionBoundary] for a coordinator's effective execution seam.
     *
     * Policy (binary preservation of legacy inline `if (stepRegistry != null)`): when
     * [stepRegistry] is non-null, the factory ALWAYS returns a `SeamedRouting` boundary over the
     * pre-built legacy + registry boundaries via [SeamedExecutionRouter.route] — regardless of
     * whether a [stepKey] is supplied, because reachability is decided at prepare-time inside the
     * registry boundary, not in the factory. When [stepRegistry] is null the factory returns the
     * legacy adapter alone (the dispatcher fallback applies as for the null executor).
     *
     * The seam is named and the structural shape is an exhaustive `when` over
     * [FamilyRoutingDecision]; the binary rule above means [FamilyRoutingDecision.LegacyOnly] only
     * fires when registry is null, and [FamilyRoutingDecision.SeamedRouting] fires when registry is
     * supplied. [FamilyRouter.decide]'s finer-grained step-key check is the optional refinement for
     * future per-step entry points that know the key up front; the canonical coordinator
     * (`CanonicalDurableRunCoordinator`) does not need it.
     *
     * When [recorder] is non-null, wraps the produced boundary in a pass-through decorator that
     * increments its own call counter, invokes the user-supplied [recorder] boundary once per
     * call (so the caller can observe call counts on its own counter), delegates to the produced
     * boundary, and returns the produced boundary's [StepOutcome] unchanged.
     *
     * @param dispatcher canonical node dispatcher used as the legacy-executor fallback when no
     *   [invocationExecutor] is supplied.
     * @param invocationExecutor optional legacy compatibility seam; when null, the dispatcher is
     *   used as the fallback inside the LegacyOnly branch.
     * @param stepRegistry optional step registry; when non-null the factory always produces
     *   `SeamedRouting`.
     * @param stepKey optional [PluginStepId]; currently advisory only — kept in the signature so a
     *   future per-step entry point can plumb it through.
     * @param recorder optional user-supplied observation boundary. When supplied, the returned
     *   boundary delegates to the structurally-decided boundary and also invokes [recorder]
     *   before returning so the caller can observe call counts without taking over routing.
     */
    fun build(
        dispatcher: CanonicalNodeDispatcher,
        invocationExecutor: CanonicalInvocationExecutor?,
        stepRegistry: StepRegistry?,
        stepKey: PluginStepId? = null,
        recorder: CommonExecutionBoundary? = null,
        // S2-A9: milestone state store scoped to the coordinator/run. The coordinator always
        // creates a MilestoneStateStore instance (no longer nullable), so this parameter is
        // always non-null when called from production code. Nullable kept for test/adapter flexibility.
        milestoneStateStore: MilestoneStateStore? = null,
    ): CommonExecutionBoundary {
        // Binary policy preserved bit-a-bit from the original `if (stepRegistry != null)` inline
        // branch. `stepKey` is forwarded to the router for future per-step routing, but does not
        // gate the canonical case (the registry reachability is established at prepare-time).
        if (stepRegistry != null) {
            val legacy = LegacyExecutionAdapter.adapt(
                invocationExecutor ?: CanonicalInvocationExecutor { command, context ->
                    dispatcher.dispatch(command, context)
                },
            )
            // S2-A9 spike: pass milestoneStateStore to RegistryExecutionBoundary so it can
            // populate MILESTONE_OPERATIONS_CAPABILITY when building CanonicalRuntimeCapabilityAccess.
            val registry = RegistryExecutionBoundary.adapt(milestoneStateStore = milestoneStateStore)
            // stepKey is intentionally not consumed here; the canonical coordinator does not know
            // the key at boundary-build time and the registry boundary decides reachability per
            // prepared execution.
            @Suppress("UNUSED_VARIABLE")
            val k = stepKey
            val produced: CommonExecutionBoundary = SeamedExecutionRouter.route(legacy, registry)
            return if (recorder == null) produced else RecordingBoundary(recorder = recorder, delegate = produced)
        }
        val produced: CommonExecutionBoundary = when (val decision = FamilyRouter.decide(
            dispatcher = dispatcher,
            invocationExecutor = invocationExecutor,
            stepRegistry = stepRegistry,
            stepKey = stepKey,
        )) {
            is FamilyRoutingDecision.LegacyOnly -> LegacyExecutionAdapter.adapt(
                invocationExecutor ?: CanonicalInvocationExecutor { command, context ->
                    dispatcher.dispatch(command, context)
                },
            )
            is FamilyRoutingDecision.SeamedRouting -> SeamedExecutionRouter.route(
                decision.legacy,
                decision.registry,
            )
        }
        return if (recorder == null) produced else RecordingBoundary(recorder = recorder, delegate = produced)
    }

    /**
     * Pass-through recorder wrapper: increments an internal call counter, invokes the user-supplied
     * [recorder] so the caller can observe call counts (its own counter), delegates the actual
     * execution to [delegate] (the structurally-decided boundary), and returns the delegated
     * [StepOutcome] unchanged. Does NOT reimplement family routing — if it did, the productive
     * [SeamedExecutionRouter] authority would be silently bypassed.
     *
     * The counter and recorder invocation are purely observational: tests can assert on either
     * `calls` or the user-supplied recorder's counter to prove the boundary was exercised the
     * expected number of times without affecting the structural routing decision.
     */
    private class RecordingBoundary(
        private val recorder: CommonExecutionBoundary,
        private val delegate: CommonExecutionBoundary,
    ) : CommonExecutionBoundary {
        var calls: Int = 0
            private set

        override suspend fun execute(
            prepared: PreparedExecution,
            context: CanonicalRuntimeContext,
        ): CommonExecutionResult {
            calls++
            recorder.execute(prepared, context)
            return delegate.execute(prepared, context)
        }
    }
}