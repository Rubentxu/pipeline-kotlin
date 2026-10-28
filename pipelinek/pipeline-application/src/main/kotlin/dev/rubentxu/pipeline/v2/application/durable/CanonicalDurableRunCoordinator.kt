package dev.rubentxu.pipeline.v2.application.durable


import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.CoreLegacyStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.RegistryBodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.post.StageOutcome
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialLeaseAdmission
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
// Durable waitUntil reconciliation driver and identity factory.

// Control journal + reconciliation driver.
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.registry.EventRegistry
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/** Executes the linear canonical core subset with the durable journal and replay cursor. */
class CanonicalDurableRunCoordinator(
    private val dispatcher: CanonicalNodeDispatcher,
    private val journal: OperationJournal,
    private val cursorStore: ReplayCursorStore,
    private val clock: Clock,
    private val effectReplayPolicy: EffectReplayPolicy,
    private val eventSink: EventSink,
    // The open event registry for this run. Empty rather than null, so that
    // "a plugin declared nothing" and "nothing is registered" are the same typed refusal.
    private val eventRegistry: dev.rubentxu.pipeline.v2.events.registry.EventRegistry =
        dev.rubentxu.pipeline.v2.events.registry.EventRegistry.create(),
    private val credentialScopePort: CredentialScopePort,
    private val controlDirRoot: Path,
    /** Optional project-workspace override (--workspace <dir>). */
    private val workspaceBase: Path? = null,
    private val shOptions: ShOptions = ShOptions.EMPTY,
    // The run's output-wake signal, forwarded to dispatch so the shell path
    // can announce committed chunks to the run's live observer. Null for
    // compositions without an observer; the observer's poll deadline covers
    // the silence either way.
    wakeups: dev.rubentxu.pipeline.v2.application.observation.WakeupSignal? = null,
    // Active secret registry threaded to the
    // runtime context so the durable console transcript is redacted at the
    // transcript seam before reaching the observable event plane.
    private val secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    // OFFICIAL_PLUGIN capability contributions. PREPARE and EXECUTE
    // must observe the SAME set, so both get this one contributor. The
    // alternative tried first — a `Map<StepCapability, Any>` on `ShOptions` to
    // avoid this parameter — kept the count and lost the architecture.
    private val capabilityContributor: RuntimeCapabilityContributor = RuntimeCapabilityContributor { emptyMap() },
    private val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector(),
    // durable metadata resolved by structural step key (pre-decode). Nullable default so a
    // registry-injected constructor can opt into the composite; legacy call-sites that do
    // not pass a resolver keep the [CoreLegacyStepMetadataResolver] authority exactly as before.
    private val stepMetadataResolver: StepMetadataResolver? = null,
    // Temporary compatibility seam for the EFFECTIVE step invocation. Optional so the
    // existing ~25 construction sites compile unchanged; production default delegates to the legacy
    // dispatcher. Not the final DI architecture.
    invocationExecutor: CanonicalInvocationExecutor? = null,

    // the new authority seam for effective execution. Optional for dual characterization
    // when absent it defaults to the legacy adapter over [invocationExecutor] (or the legacy
    // dispatcher), preserving behaviour exactly. Existing callers that inject the old executor keep
    // working unchanged because the default boundary routes to it.
    commonExecutionBoundary: CommonExecutionBoundary? = null,
    // Optional open StepRegistry (plugin definitions) for registry-driven metadata and,
    // later, execution. Additive at the end of the ctor so the ~25 legacy call-sites (positional or
    // named) compile unchanged. No global registry, no service locator, no singleton.
    private val stepRegistry: StepRegistry? = null,
    // Optional retry control journal. When bound, the retry branch of `dispatchBody`
    // Routes through RetryReconciliationDriver. When null, the inline
    // retry loop is preserved bit-equivalent — existing callers and tests see no change.
    private val retryControlJournal: FileBasedRetryControlJournal? = null,
    // Optional waitUntil control journal. When bound, the waitUntil
    // branch routes through the WaitUntilReconciler (durable polling aggregate). When null,
    // The inline polling loop is preserved — existing callers and tests see no change
    private val waitUntilControlJournal: WaitUntilControlJournal? = null,
    // Milestone state store scoped to this coordinator/run. Each coordinator instance
    // creates its own MilestoneStateStore by default, so all milestone invocations within a run
    // share the same store (shared by all pipeline stages in the run) while concurrent runs
    // are fully isolated. This mirrors the legacy CanonicalMilestoneNodeDispatcher.lastReachedOrdinal
    // scope (per run, not global classloader). No longer nullable — production always gets
    // a store; tests that need to control the store explicitly pass their own instance.
    private val milestoneStateStore: MilestoneStateStore = MilestoneStateStore(),

    // Per-run artifact index for the core.archiveArtifacts →
    // core.artifact.query bridge. Defaults to a fresh InMemoryArtifactIndex
    // when absent; tests that need to control the index explicitly pass
    // their own instance. Lifetime is the coordinator lifetime — i.e. one
    // index per run, shared between the producer (archive with name=...) and
    // the consumer (artifactQuery). When null is supplied (legacy callers),
    // the bridge stays unwired and both Steps fail closed at runtime.
    private val artifactIndex: dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability? = null,

    // The body execution policy authority. Composed in the class body
    // (see bodyPolicyResolver below). A caller may inject another authority to characterize
    // the fail-closed laws. Appended last so existing positional call-sites compile unchanged.
    private val injectedBodyPolicyResolver: BodyPolicyResolver? = null,

    // The body-reentry adapter bound under BODY_INVOKER_CAPABILITY.
    // Per-run lifetime: a fresh adapter is constructed when no caller injects one; the canonical
    // coordinator passes it into CanonicalRuntimeContext for every dispatch so the capability
    // bridge exposes the seam and any registered handler can re-enter the engine through the
    // shared body-child loop. When the caller wires a custom adapter (e.g. tests) the
    // single shared-loop law is preserved: the adapter NEVER iterates body children itself.
    private val bodyInvokerAdapter: CanonicalBodyInvokerAdapter = CanonicalBodyInvokerAdapter(),

    // The open DirectiveRegistry. When bound, every stage's declared
    // directives are admitted against it BEFORE the stage starts (pure
    // decision via StageDirectivePlanner, interpreted here at the effect
    // boundary); a denial fails the run closed with a typed USER failure and
    // the stage body never dispatches. When null, stages declaring directives
    // are denied (no composition = no resolution), and stages without
    // directives behave exactly as before: the seam is additive.
    private val directiveRegistry: dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry? = null,

    /**
     * The facts a gate is evaluated against, read at the effect boundary.
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
     * The PURE decision. Injected so the engine interprets a verdict
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
     * The reattach wait, forwarded to the composed [ExternalSubprocessRecovery].
     * `null`, which is every production call site, leaves the observer on its own real-executor
     * default, so no production behaviour or timing changes. The dependency and its rationale live
     * on the observer's constructor; this parameter is the coordinator's own view of it.
*/
    private val reattachPoll: ((Path, Long) -> Int?)? = null,

    /**
     * What a run's terminal state does to its output, consulted in exactly one place —
     * the `finally` of [run]. [RunOutputRetention] is the only production seam that can turn that
     * into an `OutputPruneIntent`, so the store is never told and cannot learn.
*/
    private val outputRetention: RunOutputRetention? = null,

    /**
     * How a [StageBody.Scripted] body becomes a runnable artifact. Injected because the
     * registry is composed where artifacts are compiled, not here. `null` means this coordinator
     * cannot run scripted bodies, and a scripted stage then says exactly that rather than
     * degrading into a stage that quietly did nothing.
*/
    private val scriptedStageExecution: ScriptedStageExecution? = null,

    /**
     * the per-run Saga seam.
     *
     * When bound, every dispatch of this run can offer the compensation intent port, and the
     * obligations it writes land in the run's own database. When null (every pre-existing
     * call site), the capability stays unexposed and a Step that declares it is refused at
     * admission — the fail-closed direction, never a port that silently forgets a promise.
     */
    sagaBinding: SagaCompensationBinding? = null,
) {
    // The body execution policy authority. The production default
    // composes TWO declared-policy authorities, both fail-closed and neither key-specific:
    //   1. the OPEN StepRegistry (same seam that resolves handlers) — a core or external
    //      plugin Step whose descriptor declares a body resolves here identically;
    //   2. the canonical descriptor table (StepDescriptorRegistry.standard) — the core
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
        // The detector is a PORT: the constructor declares the interface and the resolver declares
        // it too, so the composition hands the interface straight through. It used to narrow it to
        // [StrictFingerprintDivergenceDetector] here, which made every other implementation die
        // with a ClassCastException at CONSTRUCTION — before the run had said anything.
        divergenceDetector = divergenceDetector,
        effectReplayPolicy = effectReplayPolicy,
        journal = journal,
        // The external-subprocess compatibility hook is COMPOSED here and
        // reaches the resolver only as a port. The resolver decides when recovery applies; this
        // adapter owns the only place that knows a control directory and a live process exist.
        runningSubprocessRecovery = ExternalSubprocessRecovery(clock, controlDirRoot, reattachPoll),
    )

    // Typed input preparation behind a narrow collaborator.
    private val typedInputPreparation: DurableTypedInputPreparation = DurableTypedInputPreparation(
        stepRegistry = stepRegistry,
        milestoneStateStore = milestoneStateStore,
        artifactIndex = artifactIndex,
        capabilityContributor = capabilityContributor, // H2b: the same set EXECUTE will see
    )


    /**
     * Effective pre-decode metadata authority. An explicit [stepMetadataResolver] wins
     * otherwise an injected [stepRegistry] opts into the composite (core keys -> legacy authority,
     * other keys -> registry metadata, unknown -> hard defect); otherwise the legacy core authority.
     * The durable coordinator only ever consumes [StepMetadata]; it does not know where it came from.
*/
    private val metadataResolver: StepMetadataResolver = stepMetadataResolver
        ?: if (stepRegistry != null) RegistryStepMetadataResolver.composite(stepRegistry)
        else CoreLegacyStepMetadataResolver

    /**
     * Effective step executor, routed through the new common seam. The production default
     * adapts the injected legacy [invocationExecutor] (or the legacy dispatcher) behind
     * [CommonExecutionBoundary], preserving behaviour exactly; a caller may inject its own boundary
     * for dual characterization. The durable coordinator only ever hands an opaque [PreparedExecution]
     * to this seam and never names a decoded command type.
     * As of.5.7 the call is routed through [ExecutionBoundaryFactory.build] (the named
     * producer seam) directly, bypassing the [buildDefaultExecutionBoundary] forwarder. The factory
     * is the structural-switch authority (binary `if (stepRegistry != null)` preserved bit-a-bit); the
     * forwarder is kept as a source-compatibility shim for any external callers.
*/
    private val executionBoundary: CommonExecutionBoundary = commonExecutionBoundary

        // Structural switch lives in ExecutionBoundaryFactory.build
        // (binary legacy-bit-equivalent: registry present -> SeamedRouting; otherwise -> LegacyOnly).
        // Pass milestoneStateStore so RegistryExecutionBoundary can provide
        // MILESTONE_OPERATIONS_CAPABILITY during handler execution.
        // Pass artifactIndex so the producer (core.archiveArtifacts)
        // and the consumer (core.artifact.query) share one instance per run.
        ?: ExecutionBoundaryFactory.build(
            dispatcher = dispatcher,
            invocationExecutor = invocationExecutor,
            stepRegistry = stepRegistry,
            milestoneStateStore = milestoneStateStore,
            artifactIndex = artifactIndex,
            capabilityContributor = capabilityContributor,
        )

    // The executor folds ONE operation; the traversal owns the cursor.
    private val stepExecutor: DurableStepExecutor = DurableStepExecutor(
        eventSink = eventSink,
        executionBoundary = executionBoundary,
        journal = journal,
    )

    // The run-lifecycle bookends and the running outcome live in
    // the extracted engine (same events, same ordering, same quirks).
    private val runLifecycle = RunLifecycleEngine(eventSink)
    // The BEFORE_STAGE directive seam. It admits, decodes, composes
    // evaluates and observes, then returns a closed verdict; this class keeps the run's control
    // flow, because only the owner of the loop may decide what the run does next.
    private val beforeStageDirectives = BeforeStageDirectiveEngine(eventSink, gateContext, gateEvaluator, capabilityContributor)
    // The INTERPRETATION of a recovery resolution. The DECISION already
    // lives in invocationResolver; this engine performs the journal write and the lifecycle
    // events that a resolution names, and reports ProceedToExecution for the one resolution
    // That is not a recovery case.: it advances no cursor — that is traversal state
    private val recoveryInterpretation = RecoveryInterpretationEngine(eventSink, journal)
    private val bodyExecutionEngine = BodyExecutionEngine(
        eventSink,
        clock,
        bodyInvokerAdapter,
        retryControlJournal,
        waitUntilControlJournal,
    )
    // The three OWNERS the dispatch engine observes. The coordinator builds them here because
    // it owns the ports they wrap; the engine never sees the ports themselves.
    //   PREPARACION — the durable identity, the control overlay, the metadata and the fingerprint.
    //   ADMISION — the credential lease.
    //   AVANCE — the canonical cursor: what moves it and the write that moves it.
    private val dispatchPreparation = CanonicalDispatchPreparation(metadataResolver)
    private val leaseAdmission = CredentialLeaseAdmission(credentialScopePort)
    private val cursorAdvance = CanonicalCursorAdvance(cursorStore)
    // The step-execution spine. `dispatch` falls through to `dispatchBody`
    // which hands the body back to the body engine with this class's own `dispatchChild` as the
    // re-entry reference, and a parallel branch walks its steps through `dispatch` again. That
    // recursion is the unit of cohesion, so the whole closed cycle moves together and the
    // coordinator keeps only the run's own control flow.
    private val stepDispatch = StepDispatchEngine(        journal = journal,
        eventSink = eventSink,
        clock = clock,
        leaseAdmission = leaseAdmission,
        dispatchPreparation = dispatchPreparation,
        typedInputPreparation = typedInputPreparation,
        stepExecutor = stepExecutor,
        invocationResolver = invocationResolver,
        recoveryInterpretation = recoveryInterpretation,
        bodyExecutionEngine = bodyExecutionEngine,
        bodyPolicyResolver = bodyPolicyResolver,
        // The dispatch engine composes the emitter from this registry, sink and clock.
        eventRegistry = eventRegistry,
        outputWakeups = wakeups,
        cursorAdvance = cursorAdvance,
        stepRegistry = stepRegistry,
        bodyInvokerAdapter = bodyInvokerAdapter,
        controlDirRoot = controlDirRoot,
        workspaceBase = workspaceBase,
        secretPatternRegistry = secretPatternRegistry,
        // the per-run Saga seam, forwarded so the dispatch can build the
        // invocation-bound intent port.
        sagaBinding = sagaBinding,
    )
    // A parallel stage is a composite with its own durable aggregate, not a
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
    // What a stage DOES once the run has decided to start it — its linear
    // body, its continuation, and its `post` finalizers. A stage reports a closed verdict and
    // the context it leaves behind; only this loop decides what the run does next.
    private val stageExecution = StageExecutionEngine(
        stepDispatch = stepDispatch,
        runLifecycle = runLifecycle,
        eventSink = eventSink,
    )

    suspend fun run(pipeline: CompiledPipeline, runId: RunId): RunOutcome {
        runLifecycle.openRun(pipeline, runId)
        // Ambient execution context is a run-local immutable value threaded
        // explicitly through dispatch; no coordinator field, no restore idiom.
        var ambient = ExecutionContext.EMPTY


        try {
            stagesLoop@ for (stageIndex in pipeline.stages.indices) {
                val stage = pipeline.stages[stageIndex]

                // The BEFORE_STAGE directive seam is interpreted by
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
                        // A skip is itself a stage outcome. The finalizers that fire for
                        // it are exactly the ones the pure planner selects for Skipped
                        // (always + cleanup); if the author declared none, this is a no-op.
                        stageExecution.runPostBlock(
                            stage = stage,
                            stageIndex = stageIndex,
                            stageOutcome = StageOutcome.Skipped,
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
                // The control directory root always exists, so the stage
                // workspace always does too: there is no stage that runs
                // without one.
                val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
                val workspacePath = resolver.resolve(stage.name, stageIndex)
                try {
                    resolver.ensureCreated(workspacePath)
                } catch (e: java.io.IOException) {
                    runLifecycle.fold(RunOutcome.Failure(
                        PipelineFailure(
                            dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                            "Failed to create stage workspace '${workspacePath}': ${e.message}"
                        ),
                    ))
                    return@run runLifecycle.outcome()
                }
                val stageBaseOptions = shOptions.copy(workspaceRoot = workspacePath)
                val stageShOptions = stage.projectShellOptions(stageBaseOptions)
                // Parallel stages re-enter the SAME dispatch spine —
                // each branch is dispatched as a bodyPath-rooted branch with its
                // own branchIndex (deterministic durable identity via OpId -b{N}
                // journal keys). No second engine, no ParallelFrameExecutor.
                if (steps == null && stage.body is StageBody.Parallel) {
                    // Workspace creation is required before branch dispatch
                    val parallelOutcome = parallelStages.runParallelStage(stage, stageIndex, stageShOptions, runId, ambient)
                    // Post-block + StageFinished are ONE rule, in StageExecutionEngine, and
                    // this arm used to spell it out with outcome STRINGS while the engine passed
                    // StageOutcome — a copy that had already drifted. It is a call now, and it is
                    // the SAME call the scripted body makes.
                    when (val verdict = stageExecution.finalizeStageOutcome(
                        parallelOutcome, stage, stageIndex, runId, stageShOptions, ambient,
                    )) {
                        is StageExecutionEngine.StageVerdict.Completed -> ambient = verdict.context
                        is StageExecutionEngine.StageVerdict.Abort -> {
                            runLifecycle.fold(RunOutcome.Failure(verdict.failure))
                            return@run runLifecycle.outcome()
                        }
                    }
                    continue@stagesLoop
                }
                // A scripted body enters the SAME spine. The continuation rule and the
                // finalisation live in StageExecutionEngine, so this arm is a call, not a copy.
                if (steps == null && stage.body is StageBody.Scripted) {
                    val scripted = scriptedStageExecution ?: throw EngineInvariantViolation(
                        "stage '${stage.name}' has a scripted body but no ScriptedStageExecution is " +
                            "bound, so it cannot be resolved to an artifact. Bind it in the composition root.",
                    )
                    when (val verdict = stageExecution.runScriptedStage(
                        stage, stageIndex, scripted, stageShOptions, runId, ambient,
                    )) {
                        is StageExecutionEngine.StageVerdict.Completed -> ambient = verdict.context
                        is StageExecutionEngine.StageVerdict.Abort -> {
                            runLifecycle.fold(RunOutcome.Failure(verdict.failure))
                            return@run runLifecycle.outcome()
                        }
                    }
                    continue@stagesLoop
                }
                // Fail closed on a body shape this spine does not run, and NAME the shape.
                val steps1 = steps ?: throw IllegalArgumentException("Unsupported ${stage.body::class.simpleName} in '${stage.name}'")
                // Per-stage workspaceRoot override at dispatch boundary
                // Workspace pre-creation - ensure stage workspace exists before shell dispatch
                // Restore stage bookends lost in the spine migration.
                // The canonical coordinator emits StageStarted at entry and StageFinished on normal
                // completion (success/unstable). An aborting stage returns before StageFinished;
                // RunFinished carries the failure.
                // The stage body, its continuation and its `post`
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
            // The closing bookend is the engine's; the correlation
            // invariant (RunFinished only if RunStarted was emitted) lives there.
            runLifecycle.closeRun(runId)
            // The run has ENDED, the only moment that can authorise discarding its
            // output. Not gated on the bookend invariant: that asks whether RunFinished may be EMITTED.
            outputRetention?.onRunTerminal(runId)
        }
        return runLifecycle.outcome()
    }

    private companion object {
        const val REATTACH_TIMEOUT_MS = 60_000L
    }
}

/**
 * Read-only [StepRegistry] view over a nullable registry. Used by the
 * composed body-policy authority so callers that never wired a registry keep the
 * historical behaviour (open-registry lookup returns UnknownStep and the canonical
 * core table answers).
 * This adapter used to implement both `register` overloads by throwing
 * `UnsupportedOperationException`. That was the "freeze boolean" shape — a mutable-shaped
 * object carrying a flag that said it was immutable — and a caller could still reach the
 * method and be told so at runtime. [StepRegistry] no longer declares `register` at all, so
 * the adapter has nothing to refuse and the invariant is a property of the type.
*/
private class NoopStepRegistry(private val delegate: StepRegistry?) : StepRegistry {
    override fun definition(key: dev.rubentxu.pipeline.v2.domain.PluginStepId) =
        delegate?.definition(key)

    override fun providerOf(key: dev.rubentxu.pipeline.v2.domain.PluginStepId) =
        delegate?.providerOf(key)

    override fun contains(key: dev.rubentxu.pipeline.v2.domain.PluginStepId): Boolean =
        delegate?.contains(key) ?: false

    override fun keys(): Set<dev.rubentxu.pipeline.v2.domain.PluginStepId> =
        delegate?.keys() ?: emptySet()
}

