package dev.rubentxu.pipeline.v2.application.durable


import dev.rubentxu.pipeline.v2.application.CanonicalRunPolicy
import dev.rubentxu.pipeline.v2.application.CanonicalRunState
import dev.rubentxu.pipeline.v2.application.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.application.LiveSpanHandle
import dev.rubentxu.pipeline.v2.application.LiveSpanNames
import dev.rubentxu.pipeline.v2.application.LiveSpanStatus
import dev.rubentxu.pipeline.v2.application.LiveSpans
import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.application.PipelineTelemetry
import dev.rubentxu.pipeline.v2.application.PreResolvedComposition
import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.liveSpanParentOf
import dev.rubentxu.pipeline.v2.application.liveStatusOfRunOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.CompositeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.RegistryBodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.post.StageOutcome
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialLeaseAdmission
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.CompiledPipelineValidator
import dev.rubentxu.pipeline.v2.domain.BlockNestingExceededException
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.directive.GateContext
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEvaluator
import dev.rubentxu.pipeline.v2.credentials.executor.BasicCredentialsCapabilityContributor

/**
 * Executes the linear canonical core subset with the durable journal and replay cursor.
 *
 * ## What this class receives, and why so little
 *
 * The constructor takes TEN parameters, and each one is a fact the run cannot derive:
 *
 * ```text
 * state              where the run's durable state lives (journal, cursor, sink, saga)
 * policy             what the run was GIVEN (clock, replay policy, control root, sandbox, ...)
 * composition        what the run resolves against (steps, directives, events, capabilities)
 * credentialScopePort  the credential seam
 * divergenceDetector   the divergence POLICY, as a port
 * gateContext          the facts a gate is read against
 * retryControlJournal  the durable retry aggregate, or null for the inline loop
 * waitUntilControlJournal  the durable waitUntil aggregate, or null for the inline loop
 * scriptedStageExecution   how a scripted body becomes an artifact, or null
 * telemetry             the live tracing seam
 * ```
 *
 * Only the last two are kept as properties of this class: they are the only ones READ while the
 * run executes. The other eight are consumed while the run is BUILT — they are inputs of the
 * construction, not state the coordinator carries — so they are constructor parameters and nothing
 * more. A fact this class keeps would be a second copy of a fact the composition already owns.
 *
 * Everything else the run uses is DERIVED inside this class, and the derivation is where the
 * guarantees live. The three groups above are the reasons:
 *
 * - **[CanonicalRunState]** owns the run's durable state. Two facts that used to be separate
 *   constructor parameters — the journal and the cursor store — are one fact ("this run's
 *   durable spine"), and they cannot now be supplied by two different worlds.
 * - **[CanonicalRunPolicy]** owns what the run was given. The shell transport and the output
 *   retention are consequences of this group, not inputs: a caller could previously hand the
 *   coordinator a workspace root that was not the run's, or a retention policy that deleted
 *   what the product keeps. Both are now computed by
 *   [canonicalShellOptions] and [canonicalOutputRetention].
 * - **[PreResolvedComposition]** owns what the run resolves against. The Step registry, the
 *   Directive registry, the Event registry and the plugin capabilities all come from ONE
 *   composition, resolved under ONE classloader swap, so PREPARE and EXECUTE cannot see
 *   different registries.
 *
 * ## The one execution, the one capability set
 *
 * The capability contributor is built HERE, once, from the composition's plugin contributions
 * and the policy's credential provider. It is then handed to the SAME instance in all four
 * places that need it — PREPARE, EXECUTE, the directive seam and the runtime context — so there
 * is exactly one set of capabilities in the run. Two sets would mean a Step admitted by one and
 * refused by the other.
 *
 * ## What is NOT a parameter, and why
 *
 * - The **metadata resolver**: the composite over the run's own registry IS the authority.
 * - The **body policy resolver**: it blends the open registry with the canonical core table,
 *   and both are facts this class already holds.
 * - The **body invoker adapter**: one per run, and nothing else may construct one.
 * - The **milestone store** and the **artifact index**: both are per-run and side-effect free.
 * - The **reattach poll**: the recovery observer's own default is the real executor; a
 *   parameter here was a second spelling of the same thing.
 * - The **gate evaluator**: the decision is PURE and lives in the domain; the engine
 *   interprets it. A caller may not bring its own.
 *
 * ## The nulls that remain
 *
 * The three nullable parameters are **absences of a capability**, not defaults: a run with no
 * retry control journal keeps the inline retry loop, a run with no waitUntil journal keeps the
 * inline polling, and a run with no scripted execution says so instead of degrading into a
 * stage that quietly did nothing. There is no fourth kind of null here: every other fact has
 * exactly one answer, and this class computes it.
 */
internal class CanonicalDurableRunCoordinator(
    state: CanonicalRunState,
    policy: CanonicalRunPolicy,
    composition: PreResolvedComposition,
    credentialScopePort: CredentialScopePort,
    divergenceDetector: DivergenceDetector,
    gateContext: (EnvironmentSpec) -> GateContext,
    retryControlJournal: FileBasedRetryControlJournal?,
    waitUntilControlJournal: WaitUntilControlJournal?,
    private val scriptedStageExecution: ScriptedStageExecution?,
    private val telemetry: PipelineTelemetry,
) {
    // The run's durable spine, the ports it was given and the values derived from them.
    private val journal = state.journal
    private val cursorStore = state.cursorStore
    private val eventSink = state.eventSink
    private val clock = policy.clock
    private val controlDirRoot = policy.controlDirRoot
    private val workspaceBase = policy.workspace.base

    // The registries the run resolves against. ONE composition, resolved under ONE classloader
    // swap: the Step registry that resolves handlers, the Directive registry that admits the
    // stage's directives and the Event registry the run writes against are the same three
    // registries PREPARE and EXECUTE see.
    private val stepRegistry: StepRegistry = composition.steps
    private val directiveRegistry = composition.directives
    private val eventRegistry = composition.events

    /**
     * The ONE capability set of this run.
     *
     * The plugin side comes from the composition, resolved under the same classloader swap as
     * the steps these capabilities back. The credential side stays here because it is per-run
     * state — it wraps the store THIS invocation opened, so it cannot come from classpath
     * discovery. The two never collide: a plugin deliberately contributes only its own
     * transport.
     */
    private val capabilityContributor: RuntimeCapabilityContributor = CompositeCapabilityContributor(
        composition.capabilityContributors +
            BasicCredentialsCapabilityContributor(policy.credentialProvider),
    )

    // The shell transport and the output retention are CONSEQUENCES of the policy, not inputs.
    private val shOptions = canonicalShellOptions(policy)
    private val outputRetention = canonicalOutputRetention(
        store = { OutputPlaneProvider.storeFor(policy.controlDirRoot) },
        telemetry = telemetry,
    )

    // The per-run aggregates. Each is created ONCE and shared by every holder that needs it,
    // because each of them is a shared surface, not a value:
    //   milestoneStateStore — the milestone state the run's handlers read and write;
    //   artifactIndex      — the bridge between the producer (core.archiveArtifacts) and the
    //                        consumer (core.artifact.query) WITHIN this run;
    //   bodyInvokerAdapter — the one adapter the body engine, the runtime contexts and the
    //                        body dispatch all speak through.
    // Creating one per holder would give the run two artifact indexes and two body adapters,
    // which is the defect this grouping removes.
    private val milestoneStateStore = MilestoneStateStore()
    private val artifactIndex = ArtifactIndexAdapter.build()
    private val bodyInvokerAdapter = CanonicalBodyInvokerAdapter()

    /**
     * The collaborators the capability table is built from, held ONCE.
     *
     * Admission (PREPARE) and execution (EXECUTE) both read the capability
     * table, and both read it through this holder, so the two moments cannot
     * disagree about which capabilities exist. The holder is the only place
     * that decides it.
     */
    private val capabilityBindings = CanonicalCapabilityBindings(
        milestoneStateStore = milestoneStateStore,
        artifactIndex = artifactIndex,
        capabilityContributor = capabilityContributor,
    )

    // The body execution policy authority. The production default
    // composes TWO declared-policy authorities, both fail-closed and neither key-specific:
    //   1. the OPEN StepRegistry (same seam that resolves handlers) — a core or external
    //      plugin Step whose descriptor declares a body resolves here identically;
    //   2. the canonical descriptor table (StepDescriptorRegistry.standard) — the core
    //      block Steps without a registered handler (dir/withEnv/timeout/retry/...).
    private val bodyPolicyResolver: BodyPolicyResolver = run {
        // FOLDING is admitted: the canonical body engine implements the two folds
        // (containment and output decoration), so a control scope is executed by the
        // engine that owns its semantics instead of by a translated artifact.
        val canonical = StepDescriptorRegistry.standard()
            .bodyPolicyResolver(BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING_FOLDING)
        val fromRegistry = RegistryBodyPolicyResolver(
            registry = stepRegistry,
            support = BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING_FOLDING,
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
    // The durable row of ONE invocation. It is the single holder of the journal port on this
    // surface: the resolver READS the row, the executor ADMITS and SETTLES it, and the recovery
    // interpreter SETTLES the recovered terminal. Three writers, one shape, one owner.
    private val invocationRecords = DurableInvocationRecord(journal)
    private val invocationResolver: DurableInvocationResolver = DurableInvocationResolver(
        // The detector is a PORT: the constructor declares the interface and the resolver declares
        // it too, so the composition hands the interface straight through. It used to narrow it to
        // [StrictFingerprintDivergenceDetector] here, which made every other implementation die
        // with a ClassCastException at CONSTRUCTION — before the run had said anything.
        divergenceDetector = divergenceDetector,
        effectReplayPolicy = policy.effectReplayPolicy,
        records = invocationRecords,
        // The external-subprocess compatibility hook is COMPOSED here and
        // reaches the resolver only as a port. The resolver decides when recovery applies; this
        // adapter owns the only place that knows a control directory and a live process exist.
        runningSubprocessRecovery = ExternalSubprocessRecovery(clock, controlDirRoot),
    )

    // Typed input preparation behind a narrow collaborator.
    private val typedInputPreparation: DurableTypedInputPreparation = DurableTypedInputPreparation(
        stepRegistry = stepRegistry,
        // The SAME holder EXECUTE will consult: one capability set, two moments.
        capabilityBindings = capabilityBindings,
    )

    /**
     * Effective pre-decode metadata authority: the composite over the run's OWN registry
     * (core keys -> the registry's own core definitions, other keys -> registry metadata,
     * unknown -> hard defect).
     * The durable coordinator only ever consumes [StepMetadata]; it does not know where it came from.
    */
    private val metadataResolver: StepMetadataResolver = RegistryStepMetadataResolver.composite(stepRegistry)

    /**
     * The effective step executor. There is exactly ONE seam and exactly ONE implementation
     * of it, so the coordinator names it directly instead of choosing between candidates:
     * [RegistryExecutionBoundary] over the registry it already owns.
     * The coordinator only ever hands an opaque [PreparedRegistryExecution] to this seam and
     * never names a decoded command type.
    */
    private val executionBoundary: CommonExecutionBoundary = RegistryExecutionBoundary.adapt(
        // The SAME holder PREPARE consulted: one capability set, two moments.
        capabilityBindings = capabilityBindings,
    )

    // The executor folds ONE operation; the traversal owns the cursor.
    private val stepExecutor: DurableStepExecutor = DurableStepExecutor(
        eventSink = eventSink,
        executionBoundary = executionBoundary,
        records = invocationRecords,
        telemetry = telemetry,
    )

    // The run-lifecycle bookends and the running outcome live in
    // the extracted engine (same events, same ordering, same quirks).
    private val runLifecycle = RunLifecycleEngine(eventSink, telemetry)
    // The BEFORE_STAGE directive seam. It admits, decodes, composes
    // evaluates and observes, then returns a closed verdict; this class keeps the run's control
    // flow, because only the owner of the loop may decide what the run does next.
    // The gate's DECISION is pure and lives in the domain; the engine interprets it.
    private val beforeStageDirectives = BeforeStageDirectiveEngine(
        eventSink,
        gateContext,
        WhenPredicateEvaluator::evaluate,
        capabilityContributor,
    )
    // The INTERPRETATION of a recovery resolution. The DECISION already
    // lives in invocationResolver; this engine performs the journal write and the lifecycle
    // events that a resolution names, and reports ProceedToExecution for the one resolution
    // That is not a recovery case.: it advances no cursor — that is traversal state
    private val recoveryInterpretation = RecoveryInterpretationEngine(eventSink, invocationRecords, telemetry)
    private val bodyExecutionEngine = BodyExecutionEngine(
        eventSink,
        clock,
        bodyInvokerAdapter,
        telemetry,
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
    // The two body/context owners the spine observes, built on the same ports the
    // coordinator owns. The spine asks them and observes the answers; it never wires a
    // SAGA port, an emitter or a body policy of its own.
    private val runtimeContexts = CanonicalRuntimeContextFactory(
        stepRegistry = stepRegistry,
        bodyInvokerAdapter = bodyInvokerAdapter,
        eventRegistry = eventRegistry,
        eventSink = eventSink,
        clock = clock,
        outputWakeups = state.outputWakeups,
        controlDirRoot = controlDirRoot,
        workspaceBase = workspaceBase,
        secretPatternRegistry = policy.secretPatternRegistry,
        sagaBinding = state.sagaBinding,
    )
    private val bodyDispatch = CanonicalBodyDispatch(
        bodyPolicyResolver = bodyPolicyResolver,
        bodyExecutionEngine = bodyExecutionEngine,
        bodyInvokerAdapter = bodyInvokerAdapter,
        leaseAdmission = leaseAdmission,
        eventSink = eventSink,
        clock = clock,
    )
    // The step-execution spine. A body child walks it again through the engine's own
    // `dispatchChild` — handed to `bodyDispatch` as the single re-entry reference — and a
    // parallel branch walks its steps through `dispatch` again. That recursion is the unit of
    // cohesion, so the whole closed cycle moves together and the coordinator keeps only the
    // run's own control flow.
    private val stepDispatch = StepDispatchEngine(
        dispatchPreparation = dispatchPreparation,
        typedInputPreparation = typedInputPreparation,
        stepExecutor = stepExecutor,
        invocationResolver = invocationResolver,
        recoveryInterpretation = recoveryInterpretation,
        cursorAdvance = cursorAdvance,
        stepRegistry = stepRegistry,
        runtimeContexts = runtimeContexts,
        bodyDispatch = bodyDispatch,
        telemetry = telemetry,
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

    /**
     * The run, observed.
     *
     * The span is opened here and not inside the body because the span is
     * about the WHOLE run: the validation that can refuse it, the stages it
     * executes and the outcome it produces. A span that started after the
     * validation would say nothing about a run that never started.
     */
    suspend fun run(pipeline: CompiledPipeline, runId: RunId): RunOutcome {
        val span = LiveSpans.open(
            telemetry = telemetry,
            name = LiveSpanNames.RUN,
            parent = telemetry.sessionParent,
            attributes = listOf(LiveSpanAttributes.runId(runId.value)),
        )
        val outcome = try {
            runBody(pipeline, runId, span)
        } catch (throwable: Throwable) {
            LiveSpans.end(span, LiveSpanStatus.Failed)
            throw throwable
        }
        LiveSpans.end(span, liveStatusOfRunOutcome(outcome))
        return outcome
    }

    /**
     * The body of the run, without the observation around it.
     *
     * The body is the run's control flow, and this wrapper is its
     * observation. Splitting them keeps the loop free of telemetry: the
     * loop's `return@runBody` labels are the loop's own, and the span cannot
     * be ended by a branch that does not know about it.
     */
    private suspend fun runBody(
        pipeline: CompiledPipeline,
        runId: RunId,
        runSpan: LiveSpanHandle?,
    ): RunOutcome {
        try {
            CompiledPipelineValidator.validate(pipeline, bodyPolicyResolver)
        } catch (e: BlockNestingExceededException) {
            return RunOutcome.Failure(
                PipelineFailure(FailureKind.USER, "Invalid compiled pipeline: ${e.message}"),
            )
        } catch (e: IllegalArgumentException) {
            return RunOutcome.Failure(
                PipelineFailure(FailureKind.USER, "Invalid compiled pipeline: ${e.message}"),
            )
        }
        runLifecycle.openRun(pipeline, runId, runSpan)
        // Ambient execution context is a run-local immutable value threaded
        // explicitly through dispatch; no coordinator field, no restore idiom.
        var ambient = ExecutionContext.EMPTY


        try {
            stagesLoop@ for (stageIndex in pipeline.stages.indices) {
                val stage = pipeline.stages[stageIndex]

                // The BEFORE_STAGE directive seam is interpreted by
                // BeforeStageDirectiveEngine, which admits, decodes, composes, evaluates and
                // OBSERVES, then returns a closed verdict. Only this loop owns the run's control
                // flow: a collaborator may not write `return@runBody` or `continue@stagesLoop`.
                when (
                    val verdict = beforeStageDirectives.interpret(stage, stageIndex, runId, directiveRegistry)
                ) {
                    BeforeStageDirectiveEngine.Verdict.Admitted -> Unit

                    is BeforeStageDirectiveEngine.Verdict.Denied -> {
                        runLifecycle.fold(RunOutcome.Failure(PipelineFailure(
                            verdict.kind,
                            verdict.reason,
                        )))
                        return@runBody runLifecycle.outcome()
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
                            return@runBody runLifecycle.outcome()
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
                            FailureKind.INFRASTRUCTURE,
                            "Failed to create stage workspace '${workspacePath}': ${e.message}"
                        ),
                    ))
                    return@runBody runLifecycle.outcome()
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
                            return@runBody runLifecycle.outcome()
                        }
                    }
                    continue@stagesLoop
                }
                // A scripted body enters the SAME spine. The continuation rule and the
                // finalisation live in StageExecutionEngine, so this arm is a call, not a copy.
                if (steps == null && stage.body is StageBody.Scripted) {
                    val scripted = scriptedStageExecution ?: throw EngineInvariantViolation(
                        "stage '${stage.name}' has a scripted body but this run carries no " +
                            "ScriptedStageExecution, so it cannot be resolved to an artifact. The " +
                            "scripted frontend runs on its own runner, which composes the same " +
                            "durable authorities; a scripted body inside a canonical run needs " +
                            "this seam bound.",
                    )
                    when (val verdict = stageExecution.runScriptedStage(
                        stage, stageIndex, scripted, stageShOptions, runId, ambient,
                    )) {
                        is StageExecutionEngine.StageVerdict.Completed -> ambient = verdict.context
                        is StageExecutionEngine.StageVerdict.Abort -> {
                            runLifecycle.fold(RunOutcome.Failure(verdict.failure))
                            return@runBody runLifecycle.outcome()
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
                // with this typed reason. A collaborator may not write return@runBody.
                when (val verdict = stageExecution.runLinearStage(stage, stageIndex, steps1, stageShOptions, runId, ambient)) {
                    is StageExecutionEngine.StageVerdict.Completed -> ambient = verdict.context

                    is StageExecutionEngine.StageVerdict.Abort -> {
                        runLifecycle.fold(RunOutcome.Failure(verdict.failure))
                        return@runBody runLifecycle.outcome()
                    }
                }
            }
            // Success: fall through to finally and return
        } catch (e: Exception) {
            // Invariants are engine failures, never ordinary infrastructure outcomes.
            if (e is IllegalStateException || e is EngineInvariantViolation) throw e
            runLifecycle.fold(RunOutcome.Failure(
                PipelineFailure(
                    FailureKind.INFRASTRUCTURE,
                    "Unexpected error during pipeline run: ${e.message}"
                ),
            ))
        } finally {
            // A stage that threw before its finalizer ran still has an OPEN
            // span. Closing them here is what keeps the trace from showing a
            // stage that never ended — the same reason the run's bookends are
            // closed in a `finally` and not on the happy path only.
            runLifecycle.closeOpenStages()
            // The closing bookend is the engine's; the correlation
            // invariant (RunFinished only if RunStarted was emitted) lives there.
            runLifecycle.closeRun(runId)
            // The run has ENDED, the only moment that can authorise discarding its
            // output. Not gated on the bookend invariant: that asks whether RunFinished may be EMITTED.
            outputRetention.onRunTerminal(runId, liveSpanParentOf(runSpan))
        }
        return runLifecycle.outcome()
    }
}
