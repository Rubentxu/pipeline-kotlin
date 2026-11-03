package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.application.LiveSpanNames
import dev.rubentxu.pipeline.v2.application.LiveSpanStatus
import dev.rubentxu.pipeline.v2.application.LiveSpans
import dev.rubentxu.pipeline.v2.application.MetricBoundary
import dev.rubentxu.pipeline.v2.application.MetricOutcome
import dev.rubentxu.pipeline.v2.application.MetricStatus
import dev.rubentxu.pipeline.v2.application.PipelineTelemetry
import dev.rubentxu.pipeline.v2.application.SpanScope
import dev.rubentxu.pipeline.v2.application.liveSpanParentOf
import dev.rubentxu.pipeline.v2.application.liveStatusOfStepOutcome
import dev.rubentxu.pipeline.v2.application.metricOutcomeOf
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
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
 * | preparation — durable identity, metadata, fingerprint | [CanonicalDispatchPreparation] |
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
    // PREPARACION: durable identity, metadata and fingerprint are decided
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
    // The live tracing seam. The dispatch is one of the boundaries a trace
    // has to show, and the engine is the ONE place that knows whether the
    // step's effect ran, was refused, or was skipped by a replay.
    private val telemetry: PipelineTelemetry,
) : StepDispatcher {

    /**
     * the single re-entry reference a body child walks the canonical spine through. Built
     * once and handed to the body dispatch on every body, so the engine and every body path
     * share exactly one call site back into [dispatch].
     */
    private val childReentry = BodyChildDispatcher { child, runId, stageName, stageIndex, stepIndex, childShOptions, bodyPath, spanScope ->
        dispatchChild(
            child,
            runId,
            stageName,
            stageIndex,
            stepIndex,
            childShOptions,
            bodyPath,
            spanScope,
        )
    }

    /**
     * The ONE public entry point of the spine.
     *
     * The request carries the coordinates of the dispatch, and this method is
     * the only way a consumer reaches the engine. Everything else in this
     * class is the machinery that serves it, and the machinery is private on
     * purpose: a consumer that could call the pieces could also call them
     * out of order, and the ORDER is what this engine owns.
     */
    override suspend fun dispatch(request: StepDispatchRequest): DispatchedStep = dispatchStep(request)

    /**
     * SAGA: a slot the handler asked for and the engine cannot honour.
     *
     * The slot seam has ONE owner ([SagaSlotDispatcher]), and this engine no longer decides
     * what a missing or ambiguous slot answers: the dispatcher owns that answer, because it
     * owns the resolution of the address. What the engine owns is the OTHER half — the single
     * re-entry reference the dispatcher walks the child through — so the slot's child travels
     * the same spine as every other body child instead of opening a second path into it.
     */
    private suspend fun dispatchStep(request: StepDispatchRequest): DispatchedStep {
        val step = request.step
        val runId = request.runId
        val stageName = request.stageName
        val stageIndex = request.stageIndex
        val stepIndex = request.stepIndex
        val stageShOptions = request.shOptions
        val bodyPath = request.bodyPath
        val spanScope = request.spanScope
        // The dispatch span: the boundary between the decision to run a step
        // and its result. It is opened here, at the ONE place every step
        // reaches, so there is no second spelling of the same boundary.
        val span = LiveSpans.open(
            telemetry = telemetry,
            name = LiveSpanNames.STEP_DISPATCH,
            parent = spanScope.parent,
            attributes = listOf(
                LiveSpanAttributes.runId(runId.value),
                LiveSpanAttributes.stageIndex(stageIndex),
                LiveSpanAttributes.stepIndex(stepIndex),
            ),
        )
        // Everything this dispatch causes hangs under THIS span, not under
        // whatever caused the dispatch. The body's children, the effect and
        // the recovery are children of the step that was dispatched, which is
        // the truth about what caused them.
        val innerScope = spanScope.withParent(liveSpanParentOf(span))
        // How the dispatch ended, for the span. `spanStatus` is the status the
        // span is closed with; `spanSkipped` says the step's effect was NOT
        // produced by this run, which is the one case where the span is
        // DISCARDED instead of ended.
        var spanStatus: LiveSpanStatus? = null
        var spanSkipped: Boolean = false
        // How the dispatch ended, for the metric. The metric separates a
        // REFUSAL from a FAILURE: a step that was turned away by the admission
        // never ran, and a counter that merges the two tells an operator that
        // the pipeline broke when it did not.
        var metricOutcome: MetricOutcome? = null
        // The dispatch boundary, measured at the ONE place every step reaches.
        val dispatchMeasurement = telemetry.metrics.start(MetricBoundary.StepDispatch)

        fun terminal(dispatched: DispatchedStep): DispatchedStep {
            spanStatus = liveStatusOfStepOutcome(dispatched.result.outcome)
            metricOutcome = metricOutcomeOf(dispatched.result.outcome)
            return dispatched
        }

        fun refuse(dispatched: DispatchedStep): DispatchedStep {
            spanStatus = LiveSpanStatus.Rejected
            metricOutcome = MetricOutcome(MetricStatus.AdmissionRefused)
            return dispatched
        }

        try {
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
                        when (val projection = projectInvocationPatch(innerScope.ambient, stageShOptions, bodyContext.patch)) {
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
                                    spanScope = innerScope.withAmbient(projection.context),
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
                            innerScope,
                            childReentry,
                        )
                        // A body-only dispatch has no typed value of its own, so the carrier is
                        // built explicitly with a null encoded output rather than narrowed from one.
                        return terminal(DispatchedStep(CommonExecutionResult(outcome = body), innerScope.ambient))
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
                    SagaSlotDispatcher(
                        ownedBodies = ownedBodies,
                        ownerName = step.pluginStepId.value,
                        reentry = childReentry,
                    ).invoker(
                        SagaSlotRequest(
                            runId = runId,
                            stageName = stageName,
                            stageIndex = stageIndex,
                            stepIndex = stepIndex,
                            bodyPath = bodyPath,
                            stageShOptions = stageShOptions,
                            spanScope = innerScope,
                        ),
                    )
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
            // It handles every body-execution family: dir/withEnv/withCredentials,
            // timeout/retry, and the control scopes catchError/warnError — the last two
            // decide their own outcome inside the scope, by folding the body's typed
            // outcome (BodyOutcomeFolder). There is NO legacy linear path left for a
            // scope, and no fallback: a family the engine cannot execute is rejected.

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
            )
            val ready = when (preparation) {
                // Terminal SCHEMA rejection (C3/C5): the effective executor is never invoked.
                is DispatchPreparation.Rejected -> return refuse(
                    schemaRejection(
                        operationId = preparation.operationId,
                        input = preparation.input,
                        message = preparation.reason,
                        context = innerScope.ambient,
                    ),
                )
                is DispatchPreparation.Ready -> preparation
            }
            val opId = ready.opId
            val operationId = ready.operationId
            val input = ready.input
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
                    // The recovery is the SAME operation being settled, so it hangs under the
                    // dispatch that asked for it — not under a trace of its own.
                    parent = innerScope.parent,
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
                    return terminal(DispatchedStep(interpretation.result, innerScope.ambient))
                }
                is RecoveryInterpretationEngine.RecoveryInterpretation.Reused -> {
                    // The effect was SKIPPED by the replay: it happened in an
                    // earlier run, and this run reused the durable terminal
                    // instead of producing it. The dispatch therefore leaves NO
                    // span behind — a trace must never claim an execution that
                    // did not happen here. The span is discarded, not ended.
                    spanSkipped = true
                    return DispatchedStep(interpretation.result, innerScope.ambient)
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
                // The observability seam for THIS dispatch, already hanging
                // under the step's own span. A plugin that declares the
                // capability sees an observation inside the step that ran it,
                // never one floating beside the run.
                stepObservability = telemetry.stepObservability(innerScope.parent),
            )

            // +: strategy preparation runs ONLY on actual execution and NEVER
            // produces Step side effects. Selection is by the CLOSED structural family (LegacyCore vs
            // Registry), never by concrete step name. Reuse/divergence/recover never prepare. A
            // Rejected admission (typed field / unsupported command / missing capability) is a
            // terminal SCHEMA rejection (journal FAILED, common executor never runs).
            // extracted to DurableTypedInputPreparation.
            val prepared = when (val typed = measurePreparation(step, runtime)) {
                is DurableTypedInputPreparation.TypedPreparation.Rejected -> return refuse(
                    schemaRejection(
                        operationId = operationId,
                        input = input,
                        message = "schema mismatch for step '${step.pluginStepId.value}' on " +
                            "'${step.id.value}': ${typed.reason}",
                        context = innerScope.ambient,
                    ),
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
                parent = innerScope.parent,
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
            // `executeAndJournal` already returns the atomic carrier, so this is a COMPOSITION.
            // The carrier crosses this line whole. Narrowing it to the outcome alone is exactly
            // what this whole slice exists to remove: a recovered or a fresh encoded value dies
            // at that narrowing.
            return terminal(DispatchedStep(execution, innerScope.ambient))
        } finally {
            // The span is closed HERE and only here, so a dispatch that threw
            // still ends its span instead of leaking an open one. A span that
            // was never ended is never exported, and an exported span that
            // says "running" is a lie the packaging rules forbid.
            //
            // The metric is closed with it, and it tells the same truth: a
            // dispatch the replay SKIPPED counts nothing at all, because the
            // effect it describes did not happen here.
            if (spanSkipped) {
                LiveSpans.discard(span)
                dispatchMeasurement?.discard()
            } else {
                LiveSpans.end(span, spanStatus ?: LiveSpanStatus.Failed)
                dispatchMeasurement?.end(metricOutcome ?: MetricOutcome(MetricStatus.Unknown))
            }
        }
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
    ): DispatchedStep = DispatchedStep(
        CommonExecutionResult(
            outcome = invocationResolver.rejectSchema(operationId, input, message),
        ),
        context,
    )

    /**
     * The typed admission of one step, measured.
     *
     * The admission is a boundary of its own: it is where a payload is decoded
     * and where a step that does not fit is turned away, and both facts are
     * worth a duration. A refusal is recorded as a REFUSAL and not as a
     * failure, because nothing ran and nothing broke — a counter that merges
     * the two tells an operator that the pipeline is broken when it is not.
     *
     * A preparation that THREW is recorded as an unclassified ending: the
     * product does not classify the throw here, so claiming a class would be
     * a claim it did not make.
     */
    private fun measurePreparation(
        step: StepNode,
        runtime: CanonicalRuntimeContext,
    ): DurableTypedInputPreparation.TypedPreparation {
        val measurement = telemetry.metrics.start(MetricBoundary.StepPreparation)
        val prepared = try {
            typedInputPreparation.prepare(step, runtime)
        } catch (throwable: Throwable) {
            measurement?.end(MetricOutcome(MetricStatus.Unknown))
            throw throwable
        }
        measurement?.end(
            when (prepared) {
                is DurableTypedInputPreparation.TypedPreparation.Rejected ->
                    MetricOutcome(MetricStatus.AdmissionRefused)
                is DurableTypedInputPreparation.TypedPreparation.Ready ->
                    MetricOutcome(MetricStatus.Success)
            },
        )
        return prepared
    }

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
        spanScope: SpanScope,
    ): StepOutcome = dispatchStep(
        StepDispatchRequest(
            step = child,
            runId = runId,
            stageName = stageName,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            shOptions = childShOptions,
            bodyPath = bodyPath,
            spanScope = spanScope,
        ),
    ).result.outcome

}

