package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/**
 * The per-invocation [CanonicalRuntimeContext], composed by its owner.
 *
 * Composition is not dispatch: the context is a bag of capabilities a handler receives, and
 * who wires the SAGA ports, the body re-entry adapter and the plugin emitter into that bag is
 * a decision about the context, not about the order of the dispatch. Moving it here removes
 * that responsibility from the dispatch spine instead of forwarding to it — the engine asks
 * for a context and observes the answer; it never wires a port of its own.
 *
 * The plugin that owns the dispatched Step is read here for the same reason: it is a fact the
 * context carries (whose promise is this), and the only party that can say it is the registry —
 * the same way the body owner is read. A plugin cannot claim a promise it did not make.
 *
 * It is built only on the execution path: reuse, divergence and recovery never reach it,
 * which is what keeps a replayed Step from acquiring a capability it did not use.
 */
internal class CanonicalRuntimeContextFactory(
    // The registry that says which plugin declared a Step. A read, never a switch on the key.
    private val stepRegistry: StepRegistry?,
    // B11 / W2: the per-run body-reentry adapter, surfaced under
    // BODY_INVOKER_CAPABILITY for any block-step handler that declares it.
    // Fail-closed admission (null bodyInvoker → no capability) is preserved
    // for legacy callers because the field defaults to null on the context.
    private val bodyInvokerAdapter: CanonicalBodyInvokerAdapter,
    // S6.4: the open plugin event registry for THIS run. The emitter is composed here from
    // this registry plus the sink and clock, so one run has one registry shared by every
    // invocation — two registries would let two Steps disagree about what a kind means, and
    // the read-back would resolve against a different one than the write.
    //
    // Empty rather than null by construction: an empty registry refuses every kind, so "a plugin
    // declared nothing" and "nothing is registered" are the same typed refusal, and a Step that
    // reaches for the emission seam is refused at admission instead of reaching a dead handler.
    private val eventRegistry: dev.rubentxu.pipeline.v2.events.registry.EventRegistry =
        dev.rubentxu.pipeline.v2.events.registry.EventRegistry.create(),
    private val eventSink: EventSink,
    private val clock: Clock,
    // The run's output-wake signal, handed to every runtime context so the
    // shell adapter can announce committed chunks to the live observer.
    // Optional so pre-existing construction sites compile; null skips the
    // announce and the observer converges on its poll deadline.
    private val outputWakeups: dev.rubentxu.pipeline.v2.application.observation.WakeupSignal? = null,
    private val controlDirRoot: Path,
    private val workspaceBase: Path?,
    private val secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry?,
    // SAGA: the per-run Saga seam. The PORT itself is built per dispatch, because it
    // carries the forward operation's identity; this binding only carries the run-level facts
    // (the durable store and the lease epoch that fences writes).
    //
    // Null when the run has no durable Saga store: the capability then stays unexposed and a
    // Step that declares it is refused at admission rather than reaching a handler that could
    // not keep the promise it is about to make.
    private val sagaBinding: SagaCompensationBinding? = null,
) {

    /**
     * the runtime context is built here so registry prepare can derive the available
     * capabilities from the capability bridge, never the raw context handed to a handler.
     */
    @Suppress("LongParameterList") // Every argument is named and read once; the alternative
    // would be a holder that exists only to satisfy a counting rule.
    fun runtimeContext(
        step: StepNode,
        opId: OpId,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        bodyContinuation: dev.rubentxu.pipeline.v2.domain.step.BodyContinuation?,
        bodySlotInvoker: BodySlotInvoker?,
        bodies: SagaBodyResolver?,
    ): CanonicalRuntimeContext {
        val ownerPluginId = pluginIdOf(step)
        return CanonicalRuntimeContext(
            opId = opId,
            runId = runId.value,
            stageName = stageName,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            shOptions = stageShOptions,
            controlDirRoot = controlDirRoot,
            eventSink = eventSink,
            outputWakeups = outputWakeups,
            bodyInvoker = bodyInvokerAdapter,
            // bound ONLY for a HANDLER_CONTINUATION Step, and already
            // carrying that Step's own body identity. Null everywhere else, so the
            // fail-closed admission is real: a handler that declares the capability
            // without the engine having bound it never runs.
            bodyContinuation = bodyContinuation,
            // SAGA: the slot seam, bound ONLY for a HANDLER_CONTINUATION Step
            // whose body is already bound. Null everywhere else, so the fail-closed
            // admission is real.
            bodySlotInvoker = bodySlotInvoker,
            secretPatternRegistry = secretPatternRegistry,
            workspaceBase = workspaceBase,
            pluginEventEmitter = pluginEventEmitter(eventRegistry, eventSink, clock),
            // SAGA: the intent port for THIS invocation. Built here, where the forward
            // operation's identity is known, so the compensation an obligation names is the
            // compensation OF that operation and not of some other step in the same run.
            sagaCompensation = sagaBinding?.let { binding ->
                SagaCompensationOperations(
                    runId = runId.value,
                    forwardOpId = opId.format(),
                    leaseEpoch = binding.leaseEpoch,
                    store = binding.store,
                    eventSink = eventSink,
                    clock = clock,
                    ownerPluginId = ownerPluginId,
                    // The bodies this Step owns. The engine measures the body a promise names, so
                    // the promise can be checked against the body that will run.
                    bodies = bodies,
                )
            },
            // SAGA: the plan port for THIS run. It carries no operation identity — what a
            // Saga owes is a property of the Saga, not of the step that happens to ask — so it is
            // bound to the run and its lease epoch, and it reads the same durable rows the intent
            // port writes.
            sagaPlan = sagaBinding?.let { binding ->
                SagaPlanOperationsAdapter(
                    runId = runId.value,
                    leaseEpoch = binding.leaseEpoch,
                    store = binding.store,
                    clock = clock,
                    ownerPluginId = ownerPluginId,
                )
            },
            // SAGA: the compensation RUNTIME for THIS invocation. It needs the Saga store
            // (the loop decides on durable state) and the slot seam (the compensation bodies are
            // children of this Step's own body). Both, or neither: a runtime without a body to
            // compensate through could only stop, and a runtime without durable state could only
            // guess.
            sagaRuntime = if (sagaBinding != null && bodySlotInvoker != null) {
                SagaCompensationInterpreter(
                    runId = runId.value,
                    planOperations = SagaPlanOperationsAdapter(
                        runId = runId.value,
                        leaseEpoch = sagaBinding.leaseEpoch,
                        store = sagaBinding.store,
                        clock = clock,
                        ownerPluginId = ownerPluginId,
                    ),
                    // SAGA (3b-5): the other closing of a Saga — the one that KEEPS the
                    // effects. It reads the same durable rows and writes them with the same lease
                    // epoch, because it is the same authority about the same promises.
                    completion = SagaCompletionAdapter(
                        runId = runId.value,
                        leaseEpoch = sagaBinding.leaseEpoch,
                        store = sagaBinding.store,
                        clock = clock,
                    ),
                    slotInvoker = bodySlotInvoker,
                    // The events are the observable trace of the loop: a compensation that STARTED,
                    // one that FINISHED and the Saga's TERMINAL state. They are announced by the
                    // engine, never by the plugin, and always after the durable write that makes
                    // them true.
                    eventSink = eventSink,
                    clock = clock,
                    // The bodies this Step owns: the compensation bodies are children of this
                    // Step's own body, so this is where the body that is about to run is found.
                    bodies = bodies,
                    // The body each promise is paid with, read from the durable row. The row is
                    // where the promise lives, and the promise is what says which body it names.
                    promisedBodies = SagaPromisedBodyReader { key ->
                        sagaBinding.store.find(runId.value, key)?.bodyDigest
                    },
                )
            } else {
                null
            },
        )
    }

    /**
     * The plugin that owns the Step being dispatched.
     *
     * It is read from the Step's own declaration: the registry says who declared the Step,
     * not a switch on its name.
     */
    private fun pluginIdOf(step: StepNode): String? =
        stepRegistry
            ?.definition(step.pluginStepId)
            ?.contract
            ?.descriptor
            ?.pluginId
}
