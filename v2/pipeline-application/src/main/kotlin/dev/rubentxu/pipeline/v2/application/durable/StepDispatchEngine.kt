package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StructuralOverlay
import dev.rubentxu.pipeline.v2.application.StructuralOverlayProjection
import dev.rubentxu.pipeline.v2.application.StructuralPreparation
import dev.rubentxu.pipeline.v2.application.CanonicalStructuralPreparation
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata
import dev.rubentxu.pipeline.v2.application.durable.credentials.AcquiredCredentialScope
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeCleanup
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ContextTransition
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.durable.BranchTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ParallelAggregateId
import dev.rubentxu.pipeline.v2.domain.durable.ParallelAggregateSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.ParallelBranchChildSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision
import dev.rubentxu.pipeline.v2.domain.durable.ParallelReconciler
import dev.rubentxu.pipeline.v2.domain.durable.ParallelReconciliationInput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity
import dev.rubentxu.pipeline.v2.domain.step.BodyContinuation
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * TRAIN H4 / PR-020 — the step-execution spine, out of the run loop.
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
 * Nothing here decides. The durable identity, the structural verdict, the recovery
 * resolution, the body policy and the replay decision are all made by pure components
 * elsewhere; this class observes those decisions, performs the effects they name, and
 * reports typed results. [RecoveryInterpretationEngine] is the boundary where a recovery
 * resolution becomes either a settled outcome or a progression to execution.
 *
 * The coordinator keeps only what it alone can do: the run's bookends, the stage loop and
 * the stage-level finalizers.
 */
internal class StepDispatchEngine(
    private val journal: OperationJournal,
    private val eventSink: EventSink,
    private val clock: Clock,
    private val credentialScopePort: CredentialScopePort,
    private val metadataResolver: StepMetadataResolver,
    private val typedInputPreparation: DurableTypedInputPreparation,
    private val stepExecutor: DurableStepExecutor,
    private val invocationResolver: DurableInvocationResolver,
    private val recoveryInterpretation: RecoveryInterpretationEngine,
    private val bodyExecutionEngine: BodyExecutionEngine,
    private val bodyPolicyResolver: BodyPolicyResolver,
    private val runLifecycle: RunLifecycleEngine,
    private val stepRegistry: StepRegistry?,
    private val bodyInvokerAdapter: CanonicalBodyInvokerAdapter,
    private val controlDirRoot: Path?,
    private val workspaceBase: Path?,
    private val secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry?,
    // WU-093 H2b: handed to PREPARE so admission and execution observe the same set.
    private val capabilityContributor: dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor =
        dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor { emptyMap() },
) {

    /** A dispatched Step's outcome together with the context it left behind. */
    data class Dispatched(val outcome: StepOutcome, val context: ExecutionContext)

    /**
     * Everything a dispatch needs BEFORE any effect, decided as one value.
     *
     * Closed because "structurally rejected" and "ready to execute" are opposite outcomes, and
     * because the rejection still has to carry the durable identity the journal row needs: a
     * rejection that lost its [operationId] could not be journaled fail-closed, it would simply
     * vanish. A nullable seam would have let exactly that happen silently.
     */
    private sealed interface DispatchPreparation {
        /** Terminal SCHEMA rejection. The identity is carried so the caller can journal it. */
        data class Rejected(
            val operationId: String,
            val input: OperationInput,
            val reason: String,
        ) : DispatchPreparation

        /** Admitted. Every field is derived; nothing here observed anything. */
        data class Ready(
            val opId: OpId,
            val operationId: String,
            val input: OperationInput,
            val contextAfterOverlay: ExecutionContext,
            val metadata: StepMetadata,
            val fingerprint: Fingerprint,
            val lifecycleContext: StepLifecycleContext,
        ) : DispatchPreparation
    }

    /**
     * CDE.3-e4.3: the runtime context is built here so registry prepare can derive the available
     * capabilities from the capability bridge, never the raw context handed to a handler.
     *
     * It is built only on the execution path: reuse, divergence and recovery never reach it,
     * which is what keeps a replayed Step from acquiring a capability it did not use.
     */
    private fun runtimeContext(
        opId: OpId,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        bodyContinuation: dev.rubentxu.pipeline.v2.domain.step.BodyContinuation?,
    ): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = opId,
        runId = runId.value,
        stageName = stageName,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        shOptions = stageShOptions,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
        // B11 / W2: surface the per-run body-reentry adapter under
        // BODY_INVOKER_CAPABILITY for any block-step handler that declares it.
        // Fail-closed admission (null bodyInvoker → no capability) is preserved
        // for legacy callers because the field defaults to null on the context.
        bodyInvoker = bodyInvokerAdapter,
        // WU-RP-035: bound ONLY for a HANDLER_CONTINUATION Step, and already
        // carrying that Step's own body identity. Null everywhere else, so the
        // fail-closed admission is real: a handler that declares the capability
        // without the engine having bound it never runs.
        bodyContinuation = bodyContinuation,
        secretPatternRegistry = secretPatternRegistry,
        workspaceBase = workspaceBase,
    )

    /**
     * The PURE pre-execution prologue of a dispatch: durable identity, structural verdict,
     * control-overlay projection, metadata and fingerprint. No journal, no events, no clock and
     * no executor, so the whole thing is decidable — and therefore testable — without a run.
     *
     * Order is contract. The durable identity is derived FIRST because every rejection path
     * needs it to journal the rejection. The structural gate precedes typed decode because a
     * structurally-invalid node must be rejected before any typed semantics are read
     * (CDE.2-c0, C3/C5). Metadata is resolved by structural step key BEFORE the fingerprint, so
     * reconciliation never depends on a decoded command (CDE.2-b2).
     */
    private fun prepareDispatch(
        step: StepNode,
        runId: RunId,
        stageIndex: Int,
        stepIndex: Int,
        bodyPath: List<BlockSegment>,
        stageShOptions: ShOptions,
        hasBodyContinuation: Boolean,
        executionContext: ExecutionContext,
    ): DispatchPreparation {
        val opId = OpId(runId.value, stageIndex, stepIndex, bodyPath = bodyPath)
        val operationId = opId.format()
        // WU-RP-035: the body of a HANDLER_CONTINUATION Step is part of the parent's durable
        // identity. Without it, the same parent payload with a different body would reuse a
        // memoized result, the handler would never run, and the changed child would not even
        // reach divergence. Engine-driven blocks are deliberately EXCLUDED so every journal
        // row written by the published 0.45.0 candidate stays valid.
        val bodyStructure: String? = if (hasBodyContinuation && step is BlockStepNode) {
            BodyStructureDigest.of(step.body)
        } else {
            null
        }
        val input = OperationInput(
            stepId = step.pluginStepId.value,
            // SB-S-010 / D4: a non-NONE sandbox profile enters the operation fingerprint,
            // so a resume with a CHANGED confinement profile diverges fail-closed instead
            // of silently re-attaching under different semantics. NONE stays absent from
            // params so default-run journals keep their historical fingerprints.
            params = buildMap {
                put("payload", JsonPrimitive(step.payload.encoded))
                if (stageShOptions.sandbox.profile != SandboxProfile.NONE) {
                    put("sandboxProfile", JsonPrimitive(stageShOptions.sandbox.profile.name))
                }
                bodyStructure?.let { digest -> put("bodyStructure", JsonPrimitive(digest)) }
            },
            runId = runId.value,
            attempt = 1,
        )

        val structuralReady = when (val structural = CanonicalStructuralPreparation.prepare(step)) {
            is StructuralPreparation.Rejected ->
                return DispatchPreparation.Rejected(operationId, input, structural.reason)
            is StructuralPreparation.Ready -> structural
        }
        // CTX-P2: pure context derivation, no ambient mutation (C6 preserved: pushed pre-reconcile).
        val projectedOverlay = StructuralOverlayProjection.project(
            structuralReady.invocation.stepKey,
            structuralReady.envelope,
        )
        // Baseline behaviour preserved: Triggered with emitted=false performs NO scope exit.
        val overlayExitedSilently =
            projectedOverlay is StructuralOverlay.CatchErrorTriggered && !projectedOverlay.emitted
        val contextAfterOverlay = if (overlayExitedSilently) {
            executionContext
        } else {
            deriveOverlay(executionContext, projectedOverlay)
        }

        // CDE.2-b2: durable metadata resolved by structural step key BEFORE typed semantics, so
        // fingerprint and reconcile never depend on the decoded command.
        val metadata = metadataResolver.resolve(step.pluginStepId)
            ?: throw EngineInvariantViolation("No durable metadata for canonical step '${step.pluginStepId.value}'")

        return DispatchPreparation.Ready(
            opId = opId,
            operationId = operationId,
            input = input,
            contextAfterOverlay = contextAfterOverlay,
            metadata = metadata,
            fingerprint = Fingerprint.compute(input, step.pluginStepId.value, metadata.replayPolicy, 1),
            lifecycleContext = StepLifecycleContext(
                runId = runId.value,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                stepName = step.id.value,
                stepType = CanonicalCoreStepMetadata.shortType(step.pluginStepId.value),
            ),
        )
    }

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
        // WU-RP-035: a body-bearing Step is routed by its DECLARED owner, never by its key.
        //
        //   CANONICAL_ENGINE     the engine decides when to run the body. Unchanged since
        //                         EM-4: the decoder is bypassed and dispatchBody runs the
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
                    // caller's context (CTX-P).
                    val attemptSegment = bodyContext.attempt?.let { segment ->
                        listOf(dev.rubentxu.pipeline.v2.domain.BlockSegment(segment.index, segment.key))
                    } ?: emptyList()
                    val contextForCall = applyPatchToContext(executionContext, bodyContext.patch)
                    dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Completed(
                        dispatchBody(
                            block = step,
                            runId = runId,
                            stageName = stageName,
                            stageIndex = stageIndex,
                            stepIndex = stepIndex,
                            stageShOptions = stageShOptions,
                            parentBodyPath = bodyPath + attemptSegment,
                            executionContext = contextForCall,
                        ),
                    )
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
                    return Dispatched(body, executionContext)
                }
                null
            }

        // BlockStepNode bypasses decoder and goes directly to dispatchBody (EM-4).
        // EM-4 handles only the body-execution substrate for dir/withEnv/withCredentials/
        // timeout/retry. catchError and warnError remain on the legacy linear path
        // (rewriteWorkflowControl) until EM-5/EM-6 semantics are implemented.

        // CDE.2-c0: the whole pre-execution prologue is ONE pure decision — durable identity,
        // structural verdict, control overlay, metadata and fingerprint. It is decided here and
        // interpreted below, so the executor is reachable only under a Ready preparation and a
        // structural rejection can never fall through to typed decode.
        val preparation = prepareDispatch(
            step = step,
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            bodyPath = bodyPath,
            stageShOptions = stageShOptions,
            hasBodyContinuation = bodyContinuation != null,
            executionContext = executionContext,
        )
        val ready = when (preparation) {
            // Terminal SCHEMA rejection (C3/C5): the effective executor is never invoked.
            is DispatchPreparation.Rejected -> return Dispatched(
                rejectSchema(preparation.operationId, preparation.input, preparation.reason),
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
        // TRAIN H3 / PR-019: the four recovery resolutions are interpreted by
        // RecoveryInterpretationEngine; only Execute — which IS the invocation — stays here.
        // The arms were moved verbatim, including which ones emit lifecycle events, write the
        // journal and advance the cursor.
        when (
            val interpretation = recoveryInterpretation.interpret(
                reconcileInvocation(metadata, journaled, currentOperation, operationId),
                RecoveryInterpretationEngine.Request(
                    operationId = operationId,
                    fingerprint = fingerprint,
                    input = input,
                    runIdValue = runId.value,
                    stageIndex = stageIndex,
                    lifecycleContext = lifecycleContext,
                ),
            )
        ) {
            is RecoveryInterpretationEngine.RecoveryInterpretation.Settled ->
                return Dispatched(interpretation.outcome, contextAfterOverlay)
            RecoveryInterpretationEngine.RecoveryInterpretation.ProceedToExecution -> Unit
        }

        // Execute is the ONLY resolution that reaches the effective executor. beginOperation, the
        // StepExecutionBoundary-wrapped executor call, the terminal journal write and cursor advance
        // live here, so the concrete semantics are invoked exclusively under this decision.
        // CDE.3-e4.3: runtime context is hoisted here so registry prepare can derive the
        // available capabilities from the capability bridge (never the raw context to a handler).
        val runtime = runtimeContext(
            opId = opId,
            runId = runId,
            stageName = stageName,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stageShOptions = stageShOptions,
            bodyContinuation = bodyContinuation,
        )

        // CDE.2-c/d + CDE.3-b3/e4.3: strategy preparation runs ONLY on actual execution and NEVER
        // produces Step side effects. Selection is by the CLOSED structural family (LegacyCore vs
        // Registry), never by concrete step name. Reuse/divergence/recover never prepare. A
        // Rejected admission (typed field / unsupported command / missing capability) is a
        // terminal SCHEMA rejection (journal FAILED, common executor never runs).
        // WU-RP-031 E3: extracted to DurableTypedInputPreparation.
        val prepared = when (val typed = typedInputPreparation.prepare(step, runtime)) {
            is DurableTypedInputPreparation.TypedPreparation.Rejected -> return Dispatched(rejectSchema(
                operationId,
                input,
                "schema mismatch for step '${step.pluginStepId.value}' on '${step.id.value}': ${typed.reason}",
            ), contextAfterOverlay)
            is DurableTypedInputPreparation.TypedPreparation.Ready -> typed.prepared
        }

        // WU-RP-031 E4: effective execution + durable folding extracted to DurableStepExecutor.
        val outcome = stepExecutor.executeAndJournal(
            operationId = operationId,
            fingerprint = fingerprint,
            input = input,
            journaled = journaled,
            prepared = prepared,
            runtime = runtime,
            lifecycleContext = lifecycleContext,
            runIdValue = runId.value,
            stageIndex = stageIndex,
        )
        return Dispatched(outcome, contextAfterOverlay)
    }

    /**
     * Applies the pre-reconcile control overlay projected from the structural envelope (CDE.2-c0),
     * establishing or closing a CatchError context frame. This runs BEFORE durable resolution, so a
     * reused CatchErrorEntered still establishes its scope without re-executing (C6: executor stays 0).
     * Only the structural overlay descriptor is consumed; no typed command is built here.
     *
     * CTX-P2: pure derivation of the successor ExecutionContext from the structural overlay.
     * The old CatchErrorTriggered pop/underflow check disappears: the frame was pushed by the
     * matching CatchErrorEntered derivation, and the caller simply keeps its own parent value.
     * There is no shared authority to underflow.
     */
    private fun deriveOverlay(parent: ExecutionContext, overlay: StructuralOverlay): ExecutionContext = when (overlay) {
        StructuralOverlay.None -> parent
        is StructuralOverlay.CatchErrorEntered -> parent.pushed(
            ContextOverlay.CatchErrorOverlay(
                overlay.buildResult,
                overlay.stageResult,
                overlay.message,
                overlay.enteredAt?.toLongOrNull() ?: System.currentTimeMillis(),
            ),
        )
        is StructuralOverlay.CatchErrorTriggered -> when (val exit = parent.exitCatchError()) {
            is ContextTransition.Advanced -> exit.context
            is ContextTransition.Rejected -> throw IllegalStateException(
                "Context stack underflow: CatchErrorTriggered without matching CatchErrorEntered",
            )
        }
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
     * The body owner DECLARED by the Step's own descriptor, or `null` when the key is unknown
     * to the registry (WU-RP-035).
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
                    dev.rubentxu.pipeline.v2.domain.FailureKind.ENGINE,
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
        // CTX-P2: no parent capture, no restore — executionContext is the caller's value and stays it.
        var contextInBody = executionContext
        var outcome: StepOutcome = StepOutcome.Success
        val scope = when (projection) {
            // EM-7/LFC-5.3 (INC-022), reworked by W1d: the credential lease is a body
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
                    dev.rubentxu.pipeline.v2.domain.FailureKind.ENGINE,
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
        // WU-LPR-302 (Phase 1b, 2026-09-18): the runner now also applies the
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
        // TRAIN H2 slice 3b (PR-018): the scoped-body execution (reentry binding,
        // engine delegation or inline loops, and the bracketed bookends) lives in
        // BodyExecutionEngine; the coordinator composes it with the projected
        // scope decided above. The credential-lease path composes through the
        // credentialLeasedBody callback (this class's own method).
        val maxBackoffMsOverride = if (scope is BlockShellScope.WaitUntilScope) {
            Regex("\"maxBackoffMs\"\\s*:\\s*(\\d+)").find(block.payload.encoded)
                ?.groupValues?.get(1)?.toLongOrNull()
        } else null
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
            maxBackoffMsOverride = maxBackoffMsOverride,
        )
    }

    /**
     * The ONE body-child invocation loop (B10 / W1d).
     *
     * Every body-bearing Step re-enters the engine here: a plain scope, a retry attempt and
     * a credential lease all dispatch their children through this function, keyed by the
     * length-prefixed bodyPath (JEP-029 exactly-once). Before W1d there were THREE copies of
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
            ).outcome
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
    ).outcome

    /**
     * EM-7/LFC-5.3 (INC-022), reworked by W1d — a credential lease as a body PREAMBLE.
     *
     * The acquisition is an effectful preamble that yields an environment overlay; the body
     * itself re-enters the engine through [invokeBodyChildren], the same loop every other
     * block Step uses. Release ALWAYS runs — it is the `finally` of the same attempt that
     * dispatches the body, so it also runs when a child fails — and its typed outcome is
     * folded with the body's outcome by [mergeBodyAndCleanup], a total pure function of two
     * values.
     *
     * Fail-closed: an [CredentialScopeOutcome.Unavailable] or
     * [CredentialScopeOutcome.Invalid] acquisition returns BEFORE any child is dispatched, so
     * a lease that cannot be acquired never runs its body. The bindings were already decoded
     * by the pure projection (`decodeCredentialBindings`), so a malformed payload never
     * reaches this function.
     *
     * The env overlay reaches children as an immutable derived value (CTX-P): the caller's
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
        // WU-LPR-103 observability fix: an admission failure here is a REAL observable
        // outcome, not a silent one. Before any StepStarted exists for the leased body,
        // the failure MUST surface as a typed StepFailed event — otherwise external
        // observers see StageStarted -> RunFinished(failure) with empty diagnostics and
        // no way to know the credential lease was rejected.
        val leased: AcquiredCredentialScope = when (val acquisition = credentialScopePort.acquire(bindings, runId)) {
            is CredentialScopeOutcome.Unavailable -> {
                emitCredentialLeaseAdmissionFailure(
                    runId = runId, stepIndex = stepIndex,
                    stepName = "${block.pluginStepId.value}", stepType = "withcredentials",
                    failureKind = dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                    message = acquisition.failure.describe(),
                )
                return StepOutcome.Failure(
                    PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                        acquisition.failure.describe(),
                    ),
                )
            }
            is CredentialScopeOutcome.Invalid -> {
                emitCredentialLeaseAdmissionFailure(
                    runId = runId, stepIndex = stepIndex,
                    stepName = "${block.pluginStepId.value}", stepType = "withcredentials",
                    failureKind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                    message = acquisition.failure.describe(),
                )
                return StepOutcome.Failure(
                    PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA, acquisition.failure.describe()),
                )
            }
            is CredentialScopeOutcome.Acquired -> acquisition.scope
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
        // engine has not observed yet. close() is idempotent and never throws by contract.
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
     * EM-7/LFC-5.3 — folds a body outcome and the scope cleanup outcome into a single
     * total StepOutcome per design §73. Cleanup is idempotent and never throws.
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
     * WU-LPR-103: emit a typed [StepFailed] for a credential-lease admission
     * failure. The lease rejection happens BEFORE any child StepStarted, so
     * this event is the only per-step observability the outcome has.
     */
    private fun emitCredentialLeaseAdmissionFailure(
        runId: RunId,
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
                stepIndex = stepIndex,
                stepName = stepName,
                stepType = stepType,
                failureKind = failureKind,
                message = message,
            ),
        )
    }

    private fun CredentialScopeFailure.describe(): String = when (this) {
        is CredentialScopeFailure.StoreUnavailable -> message
        is CredentialScopeFailure.CredentialMissing -> "Credential '${credentialsId.value}' is not present in the store"
        is CredentialScopeFailure.BindingMismatch -> message
        is CredentialScopeFailure.AcquisitionFailed -> message
        CredentialScopeFailure.ReplayUnsupported -> "Replay of an in-flight credential scope is not supported"
    }
}
