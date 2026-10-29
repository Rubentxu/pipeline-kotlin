package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodySlots
import dev.rubentxu.pipeline.v2.domain.step.SlotResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyContinuation
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions

/**
 * The step-execution spine, out of the run loop.
 *
 * What used to be one class carrying two unrelated jobs is split along the line the
 * coordinator could never draw itself: the run's control flow stays in the coordinator,
 * and "take this Step and make it happen" moves here.
 *
 * The unit of cohesion is the RECURSION. [dispatch] hands a body-bearing Step's body to
 * [CanonicalBodyDispatch] with [dispatchChild] as the single re-entry reference — the same
 * [BodyChildDispatcher] seam [BodyExecutionEngine] already declares — so a body child walks
 * the canonical spine again through exactly one call site; [executeBranchSteps] walks a
 * parallel branch through [dispatch] again. The body's own shape (its declared policy, its
 * projection, its credential preamble and its child loop) lives in [CanonicalBodyDispatch];
 * the per-invocation runtime context is composed by [CanonicalRuntimeContextFactory]. Neither
 * owns the ORDER of the dispatch: the engine keeps the order and performs the effects.
 *
 * ## What decides, and where
 *
 * Every decision this class used to make now has a declared owner. The engine keeps the
 * ORDER and performs the effects; it observes the answers.
 *
 * | decision | owner |
 * |---|---|
 * | preparation — durable identity, control overlay, metadata, fingerprint | [CanonicalDispatchPreparation] |
 * | the runtime context — SAGA ports, body adapter, plugin emitter | [CanonicalRuntimeContextFactory] |
 * | the body — policy, projection, credential preamble, child loop | [CanonicalBodyDispatch] |
 * | reconciliation against the journal — reading the row and deciding about it | [DurableInvocationResolver] |
 * | the durable row of an invocation — admission and terminal write | [DurableInvocationRecord] |
 * | interpretation of a recovery resolution | [RecoveryInterpretationEngine] |
 * | the durable cursor | [CanonicalCursorAdvance] |
 * | the ORDER of the dispatch | this class |
 *
 * [RecoveryInterpretationEngine] is the boundary where a recovery resolution becomes either
 * a settled outcome or a progression to execution. [CanonicalCursorAdvance] is the boundary
 * where an outcome becomes a cursor position, and it is the only place in the application
 * that writes one.
 *
 * ## The engine holds no journal
 *
 * It used to hold the journal port to read the row of the invocation it was about to
 * reconcile, and to write the terminal row of a schema rejection. Both of those are now
 * somebody else's job — the row is read by the resolver that DECIDES about it, and written by
 * [DurableInvocationRecord]. What the engine keeps is the ORDER, and the one thing it still
 * owns on this path: the shape of a schema rejection, which is a terminal SCHEMA outcome in
 * the context the Step left behind.
 *
 * The coordinator keeps only what it alone can do: the run's bookends, the stage loop and
 * the stage-level finalizers.
 */
internal class StepDispatchEngine(
    private val clock: Clock,
    // PREPARACION: durable identity, control overlay, metadata and fingerprint are decided
    // by their owner, which has no clock, no journal and no executor.
    private val dispatchPreparation: CanonicalDispatchPreparation,
    private val typedInputPreparation: DurableTypedInputPreparation,
    private val stepExecutor: DurableStepExecutor,
    private val invocationResolver: DurableInvocationResolver,
    private val recoveryInterpretation: RecoveryInterpretationEngine,
    /**
     * AVANCE DURABLE: the replay cursor is traversal state, and its owner decides whether it
     * moves and performs the write that moves it. The engine asks and observes; it never
     * writes a cursor of its own.
     */
    private val cursorAdvance: CanonicalCursorAdvance,
    private val stepRegistry: StepRegistry?,
    // CONTEXTO: the per-invocation runtime context is composed by its owner. The engine
    // asks for one and observes it; it never wires a SAGA port or an emitter of its own.
    private val runtimeContexts: CanonicalRuntimeContextFactory,
    // CUERPO: the body's own shape is the body dispatch's responsibility. The engine hands
    // it [childReentry] — the ONE reference through which a body child re-enters this spine.
    private val bodyDispatch: CanonicalBodyDispatch,
) {

    /**
     * the single re-entry reference a body child walks the canonical spine through. Built
     * once and handed to the body dispatch on every body, so the engine and every body path
     * share exactly one call site back into [dispatch].
     */
    private val childReentry = BodyChildDispatcher { child, runId, stageName, stageIndex, stepIndex, childShOptions, bodyPath, executionContext ->
        dispatchChild(
            child,
            runId,
            stageName,
            stageIndex,
            stepIndex,
            childShOptions,
            bodyPath,
            executionContext,
        )
    }

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
                            bodyDispatch.dispatchBody(
                                block = step,
                                runId = runId,
                                stageName = stageName,
                                stageIndex = stageIndex,
                                stepIndex = stepIndex,
                                stageShOptions = projection.shOptions,
                                parentBodyPath = bodyPath + attemptSegment,
                                executionContext = projection.context,
                                reentry = childReentry,
                            ),
                        )
                    }
                }
            } else {
                if (step is BlockStepNode) {
                    val body = bodyDispatch.dispatchBody(
                        step,
                        runId,
                        stageName,
                        stageIndex,
                        stepIndex,
                        stageShOptions,
                        bodyPath,
                        executionContext,
                        childReentry,
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
            is DispatchPreparation.Rejected -> return schemaRejection(
                operationId = preparation.operationId,
                input = preparation.input,
                message = preparation.reason,
                context = executionContext,
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

        // The four recovery resolutions are interpreted by
        // RecoveryInterpretationEngine; only Execute — which IS the invocation — stays here.
        // The arms were moved verbatim, including which ones emit lifecycle events and
        // write the journal.
        //
        // the reconciliation is NAMED rather than inlined into the call. Naming it
        // is what lets the cursor decision below see both halves of the fact — the
        // resolution (RecoverRunning or not) and the interpreted outcome (success or not) —
        // without any arm of the interpretation engine having to carry a traversal flag.
        //
        // It also carries the row the decision was made from, so the engine never asks the
        // journal a second question about the same invocation: the reader of that row is
        // the resolver that decided about it.
        val reconciliation = invocationResolver.reconcileInvocation(
            metadata = metadata,
            fingerprint = fingerprint,
            input = input,
            operationId = operationId,
        )
        val interpretation = recoveryInterpretation.interpret(
            reconciliation.resolution,
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
                        resolution = reconciliation.resolution,
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
        val runtime = runtimeContexts.runtimeContext(
            step = step,
            opId = opId,
            runId = runId,
            stageName = stageName,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stageShOptions = stageShOptions,
            bodyContinuation = bodyContinuation,
            bodySlotInvoker = bodySlotInvoker,
            bodies = bodies,
        )

        // +: strategy preparation runs ONLY on actual execution and NEVER
        // produces Step side effects. Selection is by the CLOSED structural family (LegacyCore vs
        // Registry), never by concrete step name. Reuse/divergence/recover never prepare. A
        // Rejected admission (typed field / unsupported command / missing capability) is a
        // terminal SCHEMA rejection (journal FAILED, common executor never runs).
        // extracted to DurableTypedInputPreparation.
        val prepared = when (val typed = typedInputPreparation.prepare(step, runtime)) {
            is DurableTypedInputPreparation.TypedPreparation.Rejected -> return schemaRejection(
                operationId = operationId,
                input = input,
                message = "schema mismatch for step '${step.pluginStepId.value}' on " +
                    "'${step.id.value}': ${typed.reason}",
                context = contextAfterOverlay,
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
            journaled = reconciliation.journaled,
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
     * A terminal SCHEMA rejection, in the context the Step left behind.
     *
     * The row itself is written by the resolver that owns the rejection ([DurableInvocationResolver]
     * records a FAILED row with the rejection's own fingerprint); what the engine owns here is the
     * SHAPE of the refusal: a [CommonExecutionResult] carrying the typed SCHEMA failure, in the
     * context that the rejected Step had reached.
     *
     * The two rejection sites — the structural verdict and the typed-input mismatch — differ in
     * WHAT they reject and in WHICH context they leave behind, and nothing else. They are one
     * helper because they are one decision with two inputs, not two decisions that happen to look
     * alike.
     */
    private fun schemaRejection(
        operationId: String,
        input: OperationInput,
        message: String,
        context: ExecutionContext,
    ): Dispatched = Dispatched(
        CommonExecutionResult(
            outcome = invocationResolver.rejectSchema(operationId, input, message),
        ),
        context,
    )


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

}

