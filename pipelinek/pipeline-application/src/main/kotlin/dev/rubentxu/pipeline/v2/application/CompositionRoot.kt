package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.CompositeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.FileBasedWaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.durable.RunOutputRetention
import dev.rubentxu.pipeline.v2.application.durable.credentials.WithCredentialsExecutorScopeAdapter
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.output.RetainUntil
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.events.durable.EventRecordReadPortStoreAdapter
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import dev.rubentxu.pipeline.v2.events.durable.SqliteReplayCursorStoreImpl
import dev.rubentxu.pipeline.v2.runtime.control.RuntimeControlPort
import dev.rubentxu.pipeline.v2.runtime.control.RuntimeControlPortStoreAdapter
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPortStoreAdapter
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Canonical durable composition root extracted from [Main].
 * Keeps the exact 22-argument composition contract and the
 * same coordinator wiring while removing the durable runtime assembly from the
 * CLI entrypoint. The two existing Main call sites remain unchanged because
 * this package-level function keeps the same name and parameter order.
 * This function is `internal` rather than `private` only because Kotlin file
 * privacy would prevent [Main] from calling it after the file partition. No
 * public API or CLI contract is introduced.
*/
internal fun runCanonicalPipeline(
    pipeline: CompiledPipeline,
    runId: RunId,
    journal: OperationJournal,
    cursorStore: ReplayCursorStore,
    clock: Clock,
    effectReplayPolicy: EffectReplayPolicy,
    eventSink: EventSink,
    controlDirRoot: Path,
    sandboxProfile: SandboxProfile,
    workspaceBase: Path? = null,
    //: explicit owner of `workspaceBase`. `null` means the caller
    // could not state ownership, and the runtime then treats the root as
    // PipelineK-managed. The CLI always states it (WorkspaceIntent).
    workspaceOwnership: dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership? = null,
    withCredentialsExecutor: WithCredentialsExecutor? = null,
    /**
     * The store the operator configured, if any.
     * Turned into [BasicCredentialsCapabilityContributor] here, which is the ONE
     * place allowed to know both a credentials port and a plugin-facing capability
     * shape. `Main` passes the provider it already builds and never imports the
     * http plugin; the plugin declares `credentials.basic` and never imports this.
     * A run with no store still contributes the capability, backed by a source that
     * refuses every lookup with a reason that says so. Withholding it would refuse
     * admission for every Step that declares the seam, including those that declare
     * no credential and would never call it.
*/
    credentialProvider: dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider? = null,
    /**
     * Permit outbound egress for this run.
     * FALSE by default. The transport a plugin needs is contributed through
     * a capability contributor and the PERMISSION is produced here, so a plugin can
     * be present in the distribution and still be unable to reach the network
     * until an operator says so.
*/
    allowNetwork: Boolean = false,
    /**
     * The capabilities OFFICIAL_PLUGINs contribute.
     * A LIST OF CONTRIBUTORS, composed here and consulted at both admission and
     * execution, rather than a finished map. Composition is a value a reviewer
     * can read, collisions fail closed, and adding the next plugin is one more
     * element in this list — with no change here, in the coordinator, or in the
     * capability access.
     * The default is DISCOVERY, not `emptyList()`. It used to default to an
     * empty list that the CLI never filled, which meant `http.request` was refused
     * at admission in the installed distribution for a missing `http.transport` —
     * a Step that passed every contract test and could not run. The default is
     * evaluated per call, so a caller that injects its own contributors (a test
     * asserting a specific capability set) is unaffected and no discovery happens
     * on its behalf.
*/
    // `null` now means "whatever the composition resolved, plus the credential seam". It
    // used to mean "discover again, under whatever the TCCL happens to be by now" — a fourth
    // authority resolved outside the composition window, which is how a plugin whose classes
    // live below the TCCL contributed its Step and kept its capability. The default is still a
    // default, but this one closes over the value the run already resolved.
    capabilityContributors: List<dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor>? =
        null,
    secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    // The pre-resolved composition. REQUIRED, with no default, and that is the point.
    // It used to default to `CoreStepRegistryFactory.registry()` while the event and directive
    // registries were composed here in the body. A caller who omitted the argument therefore got
    // A silent step registry — external contributions dropped with no error — which is
    // a fail-open path through the admission guarantee that exists to provide. Removing
    // the default makes "you did not compose" a compile error instead of a running pipeline that
    // quietly ignores every plugin in the installation.
    // `pluginClassLoader` is gone from this signature for the same reason. Its KDoc claimed it
    // governed directive discovery, and after the composition moved out, nothing here read it.
    // A parameter that documents a role it no longer has is a smaller lie than a default that
    // drops plugins, and it is removed rather than left with a stale comment.
    composition: PreResolvedComposition,
    /**
     * The per-run Saga seam.
     *
     * Null when the caller did not compose a durable Saga store, and then the compensation
     * intent port is not exposed at all: a Step that declares it is refused at admission
     * rather than reaching a handler that could not keep the promise it is about to make.
     */
    sagaBinding: dev.rubentxu.pipeline.v2.application.durable.SagaCompensationBinding? = null,
): RunOutcome = runBlocking {
    CanonicalDurableRunCoordinator(
        // The ONE registry this run resolves plugin event kinds against, on the write
        // side and on the read-back side. Resolved in PluginComposition, before this
        // function was called, and already cross-checked against what the plugins emit.
        eventRegistry = composition.events,
        // ONE composite, consulted by both admission and execution.
        // The plugin side now comes from the composition, resolved under the same classloader
        // swap as the Steps these capabilities back. The credential side stays here because it is
        // State — it wraps the store THIS invocation opened, so it cannot come from
        // classpath discovery. The two never collide: a plugin deliberately contributes only its
        // own transport.
        capabilityContributor = CompositeCapabilityContributor(
            (capabilityContributors ?: composition.capabilityContributors) +
                dev.rubentxu.pipeline.v2.credentials.executor.BasicCredentialsCapabilityContributor(
                    credentialProvider,
                ),
        ),
        dispatcher = CanonicalNodeDispatcher(),
        journal = journal,
        cursorStore = cursorStore,
        clock = clock,
        effectReplayPolicy = effectReplayPolicy,
        eventSink = eventSink,
        credentialScopePort = WithCredentialsExecutorScopeAdapter(withCredentialsExecutor, eventSink),
        controlDirRoot = controlDirRoot,
        workspaceBase = workspaceBase,
        shOptions = ShOptions(
            // With --workspace <dir>, the project's own directory IS the
            // workspace — stages share it (Jenkins-familiar semantics). Adding
            //.resolve("workspace") would point to a subdirectory of the project root
            // (typically nonexistent), and every `sh` step would fail with
            // "No such file or directory" because gradlew/mvn/node live in
            // the project root itself. Without --workspace we keep the legacy
            // per-stage layout.
            workspaceRoot = workspaceBase ?: controlDirRoot.resolve("workspace"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
            sandbox = SandboxConfigResolver.resolve(sandboxProfile),
            //: carry the ownership the CLI decided into the
            // Runtime transport, so the destructive guard reads the real
            // owner of this root instead of re-deriving `Managed`.
            workspaceOwnership = workspaceOwnership,
            // Egress is DENIED unless the operator asked for it
            // with --allow-network. The bridge turns this gate into the generic
            // NETWORK_EGRESS_CAPABILITY, and a Step that needs the network declares
            // it — so the default run cannot reach one.
            // The two ends are gates, not verdicts. `--allow-network` is
            // [AllowAll] and the default is [DenyAll], which are the SAME interface
            // as a future per-destination allowlist — so adding one later is a new
            // value here and not a new code path through the runtime, the Step, or
            // the admission check.
            networkEgress = if (allowNetwork) AllowAll else DenyAll,
        ),
        // Core Steps first, then external plugin contributions,
        // frozen by PluginComposition before this call.
        stepRegistry = composition.steps,
        secretPatternRegistry = secretPatternRegistry,
        // Forwarded so the dispatch can build the invocation-bound intent port.
        sagaBinding = sagaBinding,
        // Production wire-up. The retry aggregate is reconciled against
        // the on-disk control journal so a `run` invocation with the same --db and
        // --control-root reuses the prior aggregate terminal state and does not re-launch
        // child bodies that already succeeded/failed terminally.
        retryControlJournal = FileBasedRetryControlJournal(controlDirRoot),
        // Durable waitUntil control journal. Matches the
        // retryControlJournal pattern — persisted before child effects, read on plan,
        // authoritative over the aggregate state on replay.
        waitUntilControlJournal = FileBasedWaitUntilControlJournal(controlDirRoot),
        // Per-run artifact index for the core.archiveArtifacts
        // -> core.artifact.query bridge. Constructed fresh per run; the same
        // instance is shared between the producer (archive with name=...) and
        // the consumer (artifactQuery) within the run.
        artifactIndex = dev.rubentxu.pipeline.v2.application.durable.ArtifactIndexAdapter.build(),
        // The run's terminal state is the ONLY thing that can authorise discarding
        // its output, and the runtime is the authority that knows it. Declared here — ONCE, where
        // every other composition decision is declared — so the policy is a value a reviewer can
        // read rather than a branch somebody can find at the call site.
        // `ExplicitReleaseOnly`, and the reason is the product's own console: `pipeline console
        // --control-dir` reads the transcript of a run that ALREADY FINISHED, so a
        // `RunTerminalPlus` here would delete the very output the product exists to serve. That is
        // a product decision, and it has a stated consequence: nothing releases automatically, and
        // the operator-release surface that would exercise `OperatorReleased` is not built yet.
        // The store arrives as a SUPPLIER, so this policy that retains never opens (and never
        // recovers) the Output Plane for the release it will not perform.
        outputRetention = RunOutputRetention(
            retention = { OutputPlaneProvider.storeFor(controlDirRoot) },
            policy = RetainUntil.ExplicitReleaseOnly,
        ),
        // Resolved in PluginComposition, under the same classloader swap that produced
        // the step registry and the event registry, and frozen before this call.
        // Those distinctions are preserved there rather than lost: `core.when` and `core.agent` are
        // CORE definitions that enter through the SAME open registry as any vendor directive,
        // which is what keeps "open by key" honest. If either had needed its own registration
        // path, the seam would not be open.
        directiveRegistry = composition.directives,
        // Gates read the environment the STAGE actually declares, plus
        // the process environment for names the stage names explicitly.
        // The stage's own `environment { env(...) }` block is the source of
        // truth, because that is what the DSL can express. `pipeline.environment`
        // is a separate pipeline-level spec the DSL does not populate, so
        // reading only that would make every real gate see an empty world and
        // skip unconditionally.
        // Only variables the stage DECLARES are read from the host: a gate must
        // not be able to observe an arbitrary ambient process variable, or the
        // same script would behave differently on different machines for
        // reasons the author never wrote down.
        // The precedence itself is a decision and lives in the domain as
        // [dev.rubentxu.pipeline.v2.domain.directive.GateEnvironmentPrecedence];
        // this adapter only captures the ambient effect and hands the two maps
        // to it. Reading `System.getenv()` inline and merging here was a real
        // defect: `declared + host` let a host variable overwrite the value the
        // author wrote in the stage, contradicting both the comment above and
        // the established `EnvironmentComposer` base-then-stage ordering.
        gateContext = { stageEnvironment ->
            dev.rubentxu.pipeline.v2.domain.directive.GateEnvironmentPrecedence.DEFAULT.resolve(
                declared = stageEnvironment.values,
                host = System.getenv(),
            )
        },
    ).run(pipeline, runId)
}

/**
 * The control plane's composition: the published [RuntimeControlPort] over the
 * stores a run's facts live in.
 *
 * The decisions this function makes, and where they are declared:
 *
 *  - the **lease** lives under the control root, in the same place the run's
 *    own writer looks for it, so the cancel and the writer contend for the
 *    same lock;
 *  - the **journal** is the same SQLite database the run wrote, reached
 *    through the same connection factory, so the cancel and the run share one
 *    durable state and not two;
 *  - the **event lane** is the store the run wrote, wrapped in the redacting
 *    sink the run used, so the cancel's terminal bookend travels the same
 *    path as the writer's;
 *  - the **output plane** is the writing side of the control root, because a
 *    cancel seals streams and a reader may not reconcile them.
 *
 * The redaction registry is empty on purpose: the control verb does not open
 * the credentials store, so it has no credential pattern to register, and the
 * only free text it writes is the operator's own reason. The verb's usage
 * says so, because a reason is recorded verbatim in the run's history.
 */
internal fun composeRuntimeControlPort(
    eventStore: SqliteEventStore,
    controlRoot: Path,
    clock: Clock,
): RuntimeControlPort {
    val journal = SqliteOperationJournalImpl(
        connectionFactory = eventStore.underlyingConnectionFactory(),
        clock = clock,
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        dbPath = eventStore.databasePath(),
    )
    val lease = FileBackedRunExecutionLeaseStore(controlRoot.resolve("leases"))
    val output = OutputPlaneProvider.storeForWriting(controlRoot)
    val events = RedactingEventSink(eventStore, SecretPatternRegistry())
    val eventReader = EventRecordReadPortStoreAdapter(
        store = eventStore,
        runExists = { runId -> lease.isKnown(runId) || eventStore.hasRun(runId) },
        tailSequence = { runId -> eventStore.tailSequence(runId) },
    )
    val owner = RunOwnerId.of(
        "cli-cancel-" + ProcessHandle.current().pid() + "-" +
            java.util.UUID.randomUUID().toString().take(8),
    )
    return RuntimeControlPortStoreAdapter(
        journal = journal,
        lease = lease,
        events = events,
        eventReader = eventReader,
        frames = output.frameIndex(),
        outputRecovery = output,
        sealStream = { stream -> output.seal(stream) },
        clock = clock,
        ownerId = requireNotNull(owner) { "the cancel owner id is always well formed" },
    )
}

/**
 * The introspection plane: the published [RuntimeIntrospectionPort] composed
 * over the same stores every other read in this CLI reads.
 *
 * ## Why this exists
 * The adapter had a consumer nowhere. Its rule — "the run's terminal fact is
 * the event lane's `RunFinished` bookend" — was therefore untested and, until
 * this composition, unreachable: an operator could not ask the product
 * whether a run was still going, and an external worker (Fabric) could not
 * ask it either, because the port had no in-tree implementation to stand on.
 *
 * ## Why the same stores, and not new ones
 * The plane is a READ of the run that already exists: the event lane for its
 * facts, the lease for who holds it, the journal and the cursor for the
 * durable operation state, the Output Plane for the tails. Reusing them is
 * what keeps the plane an observation rather than a second copy of the run.
 *
 * ## Why the READ side of the Output Plane
 * The store is opened WITHOUT recovery. A reader must never reconcile durable
 * state, because reconciliation truncates the uncommitted tail of a stream and
 * an uncommitted tail is exactly what a live writer looks like right now.
 */
internal fun composeRuntimeIntrospectionPort(
    eventStore: SqliteEventStore,
    controlRoot: Path,
    clock: Clock,
): RuntimeIntrospectionPort {
    val journal = SqliteOperationJournalImpl(
        connectionFactory = eventStore.underlyingConnectionFactory(),
        clock = clock,
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        dbPath = eventStore.databasePath(),
    )
    val cursors = SqliteReplayCursorStoreImpl(eventStore.underlyingConnectionFactory(), clock)
    val lease = FileBackedRunExecutionLeaseStore(controlRoot.resolve("leases"))
    val output = OutputPlaneProvider.storeForReading(controlRoot)
    val eventReader = EventRecordReadPortStoreAdapter(
        store = eventStore,
        runExists = { runId -> lease.isKnown(runId) || eventStore.hasRun(runId) },
        tailSequence = { runId -> eventStore.tailSequence(runId) },
    )
    return RuntimeIntrospectionPortStoreAdapter(
        eventReads = eventReader,
        tails = output,
        frames = output.frameIndex(),
        journal = journal,
        cursors = cursors,
        lease = lease,
    )
}
