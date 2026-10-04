package dev.rubentxu.pipeline.v2.application.durable


import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.CoreLegacyStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.application.durable.StageExecutionEngine.StageOutcome as StageVerdictOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.RegistryBodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
// WU-G5R.5: durable waitUntil reconciliation driver and identity factory.

// RETRY-D: control journal + reconciliation driver (ADR-0075 §11).
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.retry.RetryReconciliationDriver
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.domain.post.PostPlanner
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Executes the linear canonical core subset with the durable journal and replay cursor. */
class CanonicalDurableRunCoordinator(
    private val dispatcher: CanonicalNodeDispatcher,
    private val journal: OperationJournal,
    private val cursorStore: ReplayCursorStore,
    private val clock: Clock,
    private val effectReplayPolicy: EffectReplayPolicy,
    private val eventSink: EventSink,
    private val credentialScopePort: CredentialScopePort,
    private val controlDirRoot: Path? = null,
    /** WU-LPR-062: optional project-workspace override (--workspace <dir>). */
    private val workspaceBase: Path? = null,
    private val shOptions: ShOptions = ShOptions.EMPTY,
    // WU-LPR-011 secret-redaction slice: active secret registry threaded to the
    // runtime context so the durable console transcript is redacted at the
    // transcript seam before reaching the observable event plane.
    private val secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    // WU-093 H2b: OFFICIAL_PLUGIN capability contributions. PREPARE and EXECUTE
    // must observe the SAME set, so both get this one contributor. The
    // alternative tried first — a `Map<StepCapability, Any>` on `ShOptions` to
    // avoid this parameter — kept the count and lost the architecture.
    private val capabilityContributor: RuntimeCapabilityContributor = RuntimeCapabilityContributor { emptyMap() },
    private val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector(),
    // CDE.2-b2: durable metadata resolved by structural step key (pre-decode). Nullable default so a
    // registry-injected constructor can opt into the composite (CDE.3-e4.2); legacy call-sites that do
    // not pass a resolver keep the [CoreLegacyStepMetadataResolver] authority exactly as before.
    private val stepMetadataResolver: StepMetadataResolver? = null,
    // B1.2c2-a1: temporary compatibility seam for the EFFECTIVE step invocation. Optional so the
    // existing ~25 construction sites compile unchanged; production default delegates to the legacy
    // dispatcher. Not the final DI architecture.
    invocationExecutor: CanonicalInvocationExecutor? = null,

    // CDE.3-b3: the new authority seam for effective execution. Optional for dual characterization;
    // when absent it defaults to the legacy adapter over [invocationExecutor] (or the legacy
    // dispatcher), preserving behaviour exactly. Existing callers that inject the old executor keep
    // working unchanged because the default boundary routes to it.
    commonExecutionBoundary: CommonExecutionBoundary? = null,
    // CDE.3-e4.1: optional open StepRegistry (plugin definitions) for registry-driven metadata and,
    // later, execution. Additive at the end of the ctor so the ~25 legacy call-sites (positional or
    // named) compile unchanged. No global registry, no service locator, no singleton.
    private val stepRegistry: StepRegistry? = null,
    // RETRY-D: optional retry control journal. When bound, the retry branch of `dispatchBody`
    // routes through RetryReconciliationDriver (ADR-0075 §11). When null, the pre-ADR-0075
    // inline retry loop is preserved bit-equivalent — existing callers and tests see no change.
    private val retryControlJournal: FileBasedRetryControlJournal? = null,
    // WU-G5R.4 / WU-G5R.5: optional waitUntil control journal. When bound, the waitUntil
    // branch routes through the WaitUntilReconciler (durable polling aggregate). When null,
    // the pre-WU-G5R.5 inline polling loop is preserved — existing callers and tests see no change.
    private val waitUntilControlJournal: WaitUntilControlJournal? = null,
    // S2-A9: milestone state store scoped to this coordinator/run. Each coordinator instance
    // creates its own MilestoneStateStore by default, so all milestone invocations within a run
    // share the same store (shared by all pipeline stages in the run) while concurrent runs
    // are fully isolated. This mirrors the legacy CanonicalMilestoneNodeDispatcher.lastReachedOrdinal
    // scope (per run, not global classloader). No longer nullable — production always gets
    // a store; tests that need to control the store explicitly pass their own instance.
    private val milestoneStateStore: MilestoneStateStore = MilestoneStateStore(),

    // E1.1 / T1: per-run artifact index for the core.archiveArtifacts →
    // core.artifact.query bridge. Defaults to a fresh InMemoryArtifactIndex
    // when absent; tests that need to control the index explicitly pass
    // their own instance. Lifetime is the coordinator lifetime — i.e. one
    // index per run, shared between the producer (archive with name=...) and
    // the consumer (artifactQuery). When null is supplied (legacy callers),
    // the bridge stays unwired and both Steps fail closed at runtime.
    private val artifactIndex: dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability? = null,

    // B10/W1c + WU-RP-033: the body execution policy authority. Composed in the class body
    // (see bodyPolicyResolver below). A caller may inject another authority to characterize
    // the fail-closed laws. Appended last so existing positional call-sites compile unchanged.
    private val injectedBodyPolicyResolver: BodyPolicyResolver? = null,

    // B11 / W2: the body-reentry adapter bound under BODY_INVOKER_CAPABILITY (ADR-0073 / ADR-0081 D1).
    // Per-run lifetime: a fresh adapter is constructed when no caller injects one; the canonical
    // coordinator passes it into CanonicalRuntimeContext for every dispatch so the capability
    // bridge exposes the seam and any registered handler can re-enter the engine through the
    // shared body-child loop. When the caller wires a custom adapter (e.g. tests) the
    // single shared-loop law is preserved: the adapter NEVER iterates body children itself.
    private val bodyInvokerAdapter: CanonicalBodyInvokerAdapter = CanonicalBodyInvokerAdapter(),

    // S1-B: the open DirectiveRegistry. When bound, every stage's declared
    // directives are admitted against it BEFORE the stage starts (pure
    // decision via StageDirectivePlanner, interpreted here at the effect
    // boundary); a denial fails the run closed with a typed USER failure and
    // the stage body never dispatches. When null, stages declaring directives
    // are denied (no composition = no resolution), and stages without
    // directives behave exactly as before: the seam is additive.
    private val directiveRegistry: dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry? = null,

    /**
     * S2-A: the facts a gate is evaluated against, read at the effect boundary.
     *
     * Takes the CURRENT STAGE's declared environment: a gate is a property of
     * the stage that carries it, and the stage's own `environment { }` block
     * is the only environment the DSL can express. Reading a pipeline-level or
     * ambient environment instead would make the same script gate differently
     * for reasons its author never wrote.
     */
    private val gateContext: (
        stageEnvironment: dev.rubentxu.pipeline.v2.domain.EnvironmentSpec,
    ) -> dev.rubentxu.pipeline.v2.domain.directive.GateContext =
        { dev.rubentxu.pipeline.v2.domain.directive.GateContext.EMPTY },

    /**
     * S2-A: the PURE decision. Injected so the engine interprets a verdict
     * rather than computing one, keeping "decide" and "interpret" separate as
     * the architecture requires.
     */
    private val gateEvaluator: (
        predicate: dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate,
        context: dev.rubentxu.pipeline.v2.domain.directive.GateContext,
    ) -> dev.rubentxu.pipeline.v2.domain.directive.GateVerdict =
        { predicate, context ->
            dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEvaluator.evaluate(predicate, context)
        },

    /**
     * S4-R1 §3b — the reattach wait, forwarded to the composed [ExternalSubprocessRecovery].
     *
     * `null`, which is every production call site, leaves the observer on its own real-executor
     * default, so no production behaviour or timing changes. The dependency and its rationale live
     * on the observer's constructor; the composition-root view is [CoordinatorCaps.reattachPoll].
     */
    private val reattachPoll: ((Path, Long) -> Int?)? = null,

    /**
     * S4 retention: what a run's terminal state does to its output. Consulted in exactly one place —
     * the `finally` of [run], which every exit passes through. The runtime knows a run ended;
     * [RunOutputRetention] is the only production seam that can turn that into an `OutputPruneIntent`,
     * so the store is never told and cannot learn. Null leaves a run's output where it is.
     */
    private val outputRetention: RunOutputRetention? = null,
) {
    /**
     * Compatibility constructor for the consolidated capability bundle.
     *
     * The legacy 22-parameter constructor remains intact for existing named and positional
     * call sites. This overload is the migration seam for new composition roots: all values
     * are forwarded without reinterpretation, so both construction paths enter the same
     * coordinator body and preserve durable execution semantics.
     */
    constructor(caps: CoordinatorCaps) : this(
        dispatcher = caps.dispatcher,
        journal = caps.journal,
        cursorStore = caps.cursorStore,
        clock = caps.clock,
        effectReplayPolicy = caps.effectReplayPolicy,
        eventSink = caps.eventSink,
        credentialScopePort = caps.credentialScopePort,
        controlDirRoot = caps.controlDirRoot,
        workspaceBase = caps.workspaceBase,
        shOptions = caps.shOptions,
        secretPatternRegistry = caps.secretPatternRegistry,
        capabilityContributor = caps.capabilityContributor,
        divergenceDetector = caps.divergenceDetector,
        stepMetadataResolver = caps.stepMetadataResolver,
        invocationExecutor = caps.invocationExecutor,
        commonExecutionBoundary = caps.commonExecutionBoundary,
        stepRegistry = caps.stepRegistry,
        retryControlJournal = caps.retryControlJournal,
        waitUntilControlJournal = caps.waitUntilControlJournal,
        milestoneStateStore = caps.milestoneStateStore,
        artifactIndex = caps.artifactIndex,
        injectedBodyPolicyResolver = caps.injectedBodyPolicyResolver,
        bodyInvokerAdapter = caps.bodyInvokerAdapter,
        directiveRegistry = caps.directiveRegistry,
        gateContext = caps.gateContext,
        reattachPoll = caps.reattachPoll,
    )
    // B10/W1c + WU-RP-033: the body execution policy authority. The production default
    // composes TWO declared-policy authorities, both fail-closed and neither key-specific:
    //   1. the OPEN StepRegistry (same seam that resolves handlers) — a core or external
    //      plugin Step whose descriptor declares a body resolves here identically;
    //   2. the canonical descriptor table (StepDescriptorRegistry.standard()) — the core
    //      block Steps without a registered handler (dir/withEnv/timeout/retry/...).
    private val bodyPolicyResolver: BodyPolicyResolver =
        injectedBodyPolicyResolver ?: run {
            val canonical = StepDescriptorRegistry.standard().bodyPolicyResolver(BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING)
            val fromRegistry = RegistryBodyPolicyResolver(
                registry = NoopStepRegistry(stepRegistry),
                support = BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING,
            )
            BodyPolicyResolver { key ->
                when (val resolved = fromRegistry.resolve(key)) {
                    is BodyPolicyResolution.Resolved -> resolved
                    // Unknown in the open registry: fall back to the canonical core table.
                    is BodyPolicyResolution.Rejected ->
                        if (resolved.reason is BodyPolicyRejection.UnknownStep) canonical.resolve(key)
                        else resolved
                }
            }
        }
    private val invocationResolver: DurableInvocationResolver = DurableInvocationResolver(
        divergenceDetector = divergenceDetector as StrictFingerprintDivergenceDetector,
        effectReplayPolicy = effectReplayPolicy,
        journal = journal,
        // TRAIN H3 / PR-019: the a2 external-subprocess compatibility hook is COMPOSED here and
        // reaches the resolver only as a port. The resolver decides when recovery applies; this
        // adapter owns the only place that knows a control directory and a live process exist.
        runningSubprocessRecovery = ExternalSubprocessRecovery(clock, controlDirRoot, reattachPoll),
    )

    // WU-RP-031 E3: typed input preparation behind a narrow collaborator.
    private val typedInputPreparation: DurableTypedInputPreparation = DurableTypedInputPreparation(
        stepRegistry = stepRegistry,
        milestoneStateStore = milestoneStateStore,
        artifactIndex = artifactIndex,
        capabilityContributor = capabilityContributor, // H2b: the same set EXECUTE will see
    )


    /**
     * Effective pre-decode metadata authority (CDE.3-e4.2). An explicit [stepMetadataResolver] wins;
     * otherwise an injected [stepRegistry] opts into the composite (core keys -> legacy authority,
     * other keys -> registry metadata, unknown -> hard defect); otherwise the legacy core authority.
     * The durable coordinator only ever consumes [StepMetadata]; it does not know where it came from.
     */
    private val metadataResolver: StepMetadataResolver = stepMetadataResolver
        ?: if (stepRegistry != null) RegistryStepMetadataResolver.composite(stepRegistry)
        else CoreLegacyStepMetadataResolver

    /**
     * Effective step executor, routed through the new common seam (CDE.3-b3). The production default
     * adapts the injected legacy [invocationExecutor] (or the legacy dispatcher) behind
     * [CommonExecutionBoundary], preserving behaviour exactly; a caller may inject its own boundary
     * for dual characterization. The durable coordinator only ever hands an opaque [PreparedExecution]
     * to this seam and never names a decoded command type.
     *
     * As of S2.5.7 WU-5 the call is routed through [ExecutionBoundaryFactory.build] (the named
     * producer seam) directly, bypassing the [buildDefaultExecutionBoundary] forwarder. The factory
     * is the structural-switch authority (binary `if (stepRegistry != null)` preserved bit-a-bit); the
     * forwarder is kept as a source-compatibility shim for any external callers.
     */
    private val executionBoundary: CommonExecutionBoundary = commonExecutionBoundary

        // CDE.3-e4.5 / B1.2c3-S2.5.7 WU-5: structural switch lives in ExecutionBoundaryFactory.build
        // (binary legacy-bit-equivalent: registry present -> SeamedRouting; otherwise -> LegacyOnly).
        // S2-A9 spike: pass milestoneStateStore so RegistryExecutionBoundary can provide
        // MILESTONE_OPERATIONS_CAPABILITY during handler execution.
        // E1.1 / T1: pass artifactIndex so the producer (core.archiveArtifacts)
        // and the consumer (core.artifact.query) share one instance per run.
        ?: ExecutionBoundaryFactory.build(
            dispatcher = dispatcher,
            invocationExecutor = invocationExecutor,
            stepRegistry = stepRegistry,
            milestoneStateStore = milestoneStateStore,
            artifactIndex = artifactIndex,
            capabilityContributor = capabilityContributor,
        )

    // WU-RP-031 E4 / ADR-0103 D7: the executor folds ONE operation; the traversal owns the cursor.
    private val stepExecutor: DurableStepExecutor = DurableStepExecutor(
        eventSink = eventSink,
        executionBoundary = executionBoundary,
        journal = journal,
    )

    // C3 / WU-PR-017: the run-lifecycle bookends and the running outcome live in
    // the extracted engine (same events, same ordering, same quirks).
    private val runLifecycle = RunLifecycleEngine(eventSink)
    // TRAIN H4 / PR-020: the BEFORE_STAGE directive seam. It admits, decodes, composes,
    // evaluates and observes, then returns a closed verdict; this class keeps the run's control
    // flow, because only the owner of the loop may decide what the run does next.
    private val beforeStageDirectives = BeforeStageDirectiveEngine(eventSink, gateContext, gateEvaluator)
    // TRAIN H3 / PR-019: the INTERPRETATION of a recovery resolution. The DECISION already
    // lives in invocationResolver; this engine performs the journal write and the lifecycle
    // events that a resolution names, and reports ProceedToExecution for the one resolution
    // that is not a recovery case. ADR-0103 D7: it advances no cursor — that is traversal state.
    private val recoveryInterpretation = RecoveryInterpretationEngine(eventSink, journal)
    private val bodyExecutionEngine = BodyExecutionEngine(
        eventSink,
        clock,
        bodyInvokerAdapter,
        retryControlJournal,
        waitUntilControlJournal,
    )
    // TRAIN H4 / PR-020: the step-execution spine. `dispatch` falls through to `dispatchBody`,
    // which hands the body back to the body engine with this class's own `dispatchChild` as the
    // re-entry reference, and a parallel branch walks its steps through `dispatch` again. That
    // recursion is the unit of cohesion, so the whole closed cycle moves together and the
    // coordinator keeps only the run's own control flow.
    private val stepDispatch = StepDispatchEngine(
        journal = journal,
        eventSink = eventSink,
        clock = clock,
        credentialScopePort = credentialScopePort,
        metadataResolver = metadataResolver,
        typedInputPreparation = typedInputPreparation,
        stepExecutor = stepExecutor,
        invocationResolver = invocationResolver,
        recoveryInterpretation = recoveryInterpretation,
        bodyExecutionEngine = bodyExecutionEngine,
        bodyPolicyResolver = bodyPolicyResolver,
        runLifecycle = runLifecycle,
        cursorStore = cursorStore,
        stepRegistry = stepRegistry,
        bodyInvokerAdapter = bodyInvokerAdapter,
        controlDirRoot = controlDirRoot,
        workspaceBase = workspaceBase,
        secretPatternRegistry = secretPatternRegistry,
        capabilityContributor = capabilityContributor,
    )
    // TRAIN H4 / PR-020: a parallel stage is a composite with its own durable aggregate, not a
    // Step, so it does not belong to the step spine. It dispatches branch steps back through
    // `stepDispatch`, which keeps one direction only and one dispatch spine.
    private val parallelStages = ParallelStageEngine(
        stepDispatch = stepDispatch,
        journal = journal,
        eventSink = eventSink,
        clock = clock,
        runLifecycle = runLifecycle,
        controlDirRoot = controlDirRoot,
        workspaceBase = workspaceBase,
    )
    // TRAIN H4 / PR-020: what a stage DOES once the run has decided to start it — its linear
    // body, its continuation, and its `post` finalizers. A stage reports a closed verdict and
    // the context it leaves behind; only this loop decides what the run does next.
    private val stageExecution = StageExecutionEngine(
        stepDispatch = stepDispatch,
        runLifecycle = runLifecycle,
        eventSink = eventSink,
    )

    suspend fun run(pipeline: CompiledPipeline, runId: RunId): RunOutcome {
        runLifecycle.openRun(pipeline, runId)
        // CTX-P2: ambient execution context is a run-local immutable value threaded
        // explicitly through dispatch; no coordinator field, no restore idiom.
        var ambient = ExecutionContext.EMPTY


        try {
            stagesLoop@ for (stageIndex in pipeline.stages.indices) {
                val stage = pipeline.stages[stageIndex]

                // TRAIN H4 / PR-020: the BEFORE_STAGE directive seam is interpreted by
                // BeforeStageDirectiveEngine, which admits, decodes, composes, evaluates and
                // OBSERVES, then returns a closed verdict. Only this loop owns the run's control
                // flow: a collaborator may not write `return@run` or `continue@stagesLoop`.
                when (
                    val verdict = beforeStageDirectives.interpret(stage, stageIndex, runId, directiveRegistry)
                ) {
                    BeforeStageDirectiveEngine.Verdict.Admitted -> Unit

                    is BeforeStageDirectiveEngine.Verdict.Denied -> {
                        runLifecycle.fold(RunOutcome.Failure(PipelineFailure(
                            verdict.kind,
                            verdict.reason,
                        )))
                        return@run runLifecycle.outcome()
                    }

                    is BeforeStageDirectiveEngine.Verdict.SkipStage -> {
                        // S2-B: a skip is itself a stage outcome. The finalizers that fire for
                        // it are exactly the ones the pure planner selects for Skipped
                        // (always + cleanup); if the author declared none, this is a no-op.
                        stageExecution.runPostBlock(
                            stage = stage,
                            stageIndex = stageIndex,
                            stageFinishedOutcome = "skipped",
                            runId = runId,
                            // The stage never ran, so its workspace was never created;
                            // finalizers run on the base options with the stage's
                            // env/timeout projection applied. Using raw `shOptions` here
                            // would silently drop the stage environment from a skipped
                            // stage's post.
                            stageShOptions = stage.projectShellOptions(shOptions),
                            ambient = ambient,
                        )?.let { failure ->
                            runLifecycle.fold(RunOutcome.Failure(failure))
                            return@run runLifecycle.outcome()
                        }
                        continue@stagesLoop
                    }
                }

                // Stage boundary: ambient context must be structurally empty when entering a stage
                check(ambient.overlays.isEmpty()) {
                    "Scope stack leaked into stage '${stage.name}' at index $stageIndex: ${ambient.overlays.size} frame(s) remaining"
                }
                val steps = (stage.body as? StageBody.Steps)?.steps
                var stageWorkspace: Path? = null
                if (controlDirRoot != null) {
                    val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
                    val workspacePath = resolver.resolve(stage.name, stageIndex)
                    try {
                        resolver.ensureCreated(workspacePath)
                        stageWorkspace = workspacePath
                    } catch (e: java.io.IOException) {
                        runLifecycle.fold(RunOutcome.Failure(
                            PipelineFailure(
                                dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                                "Failed to create stage workspace '${workspacePath}': ${e.message}"
                            ),
                        ))
                        return@run runLifecycle.outcome()
                    }
                }
                val stageBaseOptions = if (stageWorkspace != null) shOptions.copy(workspaceRoot = stageWorkspace) else shOptions
                val stageShOptions = stage.projectShellOptions(stageBaseOptions)
                // B13/E-EM-11: parallel stages re-enter the SAME dispatch spine —
                // each branch is dispatched as a bodyPath-rooted branch with its
                // own branchIndex (deterministic durable identity via OpId -b{N}
                // journal keys). No second engine, no ParallelFrameExecutor.
                if (steps == null && stage.body is StageBody.Parallel) {
                    // Workspace creation is required before branch dispatch (D5/C1 reuse).
                    val parallelOutcome = parallelStages.runParallelStage(stage, stageIndex, stageShOptions, runId, ambient)
                    // S4-F2: post-block + StageFinished are ONE rule, in StageExecutionEngine.
                    // These three arms used to spell it out and had already drifted — they passed
                    // outcome strings where the engine passed StageOutcome. A stage being FAILED
                    // does not emit StageFinished; RunFinished carries that.
                    when (val continuation = runLifecycle.decideStageContinuation(parallelOutcome, stage.name, runId.value, ambient)) {
                        CanonicalContinuation.Continue -> stageExecution.finalizeStage(
                            stage, stageIndex, StageVerdictOutcome.SUCCESS, runId, stageShOptions, ambient,
                        )
                        CanonicalContinuation.ContinueUnstable -> {
                            runLifecycle.fold(RunOutcome.Unstable)
                            stageExecution.finalizeStage(
                                stage, stageIndex, StageVerdictOutcome.UNSTABLE, runId, stageShOptions, ambient,
                            )
                        }
                        is CanonicalContinuation.Abort -> {
                            // A parallel branch failure is still a stage outcome; failure/always/
                            // cleanup finalizers MUST run before the run aborts. The original abort
                            // reason wins unless the finalizers themselves failed.
                            stageExecution.finalizeStage(
                                stage, stageIndex, StageVerdictOutcome.FAILED, runId, stageShOptions, ambient,
                                emitStageFinished = false,
                            )?.let { postFailure ->
                                runLifecycle.fold(RunOutcome.Failure(postFailure))
                                return@run runLifecycle.outcome()
                            }
                            runLifecycle.fold(RunOutcome.Failure(continuation.failure))
                            return@run runLifecycle.outcome()
                        }
                    }?.let { postFailure ->
                        runLifecycle.fold(RunOutcome.Failure(postFailure))
                        return@run runLifecycle.outcome()
                    }
                    continue@stagesLoop
                }
                // Fail closed on a body shape this spine does not run, and NAME the shape.
                val steps1 = steps ?: throw IllegalArgumentException("Unsupported ${stage.body::class.simpleName} in '${stage.name}'")
                // D5: Per-stage workspaceRoot override at dispatch boundary
                // C1: Workspace pre-creation - ensure stage workspace exists before shell dispatch
                // LFC-2 / ERR-S-004: restore stage bookends lost in the LF-0208 spine migration.
                // The canonical coordinator emits StageStarted at entry and StageFinished on normal
                // completion (success/unstable). An aborting stage returns before StageFinished;
                // RunFinished carries the failure.
                // TRAIN H4 / PR-020: the stage body, its continuation and its `post`
                // finalizers live in StageExecutionEngine. The run loop keeps only the two
                // things a stage may say to a run: carry on carrying this context, or abort
                // with this typed reason. A collaborator may not write return@run.
                when (val verdict = stageExecution.runLinearStage(stage, stageIndex, steps1, stageShOptions, runId, ambient)) {
                    is StageExecutionEngine.StageVerdict.Completed -> ambient = verdict.context

                    is StageExecutionEngine.StageVerdict.Abort -> {
                        runLifecycle.fold(RunOutcome.Failure(verdict.failure))
                        return@run runLifecycle.outcome()
                    }
                }
            }
            // Success: fall through to finally and return
        } catch (e: Exception) {
            // Invariants are engine failures, never ordinary infrastructure outcomes.
            if (e is IllegalStateException || e is EngineInvariantViolation) throw e
            runLifecycle.fold(RunOutcome.Failure(
                PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                    "Unexpected error during pipeline run: ${e.message}"
                ),
            ))
        } finally {
            // C3 / WU-PR-017: the closing bookend is the engine's; the correlation
            // invariant (RunFinished only if RunStarted was emitted) lives there.
            runLifecycle.closeRun(runId)
            // S4 retention: the run has ended, which is the only moment that can authorise discarding
            // its output. Not gated on the bookend invariant above — that asks whether RunFinished may
            // be EMITTED, this asks whether the run ENDED. The seam reports its own diagnostics.
            outputRetention?.onRunTerminal(runId)
        }
        return runLifecycle.outcome()
    }

    private companion object {
        const val REATTACH_TIMEOUT_MS = 60_000L
    }
}

/**
 * WU-RP-033: read-only [StepRegistry] view over a nullable registry. Used by the
 * composed body-policy authority so callers that never wired a registry keep the
 * historical behaviour (open-registry lookup returns UnknownStep and the canonical
 * core table answers). Read-only: register/register Throws are impossible here, an
 * invariant this adapter makes unrepresentable.
 */
private class NoopStepRegistry(private val delegate: StepRegistry?) : StepRegistry {
    override fun register(definition: StepDefinition<*, *>) =
        throw UnsupportedOperationException("NoopStepRegistry is read-only")

    override fun register(registration: StepRegistration<*, *>) =
        throw UnsupportedOperationException("NoopStepRegistry is read-only")

    override fun definition(key: dev.rubentxu.pipeline.v2.domain.PluginStepId) =
        delegate?.definition(key)

    override fun providerOf(key: dev.rubentxu.pipeline.v2.domain.PluginStepId) =
        delegate?.providerOf(key)

    override fun contains(key: dev.rubentxu.pipeline.v2.domain.PluginStepId): Boolean =
        delegate?.contains(key) ?: false

    override fun keys(): Set<dev.rubentxu.pipeline.v2.domain.PluginStepId> =
        delegate?.keys() ?: emptySet()
}
