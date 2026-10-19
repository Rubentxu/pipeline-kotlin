package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability

/**
 * Named producer seam for the [CommonExecutionBoundary] that a coordinator (or a
 * recording test decorator) routes through.
 * Replaces the inline `if (stepRegistry != null)` previously hidden inside
 * [buildDefaultExecutionBoundary]. Routing logic lives in [FamilyRouter.decide] as an exhaustive
 * sealed ADT; this object only does the structural switch + the optional recorder wrapping.
 * Policy (binary preservation of legacy inline `if (stepRegistry != null)`): when [stepRegistry]
 * is non-null, the factory ALWAYS returns a `SeamedRouting` boundary over the pre-built legacy +
 * registry boundaries via [SeamedExecutionRouter.route] — regardless of whether a [stepKey] is
 * supplied, because reachability is decided at prepare-time inside the registry boundary, not in
 * the factory. When [stepRegistry] is null the factory returns the legacy adapter alone (the
 * dispatcher fallback applies as for the null executor).
 * The seam is named and the structural shape is an exhaustive `when` over
 * [FamilyRoutingDecision]; the binary rule above means [FamilyRoutingDecision.LegacyOnly] only
 * fires when registry is null, and [FamilyRoutingDecision.SeamedRouting] fires when registry is
 * supplied. [FamilyRouter.decide]'s finer-grained step-key check is the optional refinement for
 * future per-step entry points that know the key up front; the canonical coordinator
 * (`CanonicalDurableRunCoordinator`) does not need it.
 * [More info: @rad correctness / behavioural preservation] — the former inline branch was
 * bit-equivalent to the policy below: when `stepRegistry != null`, the factory returns a
 * `SeamedRouting` boundary; otherwise it falls back to a `LegacyOnly` boundary (adapting the
 * provided dispatcher). Routing decisions NEVER depend on the StepKey or any concrete plugin
 * type — the canonical coordinator's structural switch is binary on registry presence, exactly
 * as the original code path was.
 * @param dispatcher canonical node dispatcher used as the legacy-executor fallback when no
 *   [invocationExecutor] is supplied.
 * @param invocationExecutor optional legacy compatibility seam; when null, the dispatcher is
 *   used as the fallback inside the LegacyOnly branch.
 * @param stepRegistry optional step registry; when non-null the factory always produces
 *   `SeamedRouting`.
 * @param stepKey optional [PluginStepId]; currently advisory only — kept in the signature so a
 *   future per-step entry point can plumb it through.
 * @param recorder optional user-supplied observation boundary. When supplied, the returned
 *   boundary delegates to the structurally-decided boundary and also invokes [recorder] once
 *   per call (so the caller can observe call counts on its own counter) and returns the
 *   produced boundary's [StepOutcome] unchanged.
 * @param milestoneStateStore optional run-scoped milestone store. The coordinator always
 *   creates a [MilestoneStateStore] instance (no longer nullable) so this parameter is always
 *   non-null when called from production code. Nullable kept for test/adapter flexibility.
 * Per-run artifact index passed through to the registry
 *   boundary's capability bridge. When null, the capability stays unexposed and the bridge
 *   fails closed at registry-prepare-time.
*/
object ExecutionBoundaryFactory {

    fun build(
        dispatcher: CanonicalNodeDispatcher,
        invocationExecutor: CanonicalInvocationExecutor?,
        stepRegistry: StepRegistry?,
        stepKey: PluginStepId? = null,
        recorder: CommonExecutionBoundary? = null,
        milestoneStateStore: MilestoneStateStore? = null,
        artifactIndex: ArtifactIndexCapability? = null,
        capabilityContributor: RuntimeCapabilityContributor = RuntimeCapabilityContributor { emptyMap() },
    ): CommonExecutionBoundary {
        // Binary policy preserved bit-a-bit from the original `if (stepRegistry != null)` inline
        // branch. `stepKey` is forwarded to the router for future per-step routing, but does not
        // gate the canonical case (the registry reachability is established at prepare-time).
        if (stepRegistry != null) {
            // The legacy canonical command family is LEGACY_REMOVED,
            // so the dispatcher fallback cannot route a real Step. The LegacyOnly boundary is
            // preserved only for binary compatibility: when the legacy fallback is reached WITHOUT
            // an invocationExecutor, it MUST fail closed (FailureKind.ENGINE) instead of returning a
            // silent Success — a silent Success for a step that never ran is a false green. Reaching
            // this path is unreachable in production by design (the canonical coordinator always
            // supplies an executor or a registry-owned key); the typed failure is the audit-trail
            // witness that the invariant holds and the diagnostic for any future regression.
            val legacy = LegacyExecutionAdapter.adapt(
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
            // Thread the artifact index through the registry boundary's capability
            // bridge so core.archiveArtifacts and core.artifact.query share one instance per run.
            val registry = RegistryExecutionBoundary.adapt(
                milestoneStateStore = milestoneStateStore,
                artifactIndex = artifactIndex,
                capabilityContributor = capabilityContributor,
            )
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
            // See the matching comment above. The LegacyOnly fallback
            // returns Failure(ENGINE) when no invocationExecutor is supplied so a step that never
            // ran cannot produce a silent Success; the typed failure names the cause and the
            // expected resolution path. The registry boundary decides reachability per prepared
            // execution.
            is FamilyRoutingDecision.LegacyOnly -> LegacyExecutionAdapter.adapt(
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
