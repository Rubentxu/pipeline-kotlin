package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.PreparedLegacyExecution
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.DurableInvocationResolver
import dev.rubentxu.pipeline.v2.application.durable.DurableStepExecutor
import dev.rubentxu.pipeline.v2.application.durable.ExternalSubprocessRecovery
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.RecoveryInterpretationEngine
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.toStepOutcome
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedOperationResult
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedRegistryCall
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedRegistryInvoker
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path

/**
 * The scripted counterpart of [CoordinatorFixture].
 *
 * ADR-0103 RPL-4 made the scripted invoker a COMPOSER of the canonical authorities rather than
 * an owner of its own replay protocol, so constructing one now means composing four
 * collaborators instead of passing a journal. Eight scripted test classes needed exactly that
 * composition, and duplicating it eight times is how two wirings drift apart.
 *
 * This fixture is the single place a scripted harness wires the resolver, the executor, the
 * recovery interpretation and the registry boundary. It is the same set of collaborators
 * `ScriptedFrontendRunner` composes in production — it is not a mock, a stub, or a second
 * algorithm, and it deliberately keeps the REAL `ExternalSubprocessRecovery` so a scripted
 * `sh` that was RUNNING when the process died behaves the way production behaves.
 */
/**
 * S4-D2 — test-side adapter for the operation-runtime port.
 *
 * S4-D2 made [ScriptedOperationRuntime] return a value AND its canonical outcome. Harnesses that
 * stand in for a shell have to supply both, and the honest way to do that is to run the value
 * through the SAME classifier production uses — not to hardcode an expected outcome next to the
 * result it is supposed to classify. A helper that asserted the outcome would let a test assert
 * its own premise.
 */
internal fun settled(value: ShellInvocationResult): ScriptedOperationResult =
    ScriptedOperationResult(value = value, outcome = value.toStepOutcome())

internal object ScriptedInvokerFixture {
    /**
     * @param controlDirRoot recovery substrate root AND the runtime context's control root.
     *   Defaults to a fresh temp directory because that is what every scripted harness needs:
     *   with `null` the shell substrate has nowhere to run and `core.sh` fails with exit 127,
     *   which looks like a scripted regression and is nothing of the kind. Pass an explicit
     *   `null` to DISARM the external-subprocess hook — that is a deliberate assertion about
     *   recovery, not a default, which is why it is expressed by passing `null` rather than by
     *   omitting the argument.
     */
    fun build(
        registry: StepRegistry,
        journal: OperationJournal,
        eventSink: EventSink = InMemoryEventStore(),
        clock: Clock = SystemClock(),
        controlDirRoot: Path? = Files.createTempDirectory("scripted-fixture-ctrl-"),
        shOptions: ShOptions = ShOptions.EMPTY,
        capabilityAccessFactory: (CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess =
            { CanonicalRuntimeCapabilityAccess(it) },
    ): ScriptedRegistryInvoker = ScriptedRegistryInvoker(
        registry = registry,
        journal = journal,
        eventSink = eventSink,
        metadataResolver = RegistryStepMetadataResolver.composite(registry),
        invocationResolver = DurableInvocationResolver(
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            journal = journal,
            runningSubprocessRecovery = ExternalSubprocessRecovery(clock, controlDirRoot),
        ),
        stepExecutor = DurableStepExecutor(
            eventSink = eventSink,
            executionBoundary = boundaryRoutingThrough(capabilityAccessFactory),
            journal = journal,
        ),
        recoveryInterpretation = RecoveryInterpretationEngine(eventSink, journal),
        capabilityAccessFactory = capabilityAccessFactory,
        runtimeContextFactory = { call -> contextFor(call, shOptions, controlDirRoot, eventSink) },
    )

    /**
     * Why this is not `RegistryExecutionBoundary.adapt(...)`.
     *
     * `adapt` re-derives the capability bridge from the runtime context at EXECUTE time, which
     * is correct in production and wrong for a harness with a synthetic observation source:
     * the harness's `capabilityAccessFactory` was being applied at ADMIT time and then thrown
     * away, so a test that declared "this platform reports SunOS" got the host's real platform
     * at execute time. The two admission sources disagreed, which is the exact defect
     * WU-093 H7-D fixed in production.
     *
     * Routing through the harness's own factory keeps PREPARE and EXECUTE on the same
     * observation source, which is the invariant the boundary is supposed to guarantee. The
     * structural `when` mirrors the production family router and is exhaustive over the two
     * prepared-execution shapes; it never branches on a Step key.
     */
    private fun boundaryRoutingThrough(
        capabilityAccessFactory: (CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess,
    ): CommonExecutionBoundary = CommonExecutionBoundary { prepared, context ->
        when (prepared) {
            is PreparedRegistryExecution -> RegistryExecutionBoundary.coexecute(
                prepared,
                context,
                capabilityAccessFactory,
            )
            is PreparedLegacyExecution -> throw dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation(
                "the scripted registry boundary cannot route a legacy-family PreparedExecution",
            )
        }
    }

    /** Production-like default: the composed core registry. */
    fun buildOverCoreRegistry(
        journal: OperationJournal,
        eventSink: EventSink = InMemoryEventStore(),
        clock: Clock = SystemClock(),
        controlDirRoot: Path? = null,
        shOptions: ShOptions = ShOptions.EMPTY,
    ): ScriptedRegistryInvoker = build(
        registry = CoreStepRegistryFactory.registry(),
        journal = journal,
        eventSink = eventSink,
        clock = clock,
        controlDirRoot = controlDirRoot,
        shOptions = shOptions,
    )

    /**
     * The runtime context the scripted frontend really uses. Mirrors
     * `ScriptedFrontendRunner`: one scripted body is one stage step, so `stageIndex` is 0 and
     * the step index is the invocation ordinal.
     */
    private fun contextFor(
        call: ScriptedRegistryCall,
        shOptions: ShOptions,
        controlDirRoot: Path?,
        eventSink: EventSink,
    ): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId(call.runId, 0, call.invocationOrdinal),
        runId = call.runId,
        stageName = "scripted",
        stageIndex = 0,
        stepIndex = call.invocationOrdinal,
        shOptions = shOptions,
        controlDirRoot = controlDirRoot ?: Files.createTempDirectory("scripted-fixture-ctx-"),
        eventSink = eventSink,
    )
}
