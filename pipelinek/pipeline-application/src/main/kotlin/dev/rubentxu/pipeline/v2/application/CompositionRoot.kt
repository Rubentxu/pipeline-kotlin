package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.FileBasedWaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.durable.credentials.WithCredentialsExecutorScopeAdapter
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
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
import dev.rubentxu.pipeline.v2.runtime.recover.LeaseAsAcquired
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
 *
 * ## What the coordinator no longer receives
 *
 * The coordinator used to take twenty-nine parameters, and this function was the
 * place that spelled them out one by one. Most of them are now DERIVED inside the
 * coordinator, from the three groups the request already carries:
 *
 * ```text
 * state       the durable spine (journal, cursor, sink, wakeups, saga)
 * policy      what the run was given (clock, replay policy, control root, sandbox, ...)
 * composition what the run resolves against (steps, directives, events, capabilities)
 * ```
 *
 * Three decisions moved out of THIS file and into the coordinator, and that is the
 * point of the cut — they were values a caller could contradict:
 *
 *  - the **shell transport** is a consequence of the policy (the workspace root, the
 *    sandbox profile, the network egress and the workspace ownership all follow from
 *    facts the policy already holds), so it is derived and not passed;
 *  - the **output retention** is a PRODUCT decision (`ExplicitReleaseOnly`), so it is
 *    a value the coordinator owns rather than a knob this file turns;
 *  - the **capability set** is composed from the composition's plugin contributions
 *    and the policy's credential provider, INSIDE the coordinator, so that the ONE
 *    instance is the one the run's admission, execution and directive seam consult.
 *
 * What is left here are the facts this file is the authority for: the credential
 * seam, the divergence policy, the gate's environment, the two durable control
 * journals (whose constructors create directories, so they cannot be derived
 * without writing to disk at composition time), and the scripted seam — which is
 * `null` here because the scripted frontend runs on ITS OWN runner. That surface
 * composes the same durable authorities, but it adapts its execution boundary
 * WITHOUT the coordinator's capability contributor, so the two surfaces do not
 * carry the same capability set. This file states that difference instead of
 * hiding it behind a claim of symmetry.
 */
internal fun runCanonical(request: CanonicalRunRequest): RunOutcome = runBlocking {
    val state = request.state
    val policy = request.policy
    CanonicalDurableRunCoordinator(
        // The run's durable spine, what it was given, and what it resolves against.
        // The three groups are the whole request: the coordinator derives the rest.
        state = state,
        policy = policy,
        composition = request.composition,
        // The credential seam. The adapter over the executor THIS invocation opened;
        // the store it wraps is per-run state, so it cannot come from the composition.
        credentialScopePort = WithCredentialsExecutorScopeAdapter(
            policy.withCredentialsExecutor,
            state.eventSink,
        ),
        // The divergence POLICY, as a port. The coordinator declares the interface and
        // its collaborator declares it too, so the implementation is chosen HERE and
        // reaches the run without a downcast that would refuse every other policy.
        divergenceDetector = StrictFingerprintDivergenceDetector(),
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
        // the declared-wins rule that [GateEnvironmentPrecedence] states.
        gateContext = { stageEnvironment ->
            dev.rubentxu.pipeline.v2.domain.directive.GateEnvironmentPrecedence.DEFAULT.resolve(
                declared = stageEnvironment.values,
                host = System.getenv(),
            )
        },
        // Production wire-up. The retry aggregate is reconciled against the on-disk
        // control journal so a `run` invocation with the same --db and --control-root
        // reuses the prior aggregate terminal state and does not re-launch child bodies
        // that already succeeded or failed terminally.
        retryControlJournal = FileBasedRetryControlJournal(policy.controlDirRoot),
        // Durable waitUntil control journal. Matches the retryControlJournal pattern —
        // persisted before child effects, read on plan(), authoritative over the
        // aggregate state on replay.
        waitUntilControlJournal = FileBasedWaitUntilControlJournal(policy.controlDirRoot),
        // The scripted seam is NOT bound here, and the null is a fact rather than an
        // omission: the scripted frontend (ScriptedFrontendRunner) composes the same
        // durable authorities on its own runner, and no canonical run binds it. A run
        // that reaches a scripted stage without this seam says so instead of quietly
        // executing a stage that did nothing.
        scriptedStageExecution = null,
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
 * ## The lease the introspection reads
 * The introspection does not read the raw lease record: it reads the lease as
 * the recovery that has taken it sees it ([LeaseAsAcquired]). In the recovery
 * that is the record the acquisition just wrote, and in the dry run it is the
 * record the acquisition WOULD write — the same three cases either way, so
 * the two decide on the same facts and cannot drift apart. The raw store is
 * still what the recover TAKES and what the dry run PROBES; only the view is
 * shared.
 *
 * ## Why the output plane is opened for WRITING
 * A recover reconciles the output store: it truncates the uncommitted tail a
 * dead writer left behind. A reader must never do that — a reader truncating
 * a live writer's bytes is the defect the output plane's recovery split
 * exists to prevent — and this is not a reader: it holds the run's lease.
 *
 * ## Why a dry run opens the READ side of both planes
 * A dry run writes nothing, so it must not open a plane that writes. The
 * output plane's writing store RECOVERS as it is constructed, and a
 * recovery truncates an uncommitted tail — a write, under a verb that
 * promised none. The event store's write lane opens the database in a mode
 * that writes its header and checkpoints the WAL on close; the read lane is
 * the door the engine refuses to write through.
 *
 * @param dryRun Whether this composition answers WITHOUT writing. The caller
 *   and the [dev.rubentxu.pipeline.v2.runtime.recover.RecoverOptions] it builds
 *   must agree: the flag here chooses the LANES, and the option chooses
 *   whether the adapter writes. A composition that opened the writing lanes
 *   for a dry run would write before the adapter was ever asked.
 */
internal fun composeRuntimeRecoverPort(
    eventStore: SqliteEventStore,
    controlRoot: Path,
    clock: Clock,
    dryRun: Boolean = false,
): RuntimeRecoverPort {
    val journal = SqliteOperationJournalImpl(
        connectionFactory = eventStore.underlyingConnectionFactory(),
        clock = clock,
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        dbPath = eventStore.databasePath(),
    )
    val cursors = SqliteReplayCursorStoreImpl(eventStore.underlyingConnectionFactory(), clock)
    val lease = FileBackedRunExecutionLeaseStore(controlRoot.resolve("leases"))
    val output = if (dryRun) {
        OutputPlaneProvider.storeForReading(controlRoot)
    } else {
        OutputPlaneProvider.storeForWriting(controlRoot)
    }
    // One identity for both planes: the recover holds the lease, and the
    // introspection it asks has to recognise that holder as itself.
    val owner = "cli-recover-" + ProcessHandle.current().pid() + "-" +
        java.util.UUID.randomUUID().toString().take(8)
    val leaseView = LeaseAsAcquired(
        lease,
        requireNotNull(RunOwnerId.of(owner)) { "the recover owner id is always well formed" },
    )
    val eventReader = EventRecordReadPortStoreAdapter(
        store = eventStore,
        runExists = { runId -> leaseView.isKnown(runId) || eventStore.hasRun(runId) },
        tailSequence = { runId -> eventStore.tailSequence(runId) },
    )
    val introspect = RuntimeIntrospectionPortStoreAdapter(
        eventReads = eventReader,
        tails = output,
        frames = output.frameIndex(),
        journal = journal,
        cursors = cursors,
        lease = leaseView,
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
        // The pins of this root. The recover port consults them before it declares a run terminal,
        // so a run whose bytes somebody still holds is NOT declared recovered-and-done. The
        // substrate is the root's, not the store's, so the reader and the writer of the root answer
        // the same question with the same manifest.
        pinPort = OutputPlaneProvider.pinPortFor(controlRoot),
        syntheticObserverId = owner,
    )
}

