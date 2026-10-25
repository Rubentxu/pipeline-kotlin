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
import dev.rubentxu.pipeline.v2.output.RetainUntil
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
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
import dev.rubentxu.pipeline.v2.runtime.recover.RuntimeRecoverPort
import dev.rubentxu.pipeline.v2.runtime.recover.RuntimeRecoverPortStoreAdapter
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * The ONE place a pipeline is run.
 *
 * This function took EIGHTEEN parameters and had two call sites — the
 * in-memory world and the durable one — which is exactly the shape in
 * which two call sites drift. It now takes ONE [CanonicalRunRequest], and
 * both worlds build the same request. The difference between "runs in
 * memory" and "runs in SQL" is the [CanonicalRunState] group: the journal,
 * the cursor store, the event sink and the Saga seam. The rest of the
 * request — the clock, the replay policy, the sandbox, the workspace, the
 * credential seam — is the SAME set of facts for both, filled in with the
 * values each world decided. Two of them differ on purpose (the credential
 * provider and the control root, by construction); [CanonicalRunRequest]
 * says which.
 *
 * Two parameters are GONE, and their absence is the point:
 *
 * - `capabilityContributors` defaulted to `null`, which meant "whatever the
 *   composition resolved". The composition is now the only source of plugin
 *   capabilities, and no caller ever passed anything else, so the knob was a
 *   way to inject a FOURTH authority. It is removed, not deprecated.
 * - `workspaceBase` and `workspaceOwnership` were two parameters that had to
 *   be kept in step; they are now one [RuntimeWorkspaceTransport], the value
 *   the CLI already computes.
 *
 * This function is `internal` rather than `private` only because Kotlin file
 * privacy would prevent the two run worlds from calling it. No public API or
 * CLI contract is introduced.
 */
internal fun runCanonical(request: CanonicalRunRequest): RunOutcome = runBlocking {
    val state = request.state
    val policy = request.policy
    CanonicalDurableRunCoordinator(
        // The ONE registry this run resolves plugin event kinds against, on the write
        // side and on the read-back side. Resolved in PluginComposition, before this
        // function was called, and already cross-checked against what the plugins emit.
        eventRegistry = request.composition.events,
        wakeups = state.outputWakeups,
        // ONE composite, consulted by both admission and execution.
        //
        // The plugin side comes from the composition, resolved under the same classloader
        // swap as the steps these capabilities back. The credential side stays here because it is
        // per-run state — it wraps the store THIS invocation opened, so it cannot come from
        // classpath discovery. The two never collide: a plugin deliberately contributes only its
        // own transport.
        capabilityContributor = CompositeCapabilityContributor(
            request.composition.capabilityContributors +
                dev.rubentxu.pipeline.v2.credentials.executor.BasicCredentialsCapabilityContributor(
                    policy.credentialProvider,
                ),
        ),
        dispatcher = CanonicalNodeDispatcher(),
        journal = state.journal,
        cursorStore = state.cursorStore,
        clock = policy.clock,
        effectReplayPolicy = policy.effectReplayPolicy,
        eventSink = state.eventSink,
        credentialScopePort = WithCredentialsExecutorScopeAdapter(
            policy.withCredentialsExecutor,
            state.eventSink,
        ),
        controlDirRoot = policy.controlDirRoot,
        workspaceBase = policy.workspace.base,
        shOptions = ShOptions(
            // With --workspace <dir>, the project's own directory IS the
            // workspace — stages share it (Jenkins-familiar semantics). Adding
            // .resolve("workspace") would point to a subdirectory of the project root
            // (typically nonexistent), and every `sh` step would fail with
            // "No such file or directory" because gradlew/mvn/node live in
            // the project root itself. Without --workspace we keep the legacy
            // per-stage layout.
            workspaceRoot = policy.workspace.base ?: policy.controlDirRoot.resolve("workspace"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
            sandbox = SandboxConfigResolver.resolve(policy.sandboxProfile),
            // Carry the ownership the CLI decided into the runtime transport, so the
            // destructive guard reads the real owner of this root instead of
            // re-deriving `Managed`.
            workspaceOwnership = policy.workspace.ownership,
            // Egress is DENIED unless the operator asked for it with --allow-network.
            // The bridge turns the operator's request into the generic NETWORK_EGRESS_CAPABILITY,
            // and a step that needs the network declares it — so the default run
            // cannot reach one. The two ends are gates, not verdicts: `--allow-network`
            // is [AllowAll] and the default is [DenyAll], which are the SAME interface as a
            // future per-destination allowlist — so adding one later is a new value here
            // and not a new code path through the runtime, the step, or the admission check.
            networkEgress = if (policy.allowNetwork) AllowAll else DenyAll,
        ),
        // Core steps first, then external plugin contributions, frozen by PluginComposition
        // before this call.
        stepRegistry = request.composition.steps,
        secretPatternRegistry = policy.secretPatternRegistry,
        // Forwarded so the dispatch can build the invocation-bound intent port.
        sagaBinding = state.sagaBinding,
        // Production wire-up. The retry aggregate is reconciled against the on-disk
        // control journal so a `run` invocation with the same --db and --control-root
        // reuses the prior aggregate terminal state and does not re-launch child bodies
        // that already succeeded or failed terminally.
        retryControlJournal = FileBasedRetryControlJournal(policy.controlDirRoot),
        // Durable waitUntil control journal. Matches the retryControlJournal pattern —
        // persisted before child effects, read on plan(), authoritative over the
        // aggregate state on replay.
        waitUntilControlJournal = FileBasedWaitUntilControlJournal(policy.controlDirRoot),
        // Per-run artifact index for the core.archiveArtifacts -> core.artifact.query
        // bridge. Constructed fresh per run; the same instance is shared between the
        // producer (archive with name=...) and the consumer (artifactQuery) within the run.
        artifactIndex = dev.rubentxu.pipeline.v2.application.durable.ArtifactIndexAdapter.build(),
        // The run's terminal state is the ONLY thing that can authorise discarding
        // its output, and the runtime is the authority that knows it. Declared here — ONCE, where
        // every other composition decision is declared — so the policy is a value a reviewer can
        // read rather than a branch somebody can find at the call site.
        //
        // `ExplicitReleaseOnly`, and the reason is the product's own console: `pipeline console
        // --control-dir` reads the transcript of a run that ALREADY FINISHED, so a
        // `RunTerminalPlus` here would delete the very output the product exists to serve. That is
        // a product decision, and it has a stated consequence: nothing releases automatically, and
        // the operator-release surface that would exercise `OperatorReleased` is not built yet.
        //
        // The store arrives as a SUPPLIER, so this policy that retains never opens (and never
        // recovers) the Output Plane for the release it will not perform.
        outputRetention = RunOutputRetention(
            retention = { OutputPlaneProvider.storeFor(policy.controlDirRoot) },
            policy = RetainUntil.ExplicitReleaseOnly,
        ),
        // Resolved in PluginComposition, under the same classloader swap that produced
        // the step registry and the event registry, and frozen before this call.
        //
        // `core.when` and `core.agent` are CORE definitions that enter through the
        // SAME open registry as any vendor directive, which is what keeps "open by key"
        // honest. If either had needed its own registration path, the seam would
        // not be open.
        directiveRegistry = request.composition.directives,
        // Gates read the environment the STAGE actually declares, plus
        // the process environment for names the stage names explicitly.
        //
        // The stage's own `environment { env(...) }` block is the source of
        // truth, because that is what the DSL can express. `pipeline.environment`
        // is a separate pipeline-level spec the DSL does not populate, so
        // reading only that would make every real gate see an empty world and
        // skip unconditionally.
        //
        // Only variables the stage DECLARES are read from the host: a gate must
        // not be able to observe an arbitrary ambient process variable, or the
        // same script would behave differently on different machines for
        // reasons the author never wrote down.
        //
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
    ).run(request.pipeline, request.runId)
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

/**
 * The recovery plane: the published [RuntimeRecoverPort] composed over the
 * same stores the rest of the product writes.
 *
 * ## Why the introspector and the recover share ONE identity
 * The recover TAKES the run's lease before it looks at the run, because the
 * lock is the only authority on "is a live process publishing this run". The
 * introspection it then asks would consult the lease record and find the
 * recover's OWN lease — and refuse, because the plane refuses when the
 * record names a live owner.
 *
 * So both are composed with the same owner id: the introspection sees "the
 * lease is mine", which is a re-entry, and describes the run instead of
 * refusing to.
 *
 * ## Why the output plane is opened for WRITING
 * A recover reconciles the output store: it truncates the uncommitted tail a
 * dead writer left behind. A reader must never do that — a reader truncating
 * a live writer's bytes is the defect the output plane's recovery split
 * exists to prevent — and this is not a reader: it holds the run's lease.
 */
internal fun composeRuntimeRecoverPort(
    eventStore: SqliteEventStore,
    controlRoot: Path,
    clock: Clock,
): RuntimeRecoverPort {
    val journal = SqliteOperationJournalImpl(
        connectionFactory = eventStore.underlyingConnectionFactory(),
        clock = clock,
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        dbPath = eventStore.databasePath(),
    )
    val cursors = SqliteReplayCursorStoreImpl(eventStore.underlyingConnectionFactory(), clock)
    val lease = FileBackedRunExecutionLeaseStore(controlRoot.resolve("leases"))
    val output = OutputPlaneProvider.storeForWriting(controlRoot)
    val eventReader = EventRecordReadPortStoreAdapter(
        store = eventStore,
        runExists = { runId -> lease.isKnown(runId) || eventStore.hasRun(runId) },
        tailSequence = { runId -> eventStore.tailSequence(runId) },
    )
    // One identity for both planes: the recover holds the lease, and the
    // introspection it asks has to recognise that holder as itself.
    val owner = "cli-recover-" + ProcessHandle.current().pid() + "-" +
        java.util.UUID.randomUUID().toString().take(8)
    val introspect = RuntimeIntrospectionPortStoreAdapter(
        eventReads = eventReader,
        tails = output,
        frames = output.frameIndex(),
        journal = journal,
        cursors = cursors,
        lease = lease,
        syntheticObserverId = owner,
    )
    return RuntimeRecoverPortStoreAdapter(
        journal = journal,
        cursors = cursors,
        lease = lease,
        outputRecovery = output,
        frames = output.frameIndex(),
        introspect = { runId -> introspect.inspect(runId) },
        clock = clock,
        syntheticObserverId = owner,
    )
}
