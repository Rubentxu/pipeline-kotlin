package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.directive.GateCompositionDecision
import dev.rubentxu.pipeline.v2.domain.directive.GateCompositionPlanner

import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.StructuralPreparation
import dev.rubentxu.pipeline.v2.application.StructuralOverlay
import dev.rubentxu.pipeline.v2.application.StructuralOverlayProjection
import dev.rubentxu.pipeline.v2.application.CanonicalStructuralPreparation
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata
import dev.rubentxu.pipeline.v2.application.CoreLegacyStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection
import dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity
import dev.rubentxu.pipeline.v2.domain.step.BodyContinuation
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicyShape
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.RegistryBodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.application.durable.credentials.AcquiredCredentialScope
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialBindingsPayload
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeCleanup
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
// WU-G5R.5: durable waitUntil reconciliation driver and identity factory.
import dev.rubentxu.pipeline.v2.application.durable.WaitUntilIdentityFactory

// RETRY-D: control journal + reconciliation driver (ADR-0075 §11).
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory
import dev.rubentxu.pipeline.v2.application.durable.retry.RetryReconciliationDriver
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.BranchTerminal
import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.domain.durable.ParallelAggregateId
import dev.rubentxu.pipeline.v2.domain.durable.ParallelAggregateSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.ParallelBranchChildSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision
import dev.rubentxu.pipeline.v2.domain.durable.ParallelReconciler
import dev.rubentxu.pipeline.v2.domain.durable.ParallelReconciliationInput
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.ContextTransition
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.domain.post.PostPlanner
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.nio.file.Path
import java.nio.file.Files
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
        clock = clock,
        journal = journal,
        controlDirRoot = controlDirRoot,
    )

    // WU-RP-031 E3: typed input preparation behind a narrow collaborator.
    private val typedInputPreparation: DurableTypedInputPreparation = DurableTypedInputPreparation(
        stepRegistry = stepRegistry,
        milestoneStateStore = milestoneStateStore,
        artifactIndex = artifactIndex,
    )


    /** Active context stack for body scope tracking (EM-4). */

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
        )

    // WU-RP-031 E4: effective execution + durable folding behind a narrow collaborator.
    private val stepExecutor: DurableStepExecutor = DurableStepExecutor(
        eventSink = eventSink,
        executionBoundary = executionBoundary,
        journal = journal,
        cursorStore = cursorStore,
    )

    // C3 / WU-PR-017: the run-lifecycle bookends and the running outcome live in
    // the extracted engine (same events, same ordering, same quirks).
    private val runLifecycle = RunLifecycleEngine(eventSink)
    private val bodyExecutionEngine = BodyExecutionEngine(
        eventSink,
        clock,
        bodyInvokerAdapter,
        retryControlJournal,
        waitUntilControlJournal,
    )

    suspend fun run(pipeline: CompiledPipeline, runId: RunId): RunOutcome {
        runLifecycle.openRun(pipeline, runId)
        // CTX-P2: ambient execution context is a run-local immutable value threaded
        // explicitly through dispatch; no coordinator field, no restore idiom.
        var ambient = ExecutionContext.EMPTY


        try {
            stagesLoop@ for (stageIndex in pipeline.stages.indices) {
                val stage = pipeline.stages[stageIndex]

                // S1-B: directive admission is a PURE decision taken BEFORE any
                // stage effect (workspace creation, StageStarted, dispatch). A
                // denial is fail-closed: the stage never starts and the run
                // fails with the planner's typed diagnostic. The coordinator
                // only INTERPRETS the decision; it never re-derives admission.
                var beforeStageSeam: List<dev.rubentxu.pipeline.v2.domain.directive.AdmittedDirective> = emptyList()
                when (val directiveDecision = directiveRegistry
                    ?.let { dev.rubentxu.pipeline.v2.domain.directive.StageDirectivePlanner.decide(it, stage) }) {
                    is dev.rubentxu.pipeline.v2.domain.directive.StageDirectiveDecision.Denied -> {
                        // S1-C: typed denial observability BEFORE the run aborts.
                        eventSink.append(
                            dev.rubentxu.pipeline.v2.events.DirectiveDenied(
                                eventId = UUID.randomUUID().toString(),
                                runId = runId.value,
                                sequence = 0L,
                                occurredAt = Instant.now(),
                                stageIndex = stageIndex,
                                stageName = stage.name,
                                directiveKey = directiveDecision.key.value,
                                reason = directiveDecision.reason,
                            ),
                        )
                        runLifecycle.fold(RunOutcome.Failure(
                            PipelineFailure(
                                dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
                                directiveDecision.reason,
                            ),
                        ))
                        return@run runLifecycle.outcome()
                    }
                    // S1-C: one typed admitted event per declared directive,
                    // emitted at the same seam that will later interpret them.
                    is dev.rubentxu.pipeline.v2.domain.directive.StageDirectiveDecision.Permitted -> {
                        directiveDecision.phases.values.flatten().forEach { admitted ->
                            eventSink.append(
                                dev.rubentxu.pipeline.v2.events.DirectiveAdmitted(
                                    eventId = UUID.randomUUID().toString(),
                                    runId = runId.value,
                                    sequence = 0L,
                                    occurredAt = Instant.now(),
                                    stageIndex = stageIndex,
                                    stageName = stage.name,
                                    directiveKey = admitted.invocation.key.value,
                                    phase = admitted.phase.name,
                                    policy = when (admitted.policy) {
                                        is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Evaluate -> "evaluate"
                                        is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Gate -> "gate"
                                        is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.ProvideContext -> "provide-context"
                                    },
                                ),
                            )
                        }

                        // S2-C/S2-D: the directives the interpreter will decode in
                        // the BEFORE_STAGE seam are the ones the DECISION admitted
                        // there with policy Gate OR Evaluate — never a re-scan of
                        // the stage declaration. Membership is read from the closed
                        // policy ADT, never from a key. Admitted directives in other
                        // phases or policies (e.g. ProvideContext, D5) are outside
                        // this seam (their interpretation is their own slice's
                        // business).
                        beforeStageSeam = directiveDecision.phases
                            .getValue(dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase.BEFORE_STAGE)
                            .filter {
                                it.policy is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Gate ||
                                    it.policy is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Evaluate
                            }
                    }
                    null -> Unit
                }

                // S2-C/S2-D: interpret the BEFORE_STAGE seam. Decode EVERY
                // admitted Gate|Evaluate directive (its own codec, typed
                // failure), classify into DecodedBeforeStage; the FIRST denial
                // in declaration order fails the stage closed; surviving gates
                // compose (pure GateCompositionPlanner), evaluate ONCE (pure
                // WhenPredicateEvaluator), emit the verdict, then act on it.
                // A decoded Evaluate is observed and discarded: observing and
                // continuing is ALL of its semantics — its decoded value has no
                // consumer here.
                if (beforeStageSeam.isNotEmpty()) {
                    // Decode each seam directive through its own definition codec
                    // (the registry carries the decoder; the engine never switches
                    // on a key), then classify the decode into the seam ADT
                    // WITHOUT an unchecked cast. A definition that declares
                    // policy Gate but decodes to anything other than a
                    // WhenPredicate is a self-contradiction, and the only safe
                    // reading of a contradiction is a typed failure - not a
                    // ClassCastException escaping into the run loop.
                    val decoded: List<DecodedBeforeStage> = beforeStageSeam.map { admitted ->
                        val key = admitted.invocation.key
                        when (val result = directiveRegistry?.find(key)?.decodeAny(admitted.invocation.encodedArguments)) {
                            null -> DecodedBeforeStage.Denied(key, "was admitted but is not resolvable in the registry")

                            is dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Malformed ->
                                DecodedBeforeStage.Denied(
                                    key,
                                    seamDecodeFailureReason(admitted.policy, result.reason),
                                )

                            is dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded -> when (admitted.policy) {
                                is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Gate -> {
                                    val input = result.input
                                    if (input is dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate) {
                                        DecodedBeforeStage.GatePredicate(key, input)
                                    } else {
                                        DecodedBeforeStage.Denied(
                                            key,
                                            "declared policy Gate but decoded to " + input::class.simpleName + ", not a WhenPredicate",
                                        )
                                    }
                                }

                                // Observes and continues; the typed input is
                                // discarded (Directive.kt: "evaluate and
                                // continue... observes but cannot veto").
                                is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Evaluate ->
                                    DecodedBeforeStage.Evaluated(key)

                                // Unreachable through the seam filter (Gate |
                                // Evaluate only), but the policy ADT is closed and
                                // the match must be total: a policy that cannot be
                                // interpreted in this seam is a typed denial, never
                                // a silent continue.
                                is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.ProvideContext ->
                                    DecodedBeforeStage.Denied(
                                        key,
                                        "declared policy provide-context is not interpretable in the BEFORE_STAGE decode seam",
                                    )
                            }
                        }
                    }
                    val deniedDirective: DecodedBeforeStage.Denied? = decoded.firstNotNullOfOrNull { directive ->
                        directive as? DecodedBeforeStage.Denied
                    }
                    if (deniedDirective != null) {
                        val reason = "directive '" + deniedDirective.key.value + "' " + deniedDirective.reason
                        eventSink.append(
                            dev.rubentxu.pipeline.v2.events.DirectiveDenied(
                                eventId = UUID.randomUUID().toString(),
                                runId = runId.value,
                                sequence = 0L,
                                occurredAt = Instant.now(),
                                stageIndex = stageIndex,
                                stageName = stage.name,
                                directiveKey = deniedDirective.key.value,
                                reason = reason,
                            ),
                        )
                        runLifecycle.fold(RunOutcome.Failure(
                            PipelineFailure(
                                dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
                                reason,
                            ),
                        ))
                        return@run runLifecycle.outcome()
                    }

                    // S2-D: only GATE predicates compose; a decoded Evaluate is
                    // observed and dropped by this filter. gateKeys therefore
                    // stays the GATE keys — GateEvaluated keeps gate semantics
                    // (it is never reused for an Evaluate).
                    val predicates = decoded.filterIsInstance<DecodedBeforeStage.GatePredicate>().map { gate ->
                        GateCompositionPlanner.DeclaredGate(gate.key, gate.predicate)
                    }
                    val gateKeys = predicates.map { it.key.value }

                    when (val composition = GateCompositionPlanner.compose(predicates)) {
                        is GateCompositionDecision.Conflicting -> {
                            eventSink.append(
                                dev.rubentxu.pipeline.v2.events.DirectiveDenied(
                                    eventId = UUID.randomUUID().toString(),
                                    runId = runId.value,
                                    sequence = 0L,
                                    occurredAt = Instant.now(),
                                    stageIndex = stageIndex,
                                    stageName = stage.name,
                                    directiveKey = composition.key.value,
                                    reason = composition.reason,
                                ),
                            )
                            runLifecycle.fold(RunOutcome.Failure(
                                PipelineFailure(
                                    dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
                                    composition.reason,
                                ),
                            ))
                            return@run runLifecycle.outcome()
                        }

                        // Empty cannot occur here (beforeStageSeam is non-empty),
                        // but the ADT is exhaustive and the compiler enforces it.
                        GateCompositionDecision.Empty -> Unit

                        is GateCompositionDecision.Composite -> {
                            val context = gateContext(stage.environment)
                            when (val verdict = gateEvaluator(composition.predicate, context)) {
                                is dev.rubentxu.pipeline.v2.domain.directive.GateVerdict.Satisfied -> {
                                    // S2-C: the verdict is observable even when it
                                    // admits the stage — otherwise a satisfied gate
                                    // and an absent gate are indistinguishable.
                                    eventSink.append(
                                        dev.rubentxu.pipeline.v2.events.GateEvaluated(
                                            eventId = UUID.randomUUID().toString(),
                                            runId = runId.value,
                                            sequence = 0L,
                                            occurredAt = Instant.now(),
                                            stageIndex = stageIndex,
                                            stageName = stage.name,
                                            directiveKeys = gateKeys,
                                            satisfied = true,
                                            reason = "",
                                        ),
                                    )
                                }

                                is dev.rubentxu.pipeline.v2.domain.directive.GateVerdict.NotSatisfied -> {
                                    // S2-C: the negative verdict is observable BEFORE
                                    // the skip it causes.
                                    eventSink.append(
                                        dev.rubentxu.pipeline.v2.events.GateEvaluated(
                                            eventId = UUID.randomUUID().toString(),
                                            runId = runId.value,
                                            sequence = 0L,
                                            occurredAt = Instant.now(),
                                            stageIndex = stageIndex,
                                            stageName = stage.name,
                                            directiveKeys = gateKeys,
                                            satisfied = false,
                                            reason = verdict.reason,
                                        ),
                                    )
                                    // A DECIDED negative: skip the stage and say so.
                                    eventSink.append(
                                        dev.rubentxu.pipeline.v2.events.StageSkipped(
                                            eventId = UUID.randomUUID().toString(),
                                            runId = runId.value,
                                            sequence = 0L,
                                            occurredAt = Instant.now(),
                                            stageIndex = stageIndex,
                                            stageName = stage.name,
                                            reason = verdict.reason,
                                        ),
                                    )
                                    // S2-B: a skip is itself a stage outcome. The
                                    // finalizers that fire for it are exactly the
                                    // ones the pure planner selects for Skipped
                                    // (always + cleanup); if the author declared
                                    // none, this is a no-op.
                                    runPostBlock(
                                        stage = stage,
                                        stageIndex = stageIndex,
                                        stageFinishedOutcome = "skipped",
                                        runId = runId,
                                        // The stage never ran, so its workspace was never
                                        // created; finalizers run on the base options with
                                        // the stage's env/timeout projection applied. Using
                                        // raw `shOptions` here would silently drop the
                                        // stage environment from a skipped stage's post.
                                        stageShOptions = stage.projectShellOptions(shOptions),
                                        ambient = ambient,
                                    )?.let { failure ->
                                        runLifecycle.fold(RunOutcome.Failure(failure))
                                        return@run runLifecycle.outcome()
                                    }
                                    continue@stagesLoop
                                }

                                is dev.rubentxu.pipeline.v2.domain.directive.GateVerdict.Unverifiable -> {
                                    // NOT a skip. Nobody could prove the predicate, so
                                    // the run fails closed rather than reporting
                                    // success for work that never ran. The negative
                                    // verdict is still observable.
                                    eventSink.append(
                                        dev.rubentxu.pipeline.v2.events.GateEvaluated(
                                            eventId = UUID.randomUUID().toString(),
                                            runId = runId.value,
                                            sequence = 0L,
                                            occurredAt = Instant.now(),
                                            stageIndex = stageIndex,
                                            stageName = stage.name,
                                            directiveKeys = gateKeys,
                                            satisfied = false,
                                            reason = verdict.reason,
                                        ),
                                    )
                                    val reason = "stage '${stage.name}' gate could not be verified: " +
                                        verdict.reason
                                    eventSink.append(
                                        dev.rubentxu.pipeline.v2.events.DirectiveDenied(
                                            eventId = UUID.randomUUID().toString(),
                                            runId = runId.value,
                                            sequence = 0L,
                                            occurredAt = Instant.now(),
                                            stageIndex = stageIndex,
                                            stageName = stage.name,
                                            directiveKey = gateKeys.first(),
                                            reason = reason,
                                        ),
                                    )
                                    runLifecycle.fold(RunOutcome.Failure(
                                        PipelineFailure(
                                            dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
                                            reason,
                                        ),
                                    ))
                                    return@run runLifecycle.outcome()
                                }
                            }
                        }
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
                    val parallelOutcome = runParallelStage(stage, stageIndex, stageShOptions, runId, ambient)
                    when (val continuation = runLifecycle.decideStageContinuation(parallelOutcome, stage.name, runId.value, ambient)) {
                        CanonicalContinuation.Continue -> {
                            runPostBlock(
                                stage = stage,
                                stageIndex = stageIndex,
                                stageFinishedOutcome = "success",
                                runId = runId,
                                stageShOptions = stageShOptions,
                                ambient = ambient,
                            )?.let { failure ->
                                runLifecycle.fold(RunOutcome.Failure(failure))
                                return@run runLifecycle.outcome()
                            }
                            runLifecycle.stageFinished(runId, stageIndex, stage.name, "success")
                        }
                        CanonicalContinuation.ContinueUnstable -> {
                            runLifecycle.fold(RunOutcome.Unstable)
                            runPostBlock(
                                stage = stage,
                                stageIndex = stageIndex,
                                stageFinishedOutcome = "unstable",
                                runId = runId,
                                stageShOptions = stageShOptions,
                                ambient = ambient,
                            )?.let { failure ->
                                runLifecycle.fold(RunOutcome.Failure(failure))
                                return@run runLifecycle.outcome()
                            }
                            runLifecycle.stageFinished(runId, stageIndex, stage.name, "unstable")
                        }
                        is CanonicalContinuation.Abort -> {
                            // S2-B: a parallel branch failure is still a stage
                            // outcome; failure/always/cleanup finalizers MUST run
                            // before the run aborts. The original abort reason wins
                            // unless the finalizers themselves failed.
                            runPostBlock(
                                stage = stage,
                                stageIndex = stageIndex,
                                stageFinishedOutcome = "failed",
                                runId = runId,
                                stageShOptions = stageShOptions,
                                ambient = ambient,
                            )?.let { postFailure ->
                                runLifecycle.fold(RunOutcome.Failure(postFailure))
                                return@run runLifecycle.outcome()
                            }
                            runLifecycle.fold(RunOutcome.Failure(continuation.failure))
                            return@run runLifecycle.outcome()
                        }
                    }
                    continue@stagesLoop
                }
                val steps1 = steps
                    ?: throw IllegalArgumentException("Canonical durable coordinator supports only linear or parallel stage bodies")
                // D5: Per-stage workspaceRoot override at dispatch boundary
                // C1: Workspace pre-creation - ensure stage workspace exists before shell dispatch
                // LFC-2 / ERR-S-004: restore stage bookends lost in the LF-0208 spine migration.
                // The canonical coordinator emits StageStarted at entry and StageFinished on normal
                // completion (success/unstable). An aborting stage returns before StageFinished;
                // RunFinished carries the failure.
                runLifecycle.stageStarted(runId, stageIndex, stage.name)
                var stageUnstable = false
                for (stepIndex in steps1.indices) {
                    val step = steps1[stepIndex]
                    val dispatched = dispatch(step, runId, stage.name, stageIndex, stepIndex, stageShOptions, emptyList(), ambient)
                    ambient = dispatched.context
                    when (val continuation = runLifecycle.decideStageContinuation(dispatched.outcome, stage.name, runId.value, ambient)) {
                        CanonicalContinuation.Continue -> Unit
                        CanonicalContinuation.ContinueUnstable -> {
                            runLifecycle.fold(RunOutcome.Unstable)
                            stageUnstable = true
                        }
                        is CanonicalContinuation.Abort -> {
                            // S2-B: a step failure is still a stage outcome. The
                            // failure/always/cleanup finalizers MUST run before the run
                            // aborts, or `post { failure { ... } }` would be dead code
                            // for the exact case it exists for. The original abort
                            // reason wins unless the finalizers themselves failed.
                            runPostBlock(
                                stage = stage,
                                stageIndex = stageIndex,
                                stageFinishedOutcome = "failed",
                                runId = runId,
                                stageShOptions = stageShOptions,
                                ambient = ambient,
                            )?.let { postFailure ->
                                runLifecycle.fold(RunOutcome.Failure(postFailure))
                                return@run runLifecycle.outcome()
                            }
                            runLifecycle.fold(RunOutcome.Failure(continuation.failure))
                            return@run runLifecycle.outcome()
                        }
                    }
                }
                // S2-B: the stage's own steps decided the outcome; the `post`
                // block finalizes the stage BEFORE its StageFinished, so the
                // terminal record already includes the finalizers' work.
                runPostBlock(
                    stage = stage,
                    stageIndex = stageIndex,
                    stageFinishedOutcome = if (stageUnstable) "unstable" else "success",
                    runId = runId,
                    stageShOptions = stageShOptions,
                    ambient = ambient,
                )?.let { failure ->
                    runLifecycle.fold(RunOutcome.Failure(failure))
                    return@run runLifecycle.outcome()
                }
                runLifecycle.stageFinished(runId, stageIndex, stage.name, if (stageUnstable) "unstable" else "success")
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
        }
        return runLifecycle.outcome()
    }

    /**
     * EM-5/EM-6 (catcherror-semantics-em56, D1/D2/D3/D5): folds a step outcome into a
     * continuation, publishing CatchErrorTriggered events at the point of a real failure.
     *
     * A real `StepOutcome.Failure` walks the active context stack from the innermost
     * CatchErrorOverlay outward: every enclosing catchError scope that observes the failure
     * publishes its own CatchErrorTriggered (its buildResult/stageResult/message). FAILURE
     * overlays re-throw outward (ERR-S-002 records then aborts at the outermost; ERR-S-007 lets
     * an enclosing default-UNSTABLE overlay re-catch). The first SUCCESS/UNSTABLE overlay
     * suppresses and stops the walk. An unstable()-only outcome is never a failure, so it never
     * enters the walk (ERR-S-008 emits no trigger).
     */

    /**
     * Dispatches a step, routing BlockStepNode to [dispatchBody].
     */
    /** CTX-P2: outcome + successor context returned together; callers keep their own parent value. */
    private data class Dispatched(val outcome: StepOutcome, val context: ExecutionContext)

    private suspend fun dispatch(
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

        // CDE.2-c0: durable opId/input are needed by every rejection path, so derive them first.
        val opId = OpId(runId.value, stageIndex, stepIndex, bodyPath = bodyPath)
        val operationId = opId.format()
        // WU-RP-035: the body of a HANDLER_CONTINUATION Step is part of the parent's durable
        // identity. Without it, the same parent payload with a different body would reuse a
        // memoized result, the handler would never run, and the changed child would not even
        // reach divergence. Engine-driven blocks are deliberately EXCLUDED so every journal
        // row written by the published 0.45.0 candidate stays valid.
        val bodyStructure: String? = if (bodyContinuation != null && step is BlockStepNode) {
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

        // CDE.2-c0: structural phase (envelope gate + control overlay projection) precedes any typed
        // decode. A structurally-invalid node is a terminal SCHEMA rejection (C3/C5), executor never runs.
        val structural = CanonicalStructuralPreparation.prepare(step)
        val structuralReady = when (structural) {
            is StructuralPreparation.Rejected -> return Dispatched(rejectSchema(operationId, input, structural.reason), executionContext)
            is StructuralPreparation.Ready -> structural
        }
        // CTX-P2: pure context derivation, no ambient mutation (C6 preserved: pushed pre-reconcile).
        val projectedOverlay = StructuralOverlayProjection.project(structuralReady.invocation.stepKey, structuralReady.envelope)
        // Baseline behaviour preserved: Triggered with emitted=false performs NO scope exit.
        val contextAfterOverlay = if (projectedOverlay is StructuralOverlay.CatchErrorTriggered && !projectedOverlay.emitted) {
            executionContext
        } else {
            deriveOverlay(executionContext, projectedOverlay)
        }

        // CDE.2-b2: durable metadata resolved by structural step key BEFORE typed semantics, so
        // fingerprint and reconcile never depend on the decoded command.
        val metadata = metadataResolver.resolve(step.pluginStepId)
            ?: throw EngineInvariantViolation("No durable metadata for canonical step '${step.pluginStepId.value}'")

        val fingerprint = Fingerprint.compute(input, step.pluginStepId.value, metadata.replayPolicy, 1)
        val lifecycleContext = StepLifecycleContext(
            runId = runId.value,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = step.id.value,
            stepType = CanonicalCoreStepMetadata.shortType(step.pluginStepId.value),
        )
        val journaled = journal.get(operationId, 1)
        val currentOperation = RerunOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        when (val resolution = reconcileInvocation(metadata, journaled, currentOperation, operationId)) {
            is InvocationReconciliation.Diverged ->
                return Dispatched(StepOutcome.Failure(
                    PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                        "Canonical run diverged at '${resolution.operationId}'",
                    ),
                ), contextAfterOverlay)
            is InvocationReconciliation.RecoverRunning -> {
                val executionResult = StepExecutionBoundary(eventSink).execute(lifecycleContext) {
                    CommonExecutionResult(outcome = resolution.outcome, encodedOutput = null)
                }
                val outcome = executionResult.outcome
                journal.append(
                    RerunOperation(
                        id = operationId,
                        fingerprint = fingerprint,
                        input = input,
                        output = null,
                        status = resolution.status,
                        attempt = 1,
                    ),
                )
                if (outcome is StepOutcome.Success) cursorStore.advance(runId.value, operationId, stageIndex)
                return Dispatched(outcome, contextAfterOverlay)
            }
            InvocationReconciliation.ReuseCompleted -> return Dispatched(StepOutcome.Success, contextAfterOverlay)
            is InvocationReconciliation.RejectedAbort ->
                return Dispatched(StepExecutionBoundary(eventSink).execute(lifecycleContext) {
                    CommonExecutionResult(
                        outcome = StepOutcome.Failure(
                            PipelineFailure(
                                dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                                "Replay aborted for '${resolution.operationId}'",
                            ),
                        ),
                        encodedOutput = null,
                    )
                }.outcome, contextAfterOverlay)
            // Execute is the ONLY resolution that reaches the effective executor. beginOperation, the
            // StepExecutionBoundary-wrapped executor call, the terminal journal write and cursor advance
            // live here, so the concrete semantics are invoked exclusively under this decision.
            InvocationReconciliation.Execute -> {
                // CDE.3-e4.3: runtime context is hoisted here so registry prepare can derive the
                // available capabilities from the capability bridge (never the raw context to a handler).
                val runtime = CanonicalRuntimeContext(
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
        }
    }

    /**
     * Applies the pre-reconcile control overlay projected from the structural envelope (CDE.2-c0),
     * establishing or closing a CatchError context frame. This runs BEFORE durable resolution, so a
     * reused CatchErrorEntered still establishes its scope without re-executing (C6: executor stays 0).
     * Only the structural overlay descriptor is consumed; no typed command is built here.
     */
    /**
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
     * S2-B: interprets a stage's declared `post` block for a KNOWN stage
     * outcome. The single stage-level finalizer seam of the canonical
     * coordinator.
     *
     * Decision/interpretation split (Step Constitution rule 7):
     *  - the PURE decision is `PostCondition.outcomeOf` + `PostPlanner.plan`:
     *    which blocks fire, in which order, for which outcome. No I/O;
     *  - the INTERPRETATION is this method: dispatching the selected nodes
     *    through the SAME canonical `dispatch` spine as stage-body steps, each
     *    with a deterministic `post:<CONDITION>` [BlockSegment] so journal
     *    identity, replay and divergence behave exactly like any other step.
     *
     * Ordering law: StageStarted < ... < [PostConditionSelected] < post steps
     * < StageFinished. A declared-but-not-selected block appears only in the
     * event's `skippedConditions`, never as a silent no-op.
     *
     * Failure containment: a FAILING finalizer fails the RUN (typed USER
     * failure naming the stage and condition) but never rolls back the
     * remaining finalizers — cleanup is exactly the code that must be allowed
     * to run after bad news. A post failure therefore ABORTS the run with the
     * failing finalizer's message; StageFinished is not emitted for a run the
     * coordinator is failing.
     *
     * @return the typed failure to abort the run with, or null when every
     *         selected finalizer succeeded (including the empty-plan no-op).
     */
    private suspend fun runPostBlock(
        stage: StageNode,
        stageIndex: Int,
        stageFinishedOutcome: String,
        runId: RunId,
        stageShOptions: ShOptions,
        ambient: ExecutionContext,
    ): PipelineFailure? {
        val postSpec = stage.post ?: return null
        if (postSpec.isEmpty) return null
        val stageOutcome = PostCondition.outcomeOf(stageFinishedOutcome)
            ?: return PipelineFailure(
                dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                "post block for stage '${stage.name}' received unknown stage outcome '$stageFinishedOutcome'; " +
                    "refusing to select finalizers on an unreadable outcome",
            )
        val plan = postSpec.toPostPlan()
        // The pure decision, made ONCE: if the planner selects nothing for this
        // outcome, the block is inert for this stage and emits nothing.
        if (PostPlanner.plan(plan, stageOutcome).isEmpty()) return null

        eventSink.append(
            dev.rubentxu.pipeline.v2.events.PostConditionSelected(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                stageIndex = stageIndex,
                stageName = stage.name,
                stageOutcome = stageFinishedOutcome,
                selectedConditions = PostPlanner.selectedConditions(plan, stageOutcome).map { it.name },
                skippedConditions = PostPlanner.skippedConditions(plan, stageOutcome).map { it.name },
            ),
        )

        // One decision source: the planner's own condition projection drives
        // the walk, so the event lists and the executed nodes can never
        // disagree about which blocks fired.
        var dispatched = 0
        for (condition in PostPlanner.selectedConditions(plan, stageOutcome)) {
            val nodes = plan.bodies[condition].orEmpty()
            for ((index, node) in nodes.withIndex()) {
                val bodyPath = listOf(BlockSegment("post:${condition.name}:$index"))
                val dispatchedStep = dispatch(
                    step = node,
                    runId = runId,
                    stageName = stage.name,
                    stageIndex = stageIndex,
                    stepIndex = POST_BASE_STEP_INDEX + dispatched,
                    stageShOptions = stageShOptions,
                    bodyPath = bodyPath,
                    executionContext = ambient,
                )
                dispatched++
                if (dispatchedStep.outcome !is StepOutcome.Success) {
                    val reason = "post ${condition.name} finalizer of stage '${stage.name}' failed"
                    return PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.USER, reason)
                }
            }
        }
        return null
    }

    private suspend fun runParallelStage(
        stage: StageNode,
        stageIndex: Int,
        stageShOptions: ShOptions,
        runId: RunId,
        executionContext: ExecutionContext,
    ): StepOutcome {
        val branches = (stage.body as? StageBody.Parallel)?.branches
            ?: throw EngineInvariantViolation("runParallelStage called for non-parallel stage '${stage.name}'")

        // INC-007 (+1 helper, canonical coordinator dispatchBody sibling).
        //
        // Dispatches parallel branch bodies with fresh per-child journal rows so
        // every branch child gets independent durable rows and a durable rerun
        // reuses completed branch work instead of duplicating it.
        // E-EM-11 Z2: the canonical stage law is StageStarted < stage execution <
        // StageFinished for EVERY admitted stage, including parallel bodies. The
        // parallel path previously forked before the linear-path StageStarted emitter,
        // an accidental implementation difference, not a different Stage semantic.
        // Exactly one StageStarted, same stage identity as the StageFinished emitted
        // by the caller's continuation handling.
        runLifecycle.stageStarted(runId, stageIndex, stage.name)

        // PAR-D D2: plan the parallel aggregate from durable facts BEFORE any branch
        // launches. The reconciler is pure; this coordinator is the single writer.
        val aggregateId = ParallelAggregateId(runId = runId.value, stageIndex = stageIndex)
        val aggregateInput = OperationInput(
            stepId = BodyAggregateIdentity.ParallelStageAggregate.key.value,
            params = mapOf("control" to kotlinx.serialization.json.JsonPrimitive("aggregate")),
            runId = runId.value,
            attempt = 1,
        )
        val aggregateFingerprint = Fingerprint.compute(
            aggregateInput,
            BodyAggregateIdentity.ParallelStageAggregate.fingerprintKey(stageIndex, branches.map { it.name }),
            ReplayPolicy.MEMOIZED,
            1,
        )
        val aggregateRow = journal.get(parallelControlOpId(runId.value, stageIndex), 1)?.let {
            ParallelAggregateSnapshot(
                id = aggregateId,
                fingerprint = it.fingerprint,
                status = it.status,
                semanticOutcome = decodeBranchTerminal(it.output?.result),
            )
        }
        val childSnapshots = journal.listForRun(runId.value)
            .mapNotNull { op ->
                // Branch durable identity lives in the FIRST bodyPath segment
                // ("b{N}:branch"), never in an OpId -b{N} suffix (the canonical
                // parallel child key has no -b segment). Parse it deterministically.
                val branchIndex = Regex("-bp\\d+-b(\\d+):branch(-|$)").find(op.id)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                ParallelBranchChildSnapshot(
                    branchIndex = branchIndex,
                    childIndex = op.attempt,
                    status = op.status,
                )
            }
            .groupBy { it.branchIndex }
        val decision = ParallelReconciler.reconcile(
            ParallelReconciliationInput(
                aggregateId = aggregateId,
                currentFingerprint = aggregateFingerprint,
                aggregateRow = aggregateRow,
                branchCount = branches.size,
                childrenByBranch = childSnapshots,
            ),
        )

        val aggregateOpId = parallelControlOpId(runId.value, stageIndex)
        when (decision) {
            is ParallelDecision.RejectDivergence -> {
                journal.append(aggregateTerminalRow(aggregateOpId, aggregateId, aggregateFingerprint, aggregateInput, BranchTerminal.Failed(decision.reason), OperationStatus.DIVERGENT))
                return StepOutcome.Failure(
                    PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, decision.reason),
                )
            }
            is ParallelDecision.RejectAmbiguousOutcome -> {
                journal.append(aggregateTerminalRow(aggregateOpId, aggregateId, aggregateFingerprint, aggregateInput, BranchTerminal.Failed(decision.reason), OperationStatus.ABORTED))
                return StepOutcome.Failure(
                    PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, decision.reason),
                )
            }
            is ParallelDecision.ReuseSuccess -> return StepOutcome.Success
            is ParallelDecision.ReuseUnstable -> return StepOutcome.Unstable
            is ParallelDecision.ReuseFailure -> {
                // The exact semantic outcome is durable; surface it as a canonical failure.
                val message = (decision as? ParallelDecision.ReuseFailure).let { "parallel aggregate previously failed" }
                return StepOutcome.Failure(
                    PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.USER, message),
                )
            }
            is ParallelDecision.CloseFromChildren -> {
                val status = when (decision.outcome) {
                    is BranchTerminal.Succeeded -> OperationStatus.SUCCEEDED
                    is BranchTerminal.Unstable -> OperationStatus.ABORTED
                    is BranchTerminal.Failed -> OperationStatus.FAILED
                }
                journal.append(aggregateTerminalRow(aggregateOpId, aggregateId, aggregateFingerprint, aggregateInput, decision.outcome, status))
                return when (decision.outcome) {
                    is BranchTerminal.Succeeded -> StepOutcome.Success
                    is BranchTerminal.Unstable -> StepOutcome.Unstable
                    is BranchTerminal.Failed -> StepOutcome.Failure(
                        PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.USER, (decision.outcome as BranchTerminal.Failed).message ?: "parallel branch failed"),
                    )
                }
            }
            is ParallelDecision.Start -> {
                // Single writer: persist the aggregate RUNNING BEFORE launching branches.
                journal.append(compositeRunningRow(aggregateOpId, aggregateId, aggregateFingerprint, aggregateInput))
            }
            is ParallelDecision.ResumeBranches -> {
                // Aggregate row already RUNNING (or absent for a pre-PAR-D journal); keep it RUNNING.
                if (aggregateRow == null) {
                    journal.append(compositeRunningRow(aggregateOpId, aggregateId, aggregateFingerprint, aggregateInput))
                }
            }
        }

        val branchesToRun: List<Int> = when (decision) {
            is ParallelDecision.Start -> decision.branches
            is ParallelDecision.ResumeBranches -> decision.branches
            else -> emptyList()
        }

        // PAR-D structured concurrency: the join runs inside a caller-bound
        // supervisorScope; branch outcomes are typed VALUES (contained), never
        // exceptions used as control flow. The scope exits only when every branch
        // resolves; no coroutine survives the parallel stage lifecycle. Branch
        // CancellationException stays an execution mechanism — it is converted by
        // the same typed boundary below, never into a durable terminal truth.
        val deferred: List<kotlinx.coroutines.Deferred<StepOutcome>> = kotlinx.coroutines.supervisorScope {
            branchesToRun.map { branchIndex ->
                val branch = branches[branchIndex]
                async(kotlinx.coroutines.Dispatchers.Default) {
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.ParallelBranchStarted(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            branchIndex = branchIndex,
                            branchName = branch.name,
                            parentStageIndex = stageIndex,
                        ),
                    )
                    val branchOutcome = executeBranchSteps(branch, runId, stageIndex, branchIndex, stageShOptions, executionContext)
                    val outcomeText = when (branchOutcome) {
                        is StepOutcome.Failure -> "failure"
                        is StepOutcome.Unstable -> "unstable"
                        else -> "success"
                    }
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.ParallelBranchFinished(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            branchIndex = branchIndex,
                            branchName = branch.name,
                            parentStageIndex = stageIndex,
                            outcome = outcomeText,
                        ),
                    )
                    branchOutcome
                }
            }
        }
        val branchOutcomes: List<StepOutcome> = deferred.map { it.await() }

        // PAR-D D2: fold the typed branch outcomes (exact semantic knowledge at fresh
        // execution time) and close the aggregate row with the lossless carrier.
        val outcomeByBranch = branchesToRun.mapIndexed { i, branchIdx ->
            val o = branchOutcomes[i]
            branchIdx to when (o) {
                is StepOutcome.Failure -> BranchTerminal.Failed(o.failure.message)
                is StepOutcome.Unstable -> BranchTerminal.Unstable
                else -> BranchTerminal.Succeeded
            }
        }.toMap()
        val fold = dev.rubentxu.pipeline.v2.domain.durable.foldAwaitAll(outcomeByBranch)
        val aggregateStatus = when (fold) {
            is BranchTerminal.Succeeded -> OperationStatus.SUCCEEDED
            is BranchTerminal.Unstable -> OperationStatus.ABORTED
            is BranchTerminal.Failed -> OperationStatus.FAILED
        }
        journal.append(aggregateTerminalRow(aggregateOpId, aggregateId, aggregateFingerprint, aggregateInput, fold, aggregateStatus))

        // ALL_COMPLETE: first failure (deterministic: lowest branch index) is the aggregate.
        return branchOutcomes.firstOrNull { it is StepOutcome.Failure }
            ?: branchOutcomes.firstOrNull { it is StepOutcome.Unstable }
            ?: StepOutcome.Success
    }

    /** Deterministic control OpId for the parallel aggregate (PAR-D typed identity). */
    private fun parallelControlOpId(runIdValue: String, stageIndex: Int): String =
        OpId(runIdValue, stageIndex, -1, bodyPath = listOf(BlockSegment("0:parallel-control"))).format()

    private fun compositeRunningRow(
        opId: String,
        id: ParallelAggregateId,
        fingerprint: Fingerprint,
        input: OperationInput,
    ): CompositeOperation = CompositeOperation(
        id = opId,
        fingerprint = fingerprint,
        input = input,
        output = null,
        status = OperationStatus.RUNNING,
        attempt = 1,
        subOperations = emptyList(),
    )

    /** Terminal aggregate row carrying the exact typed outcome (lossless carrier). */
    private fun aggregateTerminalRow(
        opId: String,
        id: ParallelAggregateId,
        fingerprint: Fingerprint,
        input: OperationInput,
        outcome: BranchTerminal,
        status: OperationStatus,
    ): CompositeOperation {
        val result = kotlinx.serialization.json.buildJsonObject {
            put("outcome", kotlinx.serialization.json.JsonPrimitive(outcome.asText))
            if (outcome is BranchTerminal.Failed && outcome.message != null) {
                put("message", kotlinx.serialization.json.JsonPrimitive(outcome.message))
            }
        }
        return CompositeOperation(
            id = opId,
            fingerprint = fingerprint,
            input = input,
            output = OperationOutput(result, durationMs = 0L, finishedAt = clock.now().toEpochMilli()),
            status = status,
            attempt = 1,
            subOperations = emptyList(),
        )
    }

    private fun decodeBranchTerminal(result: kotlinx.serialization.json.JsonElement?): BranchTerminal? {
        val obj = result as? kotlinx.serialization.json.JsonObject ?: return null
        val text = (obj["outcome"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        return when (text) {
            "success" -> BranchTerminal.Succeeded
            "unstable" -> BranchTerminal.Unstable
            "failure" -> BranchTerminal.Failed((obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
            else -> null
        }
    }

    /**
     * Dispatches one parallel branch's linear steps through the shared spine with
     * branch-indexed journal identity. A branch failure is contained: it becomes
     * the branch outcome, never a throw (the join waits for all branches).
     */
    private suspend fun executeBranchSteps(
        branch: StageNode,
        runId: RunId,
        stageIndex: Int,
        branchIndex: Int,
        stageShOptions: ShOptions,
        executionContext: ExecutionContext,
    ): StepOutcome {
        // CTX-P2 branch derivation: immutable value; today branchContext == parentContext.
        val branchContext = executionContext
        // SB-S-008 / WU-LPR-071: parallel branches get ISOLATED cwds. The workspace is
        // derived purely from (controlDirRoot, stageIndex, branchIndex) and carried in
        // the branch's immutable ShOptions copy — no coordinator state is mutated and
        // no branch can observe a sibling's working directory. With --workspace
        // (workspaceBase set) the shared project workspace wins, per WU-LPR-062.
        val branchShOptions = if (controlDirRoot != null && workspaceBase == null) {
            val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
            val branchWorkspace = resolver.ensureCreated(
                resolver.resolve("stage-$stageIndex-b$branchIndex", 0)
            )
            stageShOptions.copy(workspaceRoot = branchWorkspace)
        } else {
            stageShOptions
        }
        // P2 fitness: branches must observe an explicitly derived context, never coordinator state.
        val steps = (branch.body as? StageBody.Steps)?.steps
            ?: throw EngineInvariantViolation("Parallel branch '${branch.name}' has a non-linear body")
        var outcome: StepOutcome = StepOutcome.Success
        for (stepIndex in steps.indices) {
            val step = steps[stepIndex]
            // Branch durable identity: dispatch() derives the journal key from the
            // bodyPath it is handed, so the branch index is encoded as the FIRST
            // deterministic BlockSegment ("b{branchIndex}:branch"). Two branches'
            // children can never collide; a durable rerun reuses the same keys.
            val bodyPath = listOf(
                BlockSegment("b$branchIndex:branch"),
                BlockSegment(stepIndex, step.pluginStepId),
            )
            val stepOutcome = dispatch(
                step,
                runId,
                branch.name,
                stageIndex,
                stepIndex,
                branchShOptions,
                bodyPath,
                branchContext,
            ).outcome
            when (stepOutcome) {
                is StepOutcome.Failure, is StepOutcome.Unstable -> return stepOutcome
                else -> { /* continue */ }
            }
        }
        return outcome
    }

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
        val projected = bodyExecutionEngine.projectScope(scope, block, runId, stageIndex, stepIndex, stageShOptions, contextInBody)

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
        // credentialLeasedBody callback (the coordinator's own internal method).
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
     * WU-G5R.3 / LFC-5.3: legacy waitUntil polling loop used when no
     * [WaitUntilControlJournal] is bound. WU-LPR-302 Phase 3 keeps this loop
     * bit-equivalent for the no-journal case; the durable loop is now owned by
     * [dev.rubentxu.pipeline.v2.application.durable.waituntil.WaitUntilEngine].
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

    private suspend fun executeWaitUntilBodyInline(
        scope: BlockShellScope.WaitUntilScope,
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        childShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
    ): StepOutcome = bodyExecutionEngine.executeWaitUntilInline(
        scope = scope,
        block = block,
        runId = runId,
        stageName = stageName,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        childShOptions = childShOptions,
        parentBodyPath = parentBodyPath,
        executionContext = executionContext,
        dispatcher = ::dispatchChild,
    )




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
    internal suspend fun executeCredentialLeasedBody(
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
                    runId = runId, stageIndex = stageIndex, stepIndex = stepIndex,
                    stepName = "${block.pluginStepId.value}", stepType = "withcredentials",
                    failureKind = dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                    message = acquisition.failure.describe(),
                )
                return StepOutcome.Failure(
                    PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, acquisition.failure.describe()),
                )
            }
            is CredentialScopeOutcome.Invalid -> {
                emitCredentialLeaseAdmissionFailure(
                    runId = runId, stageIndex = stageIndex, stepIndex = stepIndex,
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

    private companion object {
        const val REATTACH_TIMEOUT_MS = 60_000L

        /**
         * Post finalizers dispatch at step indices AFTER every declared body
         * step (the DSL cap is 512), with the `post:<CONDITION>:<i>` body path
         * segment carrying the block identity, so a post op can never collide
         * with a body-step OpId of the same stage.
         */
        const val POST_BASE_STEP_INDEX = 1000
    }

    /**
     * +1 sibling helper for INC-007 (canonical coordinator catchError overlay handler).
     *
     * catchError semantics: executes the body. If body fails and buildResult != "FAILURE",
     * the failure is suppressed and the step succeeds (with UNSTABLE). If buildResult ==
     * "FAILURE", the failure is propagated. If body succeeds, step succeeds.
     *
     * This is the EM-4 canonical body-execution IR replacement for the legacy
     * rewriteWorkflowControl linearization (core.emit.event + shell + core.emit.event).
     */
}

/**
 * WU-RP-033: read-only [StepRegistry] view over a nullable registry. Used by the
 * composed body-policy authority so callers that never wired a registry keep the
 * historical behaviour (open-registry lookup returns UnknownStep and the canonical
 * core table answers). Read-only: register/register Throws are impossible here, an
 * invariant this adapter makes unrepresentable.
 */
/**
 * S2-D: the typed classification of ONE admitted BEFORE_STAGE directive whose
 * declared policy entered the decode seam (Gate | Evaluate), after its OWN
 * codec ran.
 *
 * A directive definition owns its own codec, so the engine cannot know
 * statically what a definition decodes to. This sealed ADT is the single point
 * where that erasure is CHECKED, so a self-contradictory definition (or an
 * undecodable one) becomes a typed value the coordinator can fail closed on,
 * instead of a ClassCastException — or a silently-running stage — escaping from
 * the run loop.
 *
 * Illegal states are unrepresentable: [Evaluated] deliberately carries NO
 * payload, because a decoded Evaluate has no consumer in this phase and
 * carrying `input: Any` would reintroduce the untyped boundary the kernel
 * eradicated at the registry ([dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinitionAny]).
 * [Denied] centralises the three decode-seam failure causes (not resolvable,
 * Malformed, gate-decoded-to-non-predicate). [GatePredicate] keeps the S2-C
 * meaning: the erased shape of an already-admitted gate, BEFORE composition.
 */
private sealed interface DecodedBeforeStage {
    val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey

    /** Gate decoded to its predicate; S2-C composition consumes exactly this. */
    data class GatePredicate(
        override val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey,
        val predicate: dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate,
    ) : DecodedBeforeStage

    /** Evaluate decoded: observes and continues; nothing is consumed. */
    data class Evaluated(
        override val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey,
    ) : DecodedBeforeStage

    /**
     * Fail-closed: the directive's decode contract failed (or the policy cannot
     * be interpreted in this seam). The stage never starts.
     */
    data class Denied(
        override val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey,
        val reason: String,
    ) : DecodedBeforeStage
}

/**
 * S2-D (D3): the reason wording for a Malformed decode in the seam.
 *
 * The denial is fail-closed of the ENGINE, not a veto of the directive: an
 * `Evaluate` observes and cannot veto, so a Malformed is that directive's OWN
 * contract failing (it could not read the arguments its author wrote). The
 * reason names the declared policy so the diagnostic says WHOSE contract
 * failed; the failure kind stays USER either way (the author wrote the args).
 * Total over the closed policy ADT.
 */
private fun seamDecodeFailureReason(
    policy: dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy,
    decodeReason: String,
): String = when (policy) {
    is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Gate ->
        "declared a gate whose arguments could not be decoded: " + decodeReason

    is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Evaluate ->
        "declared policy evaluate whose arguments could not be decoded: " + decodeReason

    is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.ProvideContext ->
        "declared policy provide-context whose arguments could not be decoded: " + decodeReason
}

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
