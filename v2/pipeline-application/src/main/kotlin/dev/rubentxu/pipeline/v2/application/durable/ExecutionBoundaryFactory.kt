package dev.rubentxu.pipeline.v2.application.durable

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
     * - Calls [FamilyRouter.decide] with the supplied inputs and exhaustively matches the returned
     *   [FamilyRoutingDecision]. For [FamilyRoutingDecision.LegacyOnly] the factory builds the
     *   legacy boundary directly via [LegacyExecutionAdapter.adapt] (with the same dispatcher/
     *   invocationExecutor fallback the router would use). For [FamilyRoutingDecision.SeamedRouting]
     *   it applies [SeamedExecutionRouter.route] over the pre-built `legacy` and `registry`
     *   boundaries. The `when` is exhaustive: adding a new [FamilyRoutingDecision] variant breaks
     *   the compile, by design.
     * - When [recorder] is non-null, wraps the produced boundary in a pass-through decorator that
     *   increments its own call counter, invokes the user-supplied [recorder] boundary once per
     *   call (so the caller can observe call counts on its own counter), delegates to the produced
     *   boundary, and returns the produced boundary's [StepOutcome] unchanged.
     *
     * @param dispatcher canonical node dispatcher used as the legacy-executor fallback when no
     *   [invocationExecutor] is supplied.
     * @param invocationExecutor optional legacy compatibility seam; when null, the dispatcher is
     *   used as the fallback inside [FamilyRouter.decide] and the LegacyOnly branch.
     * @param stepRegistry optional step registry; when null the decision is always `LegacyOnly`.
     * @param stepKey optional [PluginStepId] consulted by [FamilyRouter.decide] when a registry is
     *   supplied; controls whether `SeamedRouting` is returned.
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
    ): CommonExecutionBoundary {
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

        override suspend fun execute(prepared: PreparedExecution, context: CanonicalRuntimeContext): StepOutcome {
            calls++
            recorder.execute(prepared, context)
            return delegate.execute(prepared, context)
        }
    }
}