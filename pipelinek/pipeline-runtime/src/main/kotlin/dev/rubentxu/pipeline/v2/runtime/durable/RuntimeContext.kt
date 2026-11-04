package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.IdGenerator
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/**
 * Runtime dependencies required by the canonical core step dispatcher.
 * [bodyInvoker] is the / seam: a per-run [BodyInvokerAdapter] that re-enters
 * the canonical body machinery (`invokeBodyChildren`) for block-step handlers that declare
 * `BODY_INVOKER_CAPABILITY`. Defaulted to `null` so every pre-existing constructor site
 * (legacy dispatch, registry-aware dispatch, scripted runners, and ~25 unit tests)
 * compiles bit-equivalent. When `null` the capability bridge does NOT register
 * `BODY_INVOKER_CAPABILITY` — admission fails closed for any handler that declares it,
 * which is the correct behaviour: only runs that explicitly wire the adapter expose
 * the body-reentry seam.
 * The data class is the canonical seam between the dispatch body and the runtime
 * adapters; giving the field bundle its own file gives it its own focus.
*/
data class RuntimeContext(
    val opId: OpId,
    val runId: String,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val shOptions: ShOptions,
    val controlDirRoot: Path,
    val eventSink: EventSink,
    // The run's clock and the run's source of identifiers: the TWO ambient reads the
    // engine is forbidden to perform on its own.
    //
    // They ride here for the same reason [eventSink] does: this context is already the
    // per-run carrier that the four capability adapters share, and an adapter that read
    // the machine's clock or drew from the process's entropy would be a SECOND source of
    // time and identity next to the engine's — the very defect the injection removes.
    //
    // They are required, not defaulted: a context without them could not say when it was
    // built, and a default would be an ambient read wearing a default's clothes.
    val clock: Clock,
    val idGenerator: IdGenerator,
    val bodyInvoker: BodyInvokerAdapter? = null,
    // The body-ALREADY-BOUND continuation of a Step that declared
    // BodyExecutionOwner.HANDLER_CONTINUATION, supplied under
    // BODY_CONTINUATION_CAPABILITY to that Step's handler only.
    // Distinct from [bodyInvoker] on purpose, and not a wrapper around it: the
    // continuation already carries the engine-issued BodyRef, so the handler never
    // sees an identity it could correlate, derive or outlive. Null for every other
    // Step (and for every pre-existing construction site), which is what keeps the
    // fail-closed admission honest: a handler that declares the capability never
    // runs when the engine did not bind it.
    val bodyContinuation: dev.rubentxu.pipeline.v2.domain.step.BodyContinuation? = null,
    // The secret pattern registry,
    // threaded to the shell-operations adapter so the console transcript is
    // redacted chunk-boundary-safe BEFORE it reaches the observable plane.
    // Optional (null → no transcript redaction) so legacy callers/tests that
    // construct the context directly keep compiling; the production wire-up
    // in DurableRunCoordinator always supplies it.
    val secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    // The run's output-wake signal, shared with the run's live observer.
    // The shell adapter announces on it after each committed output chunk;
    // the observer parks on it instead of sleeping its full poll interval.
    // Null (and every pre-existing construction site) means no observer
    // announced interest, and the announce is skipped — an observer without
    // a signal still converges on its poll deadline.
    val outputWakeups: dev.rubentxu.pipeline.v2.runtime.observation.WakeupSignal? = null,
    // Optional project-workspace override (--workspace <dir>). When set,
    // stage workspaces resolve under <dir> instead of <controlRoot>/workspace, so real
    // project fixtures (Gradle/Maven/Node) execute against the actual project files.
    val workspaceBase: java.nio.file.Path? = null,
    // The open event registry composed from plugin contributors, already bound to
    // THIS run's sink and clock as a [dev.rubentxu.pipeline.v2.events.registry.RegistryEventEmitter].
    // It rides here rather than as a constructor parameter of
    // [RuntimeCapabilityAccess] on purpose. That class is constructed at FOUR sites —
    // two of them prepare-time — and admission has to see exactly the keys the handler will get,
    // so a per-call parameter would have to be threaded through all four or the Step would be
    // admitted for a capability it never receives. The context is already the per-run carrier that
    // all four share, and it already carries the [eventSink] half of this pair.
    // Null when no contributor declared anything (and for every pre-existing construction site),
    // so the capability stays unexposed and a Step that declares it is refused at admission
    // instead of reaching a handler with a null seam.
    val pluginEventEmitter: dev.rubentxu.pipeline.v2.events.registry.RegistryEventEmitter? = null,
    // The seam a plugin observes ITS OWN work through, bound to THIS
    // invocation.
    //
    // It is built per dispatch, not per run, because it carries the parent:
    // the observation a plugin opens has to hang under the span of the step
    // the plugin was admitted for, and that span exists only for the
    // dispatch in flight. A per-run seam would have no parent to name, and a
    // plugin's observation would float beside the run instead of inside it.
    //
    // Null only for a context composed WITHOUT the engine's binding — every
    // pre-existing construction site — so the capability stays unexposed and a
    // Step that declares it is refused at admission instead of reaching a
    // handler with a seam that cannot keep its promise. A run that asked for
    // no telemetry is not one of those cases: the engine binds the seam that
    // observes nothing, so a Step that declares the capability is admitted
    // and runs, and the run publishes nothing.
    val stepObservability: dev.rubentxu.pipeline.v2.runtime.telemetry.StepObservability? = null,
    // The compensation intent port, bound to THIS invocation.
    //
    // It is built per dispatch, not per run, because it carries the forward operation's
    // identity: the compensation an obligation names is the compensation OF that operation.
    // Null when the run has no durable Saga store wired (and for every pre-existing
    // construction site), so the capability stays unexposed and a Step that declares
    // SAGA_COMPENSATION_CAPABILITY is refused at admission instead of reaching a handler
    // with a port that cannot keep a promise.
    val sagaCompensation: dev.rubentxu.pipeline.v2.domain.saga.CompensationOperations? = null,
    // The compensation PLAN port, bound to THIS run.
    //
    // It answers "what does the Saga owe now?" and records what the plugin observed about the
    // effects it triggered. It is a separate port from the intent one because making a promise
    // and moving a promise along its lifecycle are different authorities.
    //
    // Null when the run has no durable Saga store wired (and for every pre-existing
    // construction site), so the capability stays unexposed and a Step that declares
    // SAGA_PLAN_CAPABILITY is refused at admission instead of reaching a handler that cannot
    // keep the state it is about to write.
    val sagaPlan: dev.rubentxu.pipeline.v2.domain.saga.SagaPlanOperations? = null,
    // The compensation RUNTIME, bound to THIS invocation.
    //
    // It is the engine's loop: ask the durable state what is owed, dispatch the body that
    // owes it, record what happened, ask again. The plugin hands over the plan (data) and
    // gets back what the engine decided and in which order.
    //
    // Null unless the run has a durable Saga store AND this Step owns a body to compensate
    // through, so a Step that declares SAGA_RUNTIME_CAPABILITY without both is refused at
    // admission instead of reaching a handler that could not keep the promise.
    val sagaRuntime: dev.rubentxu.pipeline.v2.domain.saga.SagaRuntime? = null,
    // The slot seam. A handler whose body is already bound may run ONE
    // named child of that body, chosen by it, and the engine dispatches it through the
    // same canonical spine — so the slot's durable identity is the identity it would
    // have had if the engine had dispatched it.
    //
    // Null for every other Step (and for every pre-existing construction site), which
    // is what keeps the fail-closed admission honest: a handler that declares
    // BODY_SLOT_CAPABILITY without the engine having bound it never runs.
    val bodySlotInvoker: dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker? = null,
)
