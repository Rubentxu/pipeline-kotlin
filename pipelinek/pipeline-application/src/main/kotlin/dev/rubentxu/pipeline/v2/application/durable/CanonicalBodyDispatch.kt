package dev.rubentxu.pipeline.v2.application.durable

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
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions

/**
 * The body of a body-bearing Step: its declared policy, its projection, its credential
 * preamble and its child loop — one owner, so the dispatch spine does not route bodies.
 *
 * What moved here is the body's own shape, not the dispatch ORDER: which policy family a
 * Step's declaration resolves to, which projection the body carries, whether a credential
 * lease is acquired before any child runs and released after every path, and how children
 * are iterated. Those decisions belong to the body. The engine keeps the recursion that
 * makes a Step happen and hands this class the ONE re-entry seam ([BodyChildDispatcher])
 * through which a child walks the canonical spine again — the same seam
 * [BodyExecutionEngine] already declares for exactly this purpose, so no second dispatch
 * boundary and no second decision site is created: the children re-enter the engine
 * through the same reference every other path uses.
 *
 * The journal order, the retry/recovery semantics and the credential cleanup are this
 * class's invariants, inherited verbatim from the shape they moved out of:
 * the lease is released on EVERY path and its typed outcome is folded with the body's
 * outcome; a refused lease returns BEFORE any child is dispatched.
 */
internal class CanonicalBodyDispatch(
    private val bodyPolicyResolver: BodyPolicyResolver,
    private val bodyExecutionEngine: BodyExecutionEngine,
    private val bodyInvokerAdapter: CanonicalBodyInvokerAdapter,
    // ADMISION: the lease of the credentials is decided by its owner, and the body dispatch only
    // observes whether it was admitted. The port itself is not the dispatch's business.
    private val leaseAdmission: CredentialLeaseAdmission,
    private val eventSink: EventSink,
    private val clock: Clock,
) {

    @Suppress("LongParameterList") // Every argument is named and read once; the alternative
    // would be a holder that exists only to satisfy a counting rule.
    suspend fun dispatchBody(
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
        // the ONE re-entry reference: a child dispatched here walks the canonical
        // spine again through exactly this call site, the same one every other body
        // path uses.
        reentry: BodyChildDispatcher,
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
        val contextInBody = executionContext
        // The FOLD is the scope's own outcome rule: the children run in the caller's
        // context and the fold decides what the scope's outcome IS. It is kept aside
        // because it applies AFTER the body has run, to the outcome the body produced.
        val fold = when (projection) {
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
                reentry = reentry,
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
            is BodyExecutionProjection.Folding -> projection
            is BodyExecutionProjection.Scope -> null
        }
        // A folding body runs in the caller's own context: the fold changes what the
        // scope's outcome IS, not what the children inherit.
        val scope = fold?.let { BlockShellScope.None }
            ?: (projection as BodyExecutionProjection.Scope).scope
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
        // BodyExecutionEngine; this class composes it with the projected
        // scope decided above. The credential-lease path composes through
        // [executeCredentialLeasedBody] (this class's own method).
        val bodyOutcome = bodyExecutionEngine.executeScope3b(
            scope = scope,
            block = block,
            runId = runId,
            stageName = stageName,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            childShOptions = projected.shOptions,
            parentBodyPath = parentBodyPath,
            context = contextInBody,
            dispatcher = reentry,
            bodyInvokerAdapter = bodyInvokerAdapter,
        )
        // A scope WITHOUT a fold reports the body's own outcome. A scope WITH one
        // reports the outcome the fold derives, and publishes what it saw.
        if (fold == null) return bodyOutcome
        return publishFold(fold, bodyOutcome, runId, stageName)
    }

    /**
     * Applies the scope's fold to the outcome its body produced and publishes what
     * the fold observed.
     *
     * The ORDER is contract: the fold is applied FIRST, so the scope's outcome is
     * the folded one, and the publications follow in the order the fold declared
     * them. A publication that happened before the fold would describe the body's
     * outcome rather than the scope's.
     *
     * The publications carry the injected CLOCK and a fresh event id: the fold is
     * a pure rule and knows neither, and an instant read from `Instant.now()` here
     * would put a second clock in the durable path.
     */
    private fun publishFold(
        fold: BodyExecutionProjection.Folding,
        bodyOutcome: StepOutcome,
        runId: RunId,
        stageName: String,
    ): StepOutcome {
        val result = BodyOutcomeFolder.fold(fold.fold, fold.contract, bodyOutcome)
        result.emissions.forEach { emission ->
            when (emission) {
                is BodyFoldEmission.CaughtFailure -> eventSink.append(
                    dev.rubentxu.pipeline.v2.events.CatchErrorTriggered(
                        eventId = java.util.UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = clock.now(),
                        stageName = stageName,
                        buildResult = emission.buildResult,
                        stageResult = emission.stageResult,
                        message = emission.message,
                    ),
                )
                is BodyFoldEmission.StageDegraded -> eventSink.append(
                    dev.rubentxu.pipeline.v2.events.StageMarkedUnstable(
                        eventId = java.util.UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = clock.now(),
                        stageName = stageName,
                        message = emission.message,
                    ),
                )
            }
        }
        return result.outcome
    }

    /**
     * The ONE body-child invocation loop (B10 / W1d).
     *
     * Every body-bearing Step re-enters the engine here: a plain scope, a retry attempt and
     * a credential lease all dispatch their children through this function, keyed by the
     * length-prefixed bodyPath (exactly-once). Before W1d there were THREE copies of
     * this loop — one beside the body dispatch, one in the retry-aware path and one in the
     * credential path — and each copy was a place where a block Step could acquire execution
     * semantics the shared engine did not know about.
     *
     * Semantics: children run in declaration order under [childShOptions] and
     * [executionContext]; the first Failure or Unstable stops the body and is returned;
     * otherwise the body outcome is [StepOutcome.Success].
     */
    @Suppress("LongParameterList") // Every argument is named and read once; the alternative
    // would be a holder that exists only to satisfy a counting rule.
    private suspend fun invokeBodyChildren(
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        childShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
        reentry: BodyChildDispatcher,
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
        reentry,
    )

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
    @Suppress("LongParameterList") // Every argument is named and read once; the alternative
    // would be a holder that exists only to satisfy a counting rule.
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
        reentry: BodyChildDispatcher,
    ): StepOutcome {
        // observability fix: an admission failure here is a REAL observable
        // outcome, not a silent one. Before any StepStarted exists for the leased body,
        // the failure MUST surface as a typed StepFailed event — otherwise external
        // observers see StageStarted -> RunFinished(failure) with empty diagnostics and
        // no way to know the credential lease was rejected.
        // ADMISSION: the owner of the lease decision says whether the lease was admitted and,
        // when it was refused, says the KIND of the refusal and its sentence ONCE. The body
        // dispatch reports that sentence; it does not build it, and it never asks twice for the
        // same one.
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
                reentry = reentry,
                leaseBindings = bindings,
            )
        } finally {
            cleanup = leased.close()
        }
        return mergeBodyAndCleanup(bodyOutcome, cleanup)
    }

    /**
     * emit a typed [dev.rubentxu.pipeline.v2.events.StepFailed] for a credential-lease
     * admission failure. The lease rejection happens BEFORE any child StepStarted, so
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
