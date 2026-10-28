package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialLeaseAdmission
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeCleanup
import dev.rubentxu.pipeline.v2.application.durable.credentials.LeaseAdmission
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodySlots
import dev.rubentxu.pipeline.v2.domain.step.SlotResolution
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.step.BodyContinuation
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import java.util.UUID

/**
 * The step-execution spine, out of the run loop.
 *
 * What used to be one class carrying two unrelated jobs is split along the line the
 * coordinator could never draw itself: the run's control flow stays in the coordinator,
 * and "take this Step and make it happen" moves here.
 *
 * The unit of cohesion is the RECURSION. [dispatch] falls through to [dispatchBody] for a
 * body-bearing Step; [dispatchBody] hands the body back to [BodyExecutionEngine] with
 * [dispatchChild] as the single re-entry reference; [invokeBodyChildren] dispatches every
 * child through that same reference; [executeBranchSteps] walks a parallel branch through
 * [dispatch] again. Splitting any one of these from the others would leave a stub that
 * forwards to a collaborator, which is exactly the indirection the previous shape lacked
 * and this refactor exists to remove. So the whole closed cycle moves as one unit.
 *
 * ## What decides, and where
 *
 * Every decision this class used to make now has a declared owner. The engine keeps the
 * ORDER and performs the effects; it observes the answers.
 *
 * | decision | owner |
 * |---|---|
 * | preparation — durable identity, control overlay, metadata, fingerprint | [CanonicalDispatchPreparation] |
 * | admission — the credential lease | [CredentialLeaseAdmission] |
 * | reconciliation against the journal | [DurableInvocationResolver] |
 * | interpretation of a recovery resolution | [RecoveryInterpretationEngine] |
 * | the durable cursor | [CanonicalCursorAdvance] |
 * | the ORDER of the dispatch | this class |
 *
 * [RecoveryInterpretationEngine] is the boundary where a recovery resolution becomes either
 * a settled outcome or a progression to execution. [CanonicalCursorAdvance] is the boundary
 * where an outcome becomes a cursor position, and it is the only place in the application
 * that writes one.
 *
 * The coordinator keeps only what it alone can do: the run's bookends, the stage loop and
 * the stage-level finalizers.
 */
internal class StepDispatchEngine(
    private val journal: OperationJournal,
    private val eventSink: EventSink,
    private val clock: Clock,
    // ADMISION: the lease of the credentials is decided by its owner, and the engine only
    // observes whether it was admitted. The port itself is not the engine's business.
    private val leaseAdmission: CredentialLeaseAdmission,
    // PREPARACION: durable identity, control overlay, metadata and fingerprint are decided
    // by their owner, which has no clock, no journal and no executor.
    private val dispatchPreparation: CanonicalDispatchPreparation,
    private val typedInputPreparation: DurableTypedInputPreparation,
    private val stepExecutor: DurableStepExecutor,
    private val invocationResolver: DurableInvocationResolver,
    private val recoveryInterpretation: RecoveryInterpretationEngine,
    private val bodyExecutionEngine: BodyExecutionEngine,
    private val bodyPolicyResolver: BodyPolicyResolver,
    // S6.4: the open plugin event registry for THIS run. The engine composes the emitter
    // itself from this registry plus the sink and clock it already holds, so one run has one
    // registry shared by every invocation — two registries would let two Steps disagree about what
    // a kind means, and the read-back would resolve against a different one than the write.
    //
    // Empty rather than null by construction: an empty registry refuses every kind, so "a plugin
    // declared nothing" and "nothing is registered" are the same typed refusal, and a Step that
    // reaches for the emission seam is refused at admission instead of reaching a dead handler.
    private val eventRegistry: dev.rubentxu.pipeline.v2.events.registry.EventRegistry =
        dev.rubentxu.pipeline.v2.events.registry.EventRegistry.create(),
    // The run's output-wake signal, handed to every runtime context so the
    // shell adapter can announce committed chunks to the live observer.
    // Optional so pre-existing construction sites compile; null skips the
    // announce and the observer converges on its poll deadline.
    private val outputWakeups: dev.rubentxu.pipeline.v2.application.observation.WakeupSignal? = null,
    /**
     * AVANCE DURABLE: the replay cursor is traversal state, and its owner decides whether it
     * moves and performs the write that moves it. The engine asks and observes; it never
     * writes a cursor of its own.
     */
    private val cursorAdvance: CanonicalCursorAdvance,
    private val stepRegistry: StepRegistry?,
    private val bodyInvokerAdapter: CanonicalBodyInvokerAdapter,
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
     * a dispatched Step's ATOMIC RESULT together with the context it left behind.
     *
     * This composes the carrier rather than replicating its fields. It used to hold a bare
     * `StepOutcome`, which meant the `encodedOutput` a recovered invocation had just recovered was
     * discarded here — the R14 narrowing, at the last boundary before the consumer. Holding
     * `CommonExecutionResult` keeps both channels available to every consumer, and the ones that
     * only need the outcome now say so explicitly with `.result.outcome`.
     *
     * A convenience `outcome` accessor is deliberately NOT added: while the projections are being
     * made visible, every consumer naming the field it reads is the evidence that no projection is
     * happening by accident.
     */
    data class Dispatched(val result: CommonExecutionResult, val context: ExecutionContext)

    /**
     * SAGA: a slot the handler asked for and the engine cannot honour.
     *
     * A CONTAINED failure, not an exception: the handler's own outcome is where
     * that violation belongs, and an exception would tear the run down instead
     * of reporting it. ENGINE, because the contract was the engine's to keep —
     * the handler named a slot the body does not have.
     */
    private fun slotFailure(message: String): BodyOutcome =
        BodyOutcome.Completed(
            StepOutcome.Failure(
                PipelineFailure(
                    kind = FailureKind.ENGINE,
                    message = message,
                ),
            ),
        )

    /**
     * the runtime context is built here so registry prepare can derive the available
     * capabilities from the capability bridge, never the raw context handed to a handler.
     *
     * It is built only on the execution path: reuse, divergence and recovery never reach it,
     * which is what keeps a replayed Step from acquiring a capability it did not use.
     */
    @Suppress("LongParameterList") // Every argument is named and read once; the alternative
    // would be a holder that exists only to satisfy a counting rule.
    private fun runtimeContext(
        opId: OpId,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        bodyContinuation: dev.rubentxu.pipeline.v2.domain.step.BodyContinuation?,
        bodySlotInvoker: BodySlotInvoker?,
        bodies: SagaBodyResolver?,
        ownerPluginId: String?,
    ): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = opId,
        runId = runId.value,
        stageName = stageName,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        shOptions = stageShOptions,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
        outputWakeups = outputWakeups,
        // B11 / W2: surface the per-run body-reentry adapter under
        // BODY_INVOKER_CAPABILITY for any block-step handler that declares it.
        // Fail-closed admission (null bodyInvoker → no capability) is preserved
        // for legacy callers because the field defaults to null on the context.
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

    suspend fun dispatch(
        step: StepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        bodyPath: List<BlockSegment> = emptyList(),
        executionContext: ExecutionContext = ExecutionContext.EMPTY,
    ): Dispatched {
        // a body-bearing Step is routed by its DECLARED owner, never by its key.
        //
        //   CANONICAL_ENGINE     the engine decides when to run the body. Unchanged since
        // the decoder is bypassed and dispatchBody runs the
        //                         children directly.
        //   HANDLER_CONTINUATION the registered handler runs on the durable spine below
        //                         and reaches the body only through the bound continuation.
        //   LEGACY_LINEAR        refused by the policy resolver inside dispatchBody.
        //
        // Only the first keeps the historical short-circuit. The second deliberately FALLS
        // THROUGH into the registry path, so typed decode, capability admission,
        // StepStarted/StepFinished, typed output, FailureKind, journal, replay and the
        // single execution boundary all come from the existing machinery. There is
        // deliberately no second block-handler execution boundary.
        //
        // An unknown key (owner unresolved) keeps the historical path on purpose: the
        // rejection then comes from the policy resolver inside dispatchBody, exactly as
        // before, rather than from a new check here.
        val bodyContinuation: dev.rubentxu.pipeline.v2.domain.step.BodyContinuation? =
            if (step is BlockStepNode && declaredBodyOwnerOf(step) ==
                dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner.HANDLER_CONTINUATION
            ) {
                BodyContinuation { bodyContext ->
                    // Same derivation the engine-side adapter performs for a core body
                    // (CanonicalBodyInvokerAdapter.open): a per-attempt segment is appended
                    // to the body path so a retried body gets its own durable identity, and
                    // the scope patch is applied by pure derivation without mutating the
                    // caller's context.
                    val attemptSegment = bodyContext.attempt?.let { segment ->
                        listOf(dev.rubentxu.pipeline.v2.domain.BlockSegment(segment.index, segment.key))
                    } ?: emptyList()
                    // The patch is projected onto BOTH surfaces — the context AND the options
                    // the body is launched with — so a body that runs under a patched
                    // environment really runs under it.
                    when (val projection = projectInvocationPatch(executionContext, stageShOptions, bodyContext.patch)) {
                        is InvocationPatchProjection.Refused -> BodyOutcome.Completed(
                            StepOutcome.Failure(
                                PipelineFailure(FailureKind.ENGINE, projection.reason),
                            ),
                        )

                        is InvocationPatchProjection.Applied -> BodyOutcome.Completed(
                            dispatchBody(
                                block = step,
                                runId = runId,
                                stageName = stageName,
                                stageIndex = stageIndex,
                                stepIndex = stepIndex,
                                stageShOptions = projection.shOptions,
                                parentBodyPath = bodyPath + attemptSegment,
                                executionContext = projection.context,
                            ),
                        )
                    }
                }
            } else {
                if (step is BlockStepNode) {
                    val body = dispatchBody(
                        step,
                        runId,
                        stageName,
                        stageIndex,
                        stepIndex,
                        stageShOptions,
                        bodyPath,
                        executionContext,
                    )
                    // R14: a body-only dispatch has no typed value of its own, so the carrier is
                    // built explicitly with a null encoded output rather than narrowed from one.
                    return Dispatched(CommonExecutionResult(outcome = body), executionContext)
                }
                null
            }

        // SAGA: the slot seam.
        //
        // The continuation runs the WHOLE body, which is right for a loop and wrong
        // for a plan with alternatives: a Saga has to choose between the FORWARD
        // body and the COMPENSATE body. This port gives the handler that choice and
        // nothing else — the engine still resolves the slot, still dispatches it and
        // still owns its identity.
        //
        // The dispatched child travels the SAME canonical spine as any other body
        // child, and it carries the path the canonical body loop WOULD have given
        // it (body path + attempt + BlockSegment(index, key)). So the slot's journal
        // rows are the rows that child would have had, and a body dispatched by the
        // engine and a body dispatched by the handler are the same operation.
        // The bodies this Step owns, or `null` when it owns none.
        //
        // A body-bearing Step is a HANDLER_CONTINUATION block, and that is the same condition
        // that binds the slot seam: the compensation bodies ARE this Step's own body
        // children. One question, one answer, and both seams read it.
        val ownedBodies: List<StepNode>? = ownedBodyOf(step)

        val bodySlotInvoker: BodySlotInvoker? =
            if (ownedBodies != null) {
                BodySlotInvoker { slot, slotContext ->
                    when (
                        val resolution = BodySlots.resolve(
                            // The ADDRESS of each child: the name the plugin gave it, or its
                            // kind when nobody named it. So a body that names its children can
                            // hold two of the same kind, and a body that names none behaves
                            // exactly as it did before names existed.
                            ownedBodies.map { it.address },
                            slot,
                        )
                    ) {
                        is SlotResolution.Missing -> slotFailure(
                            "no body slot '${slot.value}' in the body of '${step.pluginStepId.value}'",
                        )

                        is SlotResolution.Ambiguous -> slotFailure(
                            "body slot '${slot.value}' is ambiguous in the body of " +
                                "'${step.pluginStepId.value}': ${resolution.matches} children share that address",
                        )

                        is SlotResolution.Found -> {
                            val childIndex = resolution.childIndex
                            val child = ownedBodies[childIndex]
                            val attemptSegment = slotContext.attempt?.let { segment ->
                                listOf(BlockSegment(segment.index, segment.key))
                            }.orEmpty()
                            val childBodyPath = bodyPath + attemptSegment +
                                BlockSegment(childIndex, child.pluginStepId)
                            // The patch is projected onto BOTH surfaces: the context AND the
                            // options the child is launched with. A body that has to recognise
                            // its own operation key can only do it if the key reaches the
                            // process, and a process is launched with the options.
                            when (
                                val projection = projectInvocationPatch(
                                    executionContext,
                                    stageShOptions,
                                    slotContext.patch,
                                )
                            ) {
                                is InvocationPatchProjection.Refused -> slotFailure(projection.reason)

                                is InvocationPatchProjection.Applied -> {
                                    val outcome = dispatch(
                                        child,
                                        runId,
                                        stageName,
                                        stageIndex,
                                        stepIndex,
                                        projection.shOptions,
                                        childBodyPath,
                                        projection.context,
                                    ).result.outcome
                                    BodyOutcome.Completed(outcome)
                                }
                            }
                        }
                    }
                }
            } else {
                null
            }

        // The bodies this Step owns, so the engine can measure the body a promise names and
        // compare it with the body that is about to run.
        //
        // It exists exactly when the slot seam exists: the compensation bodies ARE this Step's
        // own body children, so a Step without a body has nothing to promise and nothing to
        // measure.
        val bodies: SagaBodyResolver? = ownedBodies?.let { SagaBodyResolver(it) }

        // BlockStepNode bypasses decoder and goes directly to dispatchBody.
        // handles only the body-execution substrate for dir/withEnv/withCredentials
        // timeout/retry. catchError and warnError remain on the legacy linear path
        // (rewriteWorkflowControl) until semantics are implemented.

        // the whole pre-execution prologue is ONE pure decision — durable identity
        // structural verdict, control overlay, metadata and fingerprint. Its OWNER decides it
        // and this engine interprets the answer, so the executor is reachable only under a
        // Ready preparation and a structural rejection can never fall through to typed decode.
        val preparation = dispatchPreparation.prepare(
            step = step,
            coordinates = DispatchCoordinates(
                runId = runId,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                bodyPath = bodyPath,
            ),
            stageShOptions = stageShOptions,
            hasBodyContinuation = bodyContinuation != null,
            executionContext = executionContext,
            now = clock.now().toEpochMilli(),
        )
        val ready = when (preparation) {
            // Terminal SCHEMA rejection (C3/C5): the effective executor is never invoked.
            is DispatchPreparation.Rejected -> return Dispatched(
                CommonExecutionResult(
                    outcome = rejectSchema(preparation.operationId, preparation.input, preparation.reason),
                ),
                executionContext,
            )
            is DispatchPreparation.Ready -> preparation
        }
        val opId = ready.opId
        val operationId = ready.operationId
        val input = ready.input
        val contextAfterOverlay = ready.contextAfterOverlay
        val metadata = ready.metadata
        val fingerprint = ready.fingerprint
        val lifecycleContext = ready.lifecycleContext

        val journaled = journal.get(operationId, 1)
        val currentOperation = RerunOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        // The four recovery resolutions are interpreted by
        // RecoveryInterpretationEngine; only Execute — which IS the invocation — stays here.
        // The arms were moved verbatim, including which ones emit lifecycle events and
        // write the journal.
        //
        // the resolution is NAMED rather than inlined into the call. Naming it
        // is what lets the cursor decision below see both halves of the fact — the
        // resolution (RecoverRunning or not) and the interpreted outcome (success or not) —
        // without any arm of the interpretation engine having to carry a traversal flag.
        val resolution = reconcileInvocation(metadata, journaled, currentOperation, operationId)
        val interpretation = recoveryInterpretation.interpret(
            resolution,
            RecoveryInterpretationEngine.Request(
                operationId = operationId,
                fingerprint = fingerprint,
                input = input,
                lifecycleContext = lifecycleContext,
                // Resolved from the ALREADY-DECODED plugin Step id this node carries, never from a
                // literal: the engine asked the registry a question, and the registry owns the answer.
                // A miss is not fatal here — the interpretation then fails closed with a stated
                // reason, which is the correct answer for a Step that cannot be found.
                definition = stepRegistry?.definition(step.pluginStepId),
                // the durable input is an IDENTITY envelope — `params["payload"]` carries
                // the Step's own encoding, next to the sandbox profile and the body digest that also
                // belong in the fingerprint. Materialisation needs the payload, not the envelope,
                // and the node still holds the exact bytes the compiler produced.
                stepPayload = step.payload.encoded,
            ),
        )
        when (interpretation) {
            is RecoveryInterpretationEngine.RecoveryInterpretation.Settled -> {
                // D7: a recovered success advances the run cursor, and only a recovered
                // success does. The predicate is `is Success`, NOT `advancesCanonicalCursor()`:
                // recovery has always been stricter than execution about Unstable, and D7
                // relocated the cursor without being licensed to change what advances.
                //
                // the projection is now WRITTEN OUT rather than pre-applied by
                // the engine. The interpretation hands back the whole `CommonExecutionResult`, and
                // this consumer asks for the one field it actually needs. That visibility is the
                // point — a consumer that needs `encodedOutput` downstream must be able to see that
                // the carrier carried it, instead of trusting that a bare outcome could not have
                // dropped it on the way out.
                //
                // the RULE of what advances belongs to [CanonicalCursorAdvance]; the engine asks
                // it and performs the write it names.
                cursorAdvance.advance(
                    runId = runId.value,
                    decision = cursorAdvance.decideAfterRecovery(
                        operationId = operationId,
                        stageIndex = stageIndex,
                        resolution = resolution,
                        outcome = interpretation.result.outcome,
                    ),
                )
                return Dispatched(interpretation.result, contextAfterOverlay)
            }
            RecoveryInterpretationEngine.RecoveryInterpretation.ProceedToExecution -> Unit
        }

        // Execute is the ONLY resolution that reaches the effective executor. beginOperation, the
        // StepExecutionBoundary-wrapped executor call, the terminal journal write and cursor advance
        // live here, so the concrete semantics are invoked exclusively under this decision.
        // runtime context is hoisted here so registry prepare can derive the
        // available capabilities from the capability bridge (never the raw context to a handler).
        val runtime = runtimeContext(
            opId = opId,
            runId = runId,
            stageName = stageName,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stageShOptions = stageShOptions,
            bodyContinuation = bodyContinuation,
            bodySlotInvoker = bodySlotInvoker,
            bodies = bodies,
            ownerPluginId = pluginIdOf(step),
        )

        // +: strategy preparation runs ONLY on actual execution and NEVER
        // produces Step side effects. Selection is by the CLOSED structural family (LegacyCore vs
        // Registry), never by concrete step name. Reuse/divergence/recover never prepare. A
        // Rejected admission (typed field / unsupported command / missing capability) is a
        // terminal SCHEMA rejection (journal FAILED, common executor never runs).
        // extracted to DurableTypedInputPreparation.
        val prepared = when (val typed = typedInputPreparation.prepare(step, runtime)) {
            is DurableTypedInputPreparation.TypedPreparation.Rejected -> return Dispatched(
                CommonExecutionResult(
                    outcome = rejectSchema(
                        operationId,
                        input,
                        "schema mismatch for step '${step.pluginStepId.value}' on " +
                            "'${step.id.value}': ${typed.reason}",
                    ),
                ),
                contextAfterOverlay,
            )
            is DurableTypedInputPreparation.TypedPreparation.Ready -> typed.prepared
        }

        // effective execution + durable folding extracted to DurableStepExecutor.
        // The carrier survives the boundary (D5): `encodedOutput` is persisted by
        // the executor AND returned, so no consumer has to re-derive the typed value.
        //
        // D7: the cursor advance lives HERE, after the terminal journal append the executor
        // has already returned from. That ordering is the ReplayCursorStore mitigation
        // and it is structural, not incidental: the append is inside executeAndJournal, the
        // advance is after it returns.
        val execution = stepExecutor.executeAndJournal(
            operationId = operationId,
            fingerprint = fingerprint,
            input = input,
            journaled = journaled,
            prepared = prepared,
            runtime = runtime,
            lifecycleContext = lifecycleContext,
        )
        // the RULE of what advances belongs to [CanonicalCursorAdvance]: the engine asks it for a
        // decision and performs the write the decision names. It does not repeat the predicate.
        cursorAdvance.advance(
            runId = runId.value,
            decision = cursorAdvance.decideAfterExecution(
                operationId = operationId,
                stageIndex = stageIndex,
                outcome = execution.outcome,
            ),
        )
        // R14: `executeAndJournal` already returns the atomic carrier, so this is a COMPOSITION.
        // It used to be `Dispatched(execution.outcome, ...)`, which is the narrowing this whole
        // slice exists to remove: a recovered or a fresh encoded value died at this line.
        return Dispatched(execution, contextAfterOverlay)
    }

    /**
     * Records a FAILED journal row and returns the terminal `SCHEMA` [StepOutcome]; the effective
     * executor is never invoked (C3/C5). Fingerprint uses [ReplayPolicy.RERUN] as on the rejection path.
     */
    private fun rejectSchema(operationId: String, input: OperationInput, message: String): StepOutcome =
        invocationResolver.rejectSchema(operationId, input, message)

    private fun reconcileInvocation(
        metadata: StepMetadata,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        currentOperation: RerunOperation,
        operationId: String,
    ): InvocationReconciliation =
        invocationResolver.reconcileInvocation(metadata, journaled, currentOperation, operationId)


    /**
     * The plugin that owns the Step being dispatched.
     *
     * It is read from the Step's own declaration, the same way the body owner is read: the
     * registry says who declared the Step, not a switch on its name. The engine is the only
     * party that can say it, which is what makes a promise's owner trustworthy: a plugin cannot
     * claim a promise it did not make.
     */
    private fun pluginIdOf(step: StepNode): String? =
        stepRegistry
            ?.definition(step.pluginStepId)
            ?.contract
            ?.descriptor
            ?.pluginId

    /**
     * The body owner DECLARED by the Step's own descriptor, or `null` when the key is unknown
     * to the registry.
     *
     * A pure read of the open registry, never a switch on the key: the routing decision above
     * is driven by what a Step declares about itself, which is the only way an external plugin
     * can obtain a body-bearing route without a core change. `null` means "no declaration to
     * honour", and the caller keeps the historical path so the policy resolver reports the
     * unknown key exactly as it did before.
     */
    private fun declaredBodyOwnerOf(block: BlockStepNode): BodyExecutionOwner? =
        stepRegistry
            ?.definition(block.pluginStepId)
            ?.contract
            ?.descriptor
            ?.body
            ?.declared
            ?.execution
            ?.owner

    /**
     * The body this Step owns, or `null` when it owns none.
     *
     * A body-bearing Step is a `HANDLER_CONTINUATION` block: its handler runs on the durable
     * spine and dispatches its own children. That is the same condition that binds the slot
     * seam, so both seams read this ONE answer instead of asking the question twice.
     */
    private fun ownedBodyOf(step: StepNode): List<StepNode>? {
        if (step !is BlockStepNode) return null
        if (declaredBodyOwnerOf(step) != BodyExecutionOwner.HANDLER_CONTINUATION) return null
        return step.body
    }

    private suspend fun dispatchBody(
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
    ): StepOutcome {
        // B10/W1c: the body family is decided by its DECLARED policy, resolved from the
        // registry BEFORE any effect. No switch on the Step identity and no per-Step case: an
        // unknown, incoherent or unsupported declaration is a typed ENGINE rejection
        // with zero children launched.
        val policy = when (val resolution = bodyPolicyResolver.resolve(block.pluginStepId)) {
            is BodyPolicyResolution.Rejected -> return StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.ENGINE,
                    "body execution policy rejected for '${block.pluginStepId.value}': ${resolution.reason}",
                ),
            )
            is BodyPolicyResolution.Resolved -> resolution.policy
        }
        val projection = try {
            block.projectBodyExecution(policy, stageShOptions)
        } catch (error: IllegalArgumentException) {
            // Platform-level decode guard (e.g. InvalidPathException on a malformed path);
            // the typed decoders above report expected invalid input as a value.
            BodyExecutionProjection.InvalidInput(error.message ?: "invalid body input")
        }
        // no parent capture, no restore — executionContext is the caller's value and stays it.
        var contextInBody = executionContext
        var outcome: StepOutcome = StepOutcome.Success
        val scope = when (projection) {
            // reworked by W1d: the credential lease is a body
            // PREAMBLE, not a path of its own. Acquisition is an effectful preamble and the
            // body then re-enters the engine through the same child loop every other block
            // Step uses. Routed BY POLICY, never by Step name, and never dispatched as an
            // empty shell.
            is BodyExecutionProjection.CredentialLease -> return executeCredentialLeasedBody(
                bindings = projection.bindings,
                block = block,
                runId = runId,
                stageName = stageName,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                stageShOptions = stageShOptions,
                parentBodyPath = parentBodyPath,
                executionContext = executionContext,
            )
            is BodyExecutionProjection.InvalidInput -> return StepOutcome.Failure(
                PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                    "${block.pluginStepId.value}: ${projection.detail}",
                ),
            )
            is BodyExecutionProjection.Unimplemented -> return StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.ENGINE,
                    "body execution shape '${projection.shape}' is declared by " +
                        "'${block.pluginStepId.value}' but not implemented by this engine",
                ),
            )
            is BodyExecutionProjection.Scope -> projection.scope
        }
        val projected = bodyExecutionEngine.projectScope(
            scope, block, runId, stageIndex, stepIndex, stageShOptions, contextInBody,
        )

        // B11 / W2: bind the body-reentry seam by registering the body with the adapter.
        // The runner closure re-enters the canonical shared body loop (`invokeBodyChildren`)
        // so any future block-step handler that declares `BODY_INVOKER_CAPABILITY` can
        // execute its body through the engine exactly as the canonical loop does today.
        // The canonical loop below still drives production execution — the seam is
        // dormant until a registry-driven handler invokes it. The single
        // shared body-child iteration site stays inside `invokeBodyChildren`; this
        // adapter NEVER iterates body children itself.
        //
        // (Phase 1b, 2026-09-18): the runner now also applies the
        // [BodyInvocationContext] it receives to the canonical body execution.
        // `context.attempt?.index` projects onto the per-attempt deterministic
        // bodyPath segment (mirroring the inline retry loop's BlockSegment
        // construction), `context.patch` is projected onto the canonical
        // ExecutionContext through the pure derivation, and `context.decorator`
        // is preserved forward as a runtime fact. Today the canonical
        // coordinator's inline loop still drives production execution directly
        // through `invokeBodyChildren` (no BodyInvoker caller); the seam becomes
        // the application-internal carrier for any future engine that dispatches
        // the body through `BodyInvoker.invoke`. The single shared body-child
        // iteration site stays inside `invokeBodyChildren`; this adapter NEVER
        // iterates body children itself.
        // The scoped-body execution (reentry binding,
        // engine delegation or inline loops, and the bracketed bookends) lives in
        // BodyExecutionEngine; the coordinator composes it with the projected
        // scope decided above. The credential-lease path composes through the
        // credentialLeasedBody callback (this class's own method).
        return bodyExecutionEngine.executeScope3b(
            scope = scope,
            block = block,
            runId = runId,
            stageName = stageName,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            childShOptions = projected.shOptions,
            parentBodyPath = parentBodyPath,
            context = contextInBody,
            dispatcher = ::dispatchChild,
            bodyInvokerAdapter = bodyInvokerAdapter,
        )
    }

    /**
     * The ONE body-child invocation loop (B10 / W1d).
     *
     * Every body-bearing Step re-enters the engine here: a plain scope, a retry attempt and
     * a credential lease all dispatch their children through this function, keyed by the
     * length-prefixed bodyPath (exactly-once). Before W1d there were THREE copies of
     * this loop — one beside [dispatchBody], one in the retry-aware path and one in the
     * credential path — and each copy was a place where a block Step could acquire execution
     * semantics the shared engine did not know about.
     *
     * Semantics: children run in declaration order under [childShOptions] and
     * [executionContext]; the first Failure or Unstable stops the body and is returned;
     * otherwise the body outcome is [StepOutcome.Success].
     */
    private suspend fun invokeBodyChildren(
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        childShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
        leaseBindings: List<CredentialBindingSpec> = emptyList(),
    ): StepOutcome = bodyExecutionEngine.invokeBodyChildren(
        block,
        runId,
        stageName,
        stageIndex,
        stepIndex,
        childShOptions,
        parentBodyPath,
        executionContext,
        leaseBindings,
        BodyChildDispatcher { child, rId, sName, sIdx, stIdx, shOpts, bodyPath, ctx ->
            dispatch(
                child,
                rId,
                sName,
                sIdx,
                stIdx,
                shOpts,
                bodyPath,
                ctx,
            ).result.outcome
        },
    )

    /**
     * Dispatches one body child through the canonical spine and projects its outcome.
     * This is the reference the body execution engine is handed, so every block Step
     * re-enters the engine through exactly this one call site.
     */
    private suspend fun dispatchChild(
        child: StepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        childShOptions: ShOptions,
        bodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
    ): StepOutcome = dispatch(
        child,
        runId,
        stageName,
        stageIndex,
        stepIndex,
        childShOptions,
        bodyPath,
        executionContext,
    ).result.outcome

    /**
     * reworked by W1d — a credential lease as a body PREAMBLE.
     *
     * The acquisition is an effectful preamble that yields an environment overlay; the body
     * itself re-enters the engine through [invokeBodyChildren], the same loop every other
     * block Step uses. Release ALWAYS runs — it is the `finally` of the same attempt that
     * dispatches the body, so it also runs when a child fails — and its typed outcome is
     * folded with the body's outcome by [mergeBodyAndCleanup], a total pure function of two
     * values.
     *
     * Fail-closed: a [LeaseAdmission.Refused] lease returns BEFORE any child is dispatched, so
     * a lease that cannot be acquired never runs its body. The bindings were already decoded
     * by the pure projection (`decodeCredentialBindings`), so a malformed payload never
     * reaches this function.
     *
     * The env overlay reaches children as an immutable derived value: the caller's
     * execution context is not mutated and needs no restore.
     */
    private suspend fun executeCredentialLeasedBody(
        bindings: List<CredentialBindingSpec>,
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
    ): StepOutcome {
        // observability fix: an admission failure here is a REAL observable
        // outcome, not a silent one. Before any StepStarted exists for the leased body,
        // the failure MUST surface as a typed StepFailed event — otherwise external
        // observers see StageStarted -> RunFinished(failure) with empty diagnostics and
        // no way to know the credential lease was rejected.
        // ADMISSION: the owner of the lease decision says whether the lease was admitted and,
        // when it was refused, says the KIND of the refusal and its sentence ONCE. The engine
        // reports that sentence; it does not build it, and it never asks twice for the same one.
        val leased = when (val admission = leaseAdmission.admit(bindings, runId)) {
            is LeaseAdmission.Acquired -> admission.scope
            is LeaseAdmission.Refused -> {
                emitCredentialLeaseAdmissionFailure(
                    runId = runId, stageIndex = stageIndex, stepIndex = stepIndex,
                    stepName = "${block.pluginStepId.value}", stepType = "withcredentials",
                    failureKind = admission.failureKind,
                    message = admission.message,
                )
                return StepOutcome.Failure(
                    PipelineFailure(admission.failureKind, admission.message),
                )
            }
        }
        val childContext = executionContext.pushed(
            ContextOverlay.Environment(
                dev.rubentxu.pipeline.v2.domain.EnvironmentSpec(
                    leased.env.mapValues { (_, handle) -> handle.borrow { bytes -> String(bytes, Charsets.UTF_8) } },
                ),
            ),
        )
        val childShOptions = stageShOptions.copy(env = stageShOptions.env + leased.env)
        // The lease is released on EVERY path (success, typed failure, or an exception out of
        // the body). `cleanup` is a `val` assigned in `finally`: it cannot be read before it
        // holds the real outcome, so there is no default value standing in for a fact the
        // engine has not observed yet. The SEAM is `AcquiredCredentialScope.close()`, which is
        // idempotent and cannot throw: it maps a wipe failure to CredentialScopeCleanup.Failed.
        // `WithCredentialsExecutor.BoundCredentials.close()` underneath it DOES throw, by design,
        // and this is why the seam absorbs it rather than letting it replace the body's outcome.
        val cleanup: CredentialScopeCleanup
        val bodyOutcome = try {
            invokeBodyChildren(
                block = block,
                runId = runId,
                stageName = stageName,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                childShOptions = childShOptions,
                parentBodyPath = parentBodyPath,
                executionContext = childContext,
                leaseBindings = bindings,
            )
        } finally {
            cleanup = leased.close()
        }
        return mergeBodyAndCleanup(bodyOutcome, cleanup)
    }

    /**
     * folds a body outcome and the scope cleanup outcome into a single
     * total StepOutcome per design Cleanup is idempotent and never throws.
     *
     * Total algebra: cleanup Failed over Success, Unstable or Failure always yields a
     * typed operational Failure (INFRASTRUCTURE). Cleaned leaves the body outcome intact.
     */
    private fun mergeBodyAndCleanup(bodyOutcome: StepOutcome, cleanup: CredentialScopeCleanup): StepOutcome =
        when (cleanup) {
            CredentialScopeCleanup.Cleaned -> bodyOutcome
            is CredentialScopeCleanup.Failed -> StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, cleanup.message),
            )
        }

    /**
     * emit a typed [StepFailed] for a credential-lease admission
     * failure. The lease rejection happens BEFORE any child StepStarted, so
     * this event is the only per-step observability the outcome has.
     */
    private fun emitCredentialLeaseAdmissionFailure(
        runId: RunId,
        stageIndex: Int,
        stepIndex: Int,
        stepName: String,
        stepType: String,
        failureKind: dev.rubentxu.pipeline.v2.domain.FailureKind,
        message: String,
    ) {
        eventSink.append(
            dev.rubentxu.pipeline.v2.events.StepFailed(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = clock.now(),
                // The stage this rejection belongs to. The rejection happens before the body's own
                // step starts, so this event is the only thing that says which stage the run
                // stopped in, and it has to say it itself.
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                stepName = stepName,
                stepType = stepType,
                failureKind = failureKind,
                message = message,
            ),
        )
    }
}
