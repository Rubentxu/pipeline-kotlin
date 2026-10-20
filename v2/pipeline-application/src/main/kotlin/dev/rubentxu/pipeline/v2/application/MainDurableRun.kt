package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.SagaVerdict
import dev.rubentxu.pipeline.v2.application.durable.supportsCanonicalDurableExecution
import dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import dev.rubentxu.pipeline.v2.events.durable.SqliteReplayCursorStoreImpl
import dev.rubentxu.pipeline.v2.events.durable.SqliteSagaObligationStore
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptedSource
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import dev.rubentxu.pipeline.v2.scripting.isRuntimeReturning
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import dev.rubentxu.pipeline.v2.domain.PipelineFailure

/**
 * The durable world: a run with `--db`.
 *
 * ## What makes it a different world
 *
 * Not the algorithm: the same canonical coordinator, the same rules, the same
 * capability set. What is different is that this world PROMISES something. It
 * takes a lease on the run before it writes a single event, so two processes
 * cannot publish into the same run; it selects the run id by policy
 * (`--resume`/`--rerun`) instead of minting a fresh one; and it streams the
 * events out one at a time instead of materialising them, because a 1 GiB
 * transcript must not become a multi-gigabyte string.
 *
 * ## The lease is not optional
 *
 * Without it, two concurrent `--resume` invocations of the same definition
 * both proceed and collide on the durable uniqueness index, and the loser sees
 * a raw SQLite constraint violation instead of a typed refusal. The lease
 * turns that crash into an admission decision, and its epoch is what fences a
 * writer that lost the run to a takeover.
 */
internal fun runDurablePipeline(
    config: CliFlags,
    dbPath: String,
    secretPatternRegistry: SecretPatternRegistry,
    compiledQuery: CompiledObservationQuery,
    scriptPath: Path,
) {
    // Durable mode: SqliteEventStore + PipelineOrchestrator for replay/divergence gating.
    // Both stores are wrapped with RedactingEventSink at construction time (design §Data Flow).
    val store = admitDurableEventStore(dbPath, secretPatternRegistry)
    val rawEventStore = store.rawEventStore
    val eventStore = store.eventStore

    // The script-derived hash is the DefinitionId. Durable policy decides
    // whether its persisted RunId is reused or intentionally replaced.
    val scriptContent = scriptPath.toFile().readText()
    val definitionId = dev.rubentxu.pipeline.v2.domain.DeterministicIdGenerator.definitionId(
        scriptPath.toString(),
        scriptContent,
    )
    val controlDirRoot = resolveDurableControlRoot(config, dbPath)
    // WU-LPR-WC: the canonical engine threads `workspaceBase` through
    // `CanonicalRuntimeContext.workspaceBase`; the capability bridge
    // populates `WORKSPACE_IDENTITY_CAPABILITY` from
    // `context.shOptions.workspaceRoot`. Plugins consume the typed
    // seam via `StepHandlerContext.capabilities.get<WorkspaceIdentity>(...)`
    // instead of reading a process-global property. The system-property
    // bridge has been removed: no production code reads
    // `pipeline.workspace.root` any more (the historical writers were
    // F5.1 SCM/Git and F5.2 JUnit; F5.2 JUnit migrated to the typed
    // capability here; F5.1 SCM/Git migration is filed as a sibling
    // follow-up, OUT OF SCOPE for this WU).
    val identity = establishDurableIdentity(
        config = config,
        eventStore = eventStore,
        controlDirRoot = controlDirRoot,
        definitionId = definitionId,
    )
    val runId = identity.runId
    val lease = identity.lease
    val source = compileDurableScript(
        eventStore = eventStore,
        runId = runId,
        scriptPath = scriptPath,
        scriptContent = scriptContent,
        pluginJars = config.pluginJars,
    )
    val compilation = source.compilation
    val scripted = source.scripted
    val compiledPipeline = source.pipeline

    // Build orchestrator with all durable dependencies
    val clock: dev.rubentxu.pipeline.v2.domain.durable.Clock = SystemClock()
    val journal: OperationJournal = SqliteOperationJournalImpl(
        store.factory,
        clock,
        Json { ignoreUnknownKeys = true; encodeDefaults = true },
        store.dbPathStr,
    )
    // SAGA / PK-1: the durable Saga obligations live in the SAME database as the journal and
    // the event log, through the same connection factory. One authority for the run's durable
    // state, not a second store with its own lifecycle.
    val sagaObligationStore = SqliteSagaObligationStore(
        connectionFactory = store.factory,
        dbPath = store.dbPathStr,
    )
    val cursorStore: ReplayCursorStore = SqliteReplayCursorStoreImpl(store.factory, clock)
    val effectPolicy: EffectReplayPolicy = DefaultEffectReplayPolicy()
    val credentials = composeCredentialSeam(secretPatternRegistry, clock)

    // LF-0205 (closed LEG-1.1): the legacy PipelineOrchestrator was constructed here but
    // never invoked. The CLI reaches the durable runtime ONLY through the canonical
    // RunCoordinator (fail-closed exit 2 otherwise). LEG-1 burn-down deleted the class.
    // LB-02 / EP-6: compose the registry BEFORE the canonical-eligibility gate —
    // eligibility is registry-derived, so external plugin contributions must be
    // visible to the gate or a contributed key would be wrongly rejected as
    // non-canonical. Same composed registry is handed to the coordinator below.
    // S6-COMPOSITION: same single entry point as the in-memory branch above. Writing the admission
    // wiring out a second time here would let the two branches disagree about which plugins were
    // admitted, which is precisely the defect S6/G removed once already.
    val composition = admitThenComposeOrExit(config.pluginJars)
    val composedStepRegistry = composition.steps
    // RP034-H / ADR-0101 clause 3.1 + RP034-Id: the workspace origin is decided
    // ONCE, here at the boundary, and both facts cross into the runtime — the
    // shared directory and its owner. `--isolated` keeps the historical
    // PipelineK scratch; `--workspace <path>` stays authoritative.
    val workspaceTransport = WorkspaceIntent.resolveRuntimeTransport(
        config, Path.of("").toAbsolutePath().normalize(), controlDirRoot,
    )
    // SAGA: the terminal state of the Sagas this run executed. The verdict block has to
    // say it: a Saga that needs a human and a Saga that was fully compensated look
    // identical on stderr otherwise, and they call for opposite responses from whoever
    // reads the run.
    //
    // The EVENT, not the obligation store: the event is the published fact, and the store
    // answers a different question (what is owed, not what was concluded). Declared HERE,
    // outside the `try`, because the verdict is printed after it — and because a run that
    // threw still has to say what it left behind.
    val sagaTerminals = mutableListOf<SagaTerminal>()
    // UAT-RP-024 collateral finding fix (shutdown race 1/3): the sqlite
    // single-writer thread is NON-daemon. If any exception escapes the run
    // or the stdout envelope streaming below, rawEventStore.close() is
    // skipped, the writer keeps blocking on queue.take(), and
    // DestroyJavaVM hangs forever (observed: first replay of the dogfood
    // receipt hung until manual kill). Compute the outcome and the last
    // event inside the try; guarantee the close in finally on every path.
    val runOutcomeAndLastEvent: Pair<RunOutcome?, DomainEvent?> = try {
        val outcome: RunOutcome? = when {
            // LFC-2R / R4B: the scripted FRONTEND form runs against the SAME durable
            // authority (journal, registry, event sink, control root) composed for the
            // canonical path. This is a frontend selection, never a second runner: the
            // invocation authority is the registry seam proven in R1/R2 (R4A model B).
            scripted.entryPoint != null -> runScriptedFrontend(
                entryPoint = scripted.entryPoint,
                runId = runId,
                artifact = requireNotNull(scripted.artifact) { "scripted frontend artifact" }.identity,
                stepRegistry = composedStepRegistry,
                journal = journal,
                eventSink = eventStore,
                clock = clock,
                controlDirRoot = controlDirRoot,
                sandboxProfile = config.sandboxProfile,
            )
            compiledPipeline != null -> {
                if (compiledPipeline.supportsCanonicalDurableExecution(composedStepRegistry)) {
                    runCanonical(
                        CanonicalRunRequest(
                            pipeline = compiledPipeline,
                            runId = RunId(runId),
                            state = CanonicalRunState(
                                journal = journal,
                                cursorStore = cursorStore,
                                eventSink = eventStore,
                                // SAGA / PK-1: the run's Saga seam — the durable store plus the lease epoch that
                                // fences a writer that lost this run to a takeover.
                                sagaBinding = dev.rubentxu.pipeline.v2.application.durable.SagaCompensationBinding(
                                    store = sagaObligationStore,
                                    leaseEpoch = lease.leaseEpoch,
                                ),
                            ),
                            policy = CanonicalRunPolicy(
                                clock = clock,
                                effectReplayPolicy = effectPolicy,
                                controlDirRoot = controlDirRoot,
                                sandboxProfile = config.sandboxProfile,
                                // RP034-H / ADR-0101 clause 3.1: the CLI default is local-first. The
                                // workspace origin is decided once, here at the boundary, and BOTH
                                // facts cross into the runtime: the shared directory and its owner.
                                // `--isolated` preserves the historical PipelineK-managed scratch;
                                // `--workspace <path>` stays authoritative.
                                workspace = workspaceTransport,
                                // H5-B: same on the canonical run path. The credential seam is offered on
                                // EVERY path or it is offered inconsistently, and an inconsistently
                                // admitted Step is a Step whose behaviour depends on which branch the CLI
                                // happened to take.
                                credentialProvider = credentials.provider,
                                withCredentialsExecutor = credentials.executor,
                                allowNetwork = config.allowNetwork,
                                secretPatternRegistry = secretPatternRegistry,
                            ),
                            // S6/G: the frozen composition, not a step registry plus a classloader.
                            composition = composition,
                        ),
                    )
                } else {
                    // Fail-closed: non-canonical pipelines are not supported by the canonical bridge.
                    System.err.println(NON_CANONICAL_CANONICAL_BRIDGE_ERROR)
                    System.exit(2)
                    null // unreachable
                }
            }
            else -> compilation.failure
        }

        // WU-LPR-089 + WU-LPR-011 regression fix: the WU-LPR-042 single-writer thread
        // is non-daemon and processes events asynchronously. We must FLUSH the pending
        // writes BEFORE reading the events back from SQL, otherwise async events emitted
        // just before run-finished (e.g. StashCreated/StashRestored from a final stage)
        // are lost from the stdout JSON envelope (the SQL row exists but hasn't been
        // committed when eventsFor(runId) runs). After flush, close() stops the writer
        // and releases the persistent connection; the JVM exits cleanly because
        // DestroyJavaVM no longer waits on queue.take().
        rawEventStore.flush()
        val lastEventCaptured = streamDurableEvents(
            eventStore = eventStore,
            runId = runId,
            sagaTerminals = sagaTerminals,
            view = config.view,
            format = config.format,
            compiledQuery = compiledQuery,
        )
        rawEventStore.close()
        Pair(outcome, lastEventCaptured)
    } finally {
        // S2-R0: release ownership on every exit path, including the
        // exception paths, so a crashed run is immediately recoverable by the
        // next owner instead of waiting for the OS to reap the lock.
        runCatching { lease.leaseStore.release(requireNotNull(lease.runOwner)) }
        runCatching { lease.leaseStore.close() }
        runCatching { rawEventStore.close() }
    }
    val runOutcome = runOutcomeAndLastEvent.first
    val lastEvent = runOutcomeAndLastEvent.second
    val exitFailure = durableRunFailed(runOutcome, lastEvent)
    // SAGA: what happened to the Sagas of this run. It belongs in the verdict block, next
    // to the reason the run failed, because "the Saga settled" and "a human has to resolve
    // a pending obligation right now" are different worlds and the exit code cannot say
    // which one this was.
    // The Saga verdict: what happened to the Sagas this run executed, from the
    // EVENT and not from the obligation store (the event is the published fact,
    // and the store answers a different question: what is owed, not what was
    // concluded). It is printed in BOTH worlds: a Saga can run with and without
    // `--db` (P6), and a run that keeps its promises has to say so in each.
    SagaVerdict.verdicts(sagaTerminals).forEach { System.err.println(it) }
    if (exitFailure) System.exit(1)
}

/**
 * The source of a durable run, compiled and classified.
 *
 * The DEFINITION is compiled BEFORE the composition is admitted — the order the durable
 * branch has always had. A definition the runtime refuses must not first print the
 * plugin-discovery lines: the operator has to read the refusal, not a preamble.
 */
private data class DurableScript(
    val compilation: ScriptCompilation,
    val scripted: ScriptedFrontend,
    val pipeline: dev.rubentxu.pipeline.v2.domain.CompiledPipeline?,
)

private fun compileDurableScript(
    eventStore: dev.rubentxu.pipeline.v2.events.EventSink,
    runId: String,
    scriptPath: Path,
    scriptContent: String,
    pluginJars: List<String>,
): DurableScript {
    val compilation = compileScriptForRun(
        eventStore = eventStore,
        runId = runId,
        scriptPath = scriptPath,
        pluginJars = pluginJars,
    )
    val scripted = resolveScriptedFrontend(
        compilation = compilation,
        scriptPath = scriptPath,
        scriptContent = scriptContent,
        pluginJars = pluginJars,
        eventSink = eventStore,
        runId = runId,
    )
    val pipeline = compilation.pipelineSpec?.let { spec ->
        compileOrRefuse(
            spec = spec,
            sourcePath = scriptPath.toString(),
            sourceContent = scriptContent,
        )
    }
    return DurableScript(compilation = compilation, scripted = scripted, pipeline = pipeline)
}

/**
 * Stream a durable run's events to stdout and report the last one.
 *
 * WU-RP-044 (M5 RSS debt): the JSON envelope goes out one event at a time
 * (identical byte output to JsonEventLog.encode) and only the last event is
 * tracked for the legacy outcome branch — never the full list, never a single
 * monolithic JSON String (1 GiB transcripts made this multi-GB).
 */
private fun streamDurableEvents(
    eventStore: dev.rubentxu.pipeline.v2.events.EventSink,
    runId: String,
    sagaTerminals: MutableList<SagaTerminal>,
    view: dev.rubentxu.pipeline.v2.application.observation.ObservationView,
    format: dev.rubentxu.pipeline.v2.application.observation.ObservationFormat,
    compiledQuery: CompiledObservationQuery,
): DomainEvent? {
    val eventSequence = eventStore.eventsFor(runId)
    var lastEventCaptured: DomainEvent? = null
    val stdout = System.out
    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(stdout, Charsets.UTF_8), 1 shl 16)
    val lastEventRef = { event: DomainEvent -> lastEventCaptured = event }
    RunObservationOutput.writeTo(
        eventSequence.onEach { event ->
            lastEventRef(event)
            if (event is SagaTerminal) sagaTerminals += event
        },
        writer,
        view,
        format,
        compiledQuery,
    )
    writer.flush()
    return lastEventCaptured
}

/**
 * The identity a durable run publishes: which run it is, which correlation it
 * answers to, and who owns it.
 *
 * The lease is taken BEFORE the correlation is emitted, because the owner in
 * the receipt has to be the owner that holds the run — S2-R0 in one place
 * instead of three statements at a call site that a reader has to reconstruct.
 */
private data class DurableRunIdentity(
    val runId: String,
    val correlationId: CorrelationId,
    val lease: DurableLease,
)

private fun establishDurableIdentity(
    config: CliFlags,
    eventStore: dev.rubentxu.pipeline.v2.events.EventSink,
    controlDirRoot: Path,
    definitionId: dev.rubentxu.pipeline.v2.domain.DefinitionId,
): DurableRunIdentity {
    val runIdDirectory = RunIdDirectory(controlDirRoot.resolve("last-run"))
    // PK-1 / ADR-PK1-IDENTITY: build the correlationId once here at
    // the CLI boundary so both the durable last-run record and the
    // RunCorrelated event share the same token.
    val pk1Correlation: CorrelationId = config.correlationId?.let { CorrelationId(it) }
        ?: UuidCorrelationIdGenerator().next()
    val runSelection: DurableRunSelection = try {
        selectDurableRun(
            policy = config.durableRunPolicy,
            runIdDirectory = runIdDirectory,
            definitionId = definitionId,
            runIdGenerator = UuidRunIdGenerator(),
            correlationId = pk1Correlation,
        )
    } catch (e: IllegalArgumentException) {
        // WU-LPR-011 F4: --resume without a prior durable run is an
        // invocation/admission error, not a pipeline failure. Typed
        // rejection, no stack trace, exit 2 per the CLI exit contract.
        System.err.println("Error: ${e.message}")
        System.exit(2)
        throw e // unreachable
    }
    val runId = runSelection.runId.value
    val lease = acquireDurableLease(controlDirRoot, runId)
    // PK-1 / ADR-PK1-IDENTITY: lease is held; emit the RunCorrelated
    // event on the durable event sink and the LaunchReceipt on stdout.
    // The owner is the lease-acquired runOwner.
    // PK-1 / ADR-PK1-IDENTITY: the durable branch's correlation is the
    // one persisted alongside the runId; fall back to the CLI-bound
    // token if the durable record has none.
    emitCorrelationAndReceipt(
        eventStore = eventStore,
        runId = RunId(runId),
        correlationId = pk1Correlation,
        ownerId = requireNotNull(lease.runOwner),
        startedAtMs = System.currentTimeMillis(),
        controlDir = controlDirRoot,
        format = config.format,
    )
    return DurableRunIdentity(runId = runId, correlationId = pk1Correlation, lease = lease)
}

/**
 * The stores of a durable run, admitted once.
 *
 * D3 — a run CREATES its event store, so the file is legitimately absent; only the DIRECTORY
 * has to exist. That is why the purpose is part of the admission and not a flag at each site:
 * `run` used to reach the same constructor as the read verbs and inherited neither the refusal
 * nor the exit code, so a `--db` under a missing directory escaped as a raw SQLException with
 * exit 1 — the code this CLI reserves for a pipeline that ran and failed.
 */
private data class DurableEventStores(
    val rawEventStore: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
    val factory: () -> java.sql.Connection,
    val dbPathStr: String,
    val eventStore: dev.rubentxu.pipeline.v2.events.EventSink,
)

private fun admitDurableEventStore(
    dbPath: String,
    secretPatternRegistry: SecretPatternRegistry,
): DurableEventStores {
    val dbAdmission = admitEventStore(dbPath, EventStorePurpose.Write)
    if (dbAdmission is EventStoreAdmission.Refused) {
        System.err.println(dbAdmission.refusal.render())
        // `main` returns Unit and this is the depth at which every other refusal ends, so the
        // exit is the process's rather than a value handed upwards.
        System.exit(2)
    }
    val rawEventStore = (dbAdmission as EventStoreAdmission.Admitted).store
    // Call raw methods BEFORE wrapping — RedactingEventSink delegates these to the inner store
    val factory = rawEventStore.underlyingConnectionFactory()
    val dbPathStr = rawEventStore.databasePath()
    val eventStore = RedactingEventSink(rawEventStore, secretPatternRegistry)
    return DurableEventStores(rawEventStore, factory, dbPathStr, eventStore)
}

/**
 * Where a durable run writes its control state.
 *
 * ML-R1: controlDirRoot is the parent directory of the SQLite db file (default).
 * Can be overridden via --control-root flag for testing.
 * Each step gets a subdirectory: $controlDirRoot/$runId-$stageIndex-$stepIndex/
 * C5: Validate control-root path before use
 */
private fun resolveDurableControlRoot(config: CliFlags, dbPath: String): Path =
    if (config.controlRoot != null) {
        try {
            validateControlRoot(config.controlRoot)
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.exit(2)
            throw e // unreachable
        }
    } else {
        Paths.get(dbPath).parent.resolve("durable-shell")
    }

/**
 * The lease this run holds, and the epoch that fences a writer that lost it.
 */
private data class DurableLease(
    val runOwner: RunOwnerId?,
    val leaseStore: FileBackedRunExecutionLeaseStore,
    val leaseEpoch: Long,
)

private fun acquireDurableLease(controlDirRoot: Path, runId: String): DurableLease {
    // S2-R0: first-class run ownership. Before any event is written for this
    // run we must prove we are the only process writing to it. Without this,
    // two concurrent `--resume` invocations of the same definition both proceed
    // and collide on the durable `ux_events_run_sequence` uniqueness index, so
    // the loser sees a raw SQLite constraint violation instead of a typed
    // refusal. The lease turns that crash into an explicit admission decision.
    val runOwner = RunOwnerId.of("cli-" + ProcessHandle.current().pid() + "-" +
        java.util.UUID.randomUUID().toString().take(8))
    val leaseStore = FileBackedRunExecutionLeaseStore(controlDirRoot.resolve("leases"))
    val lease = leaseStore.acquire(LeaseRequest(runId, requireNotNull(runOwner)))
    // SAGA / PK-1: the fencing token of the lease THIS process holds. It is the epoch the
    // durable Saga store uses to refuse a write from a writer that lost the run to a
    // takeover, so it has to travel from the acquisition to the store.
    val leaseEpoch: Long = when (lease) {
        is LeaseAcquisition.Acquired -> lease.fencingToken.value
        is LeaseAcquisition.TakenOver -> lease.fencingToken.value
        is LeaseAcquisition.Reentered -> lease.fencingToken.value
        is LeaseAcquisition.AlreadyOwned -> {
            // Typed admission rejection: this run already has a live owner.
            // Exit 2 (invocation/admission), not a pipeline failure.
            runCatching { leaseStore.close() }
            System.err.println(
                "Error: run $runId is already owned by '${lease.heldBy.value}'; " +
                    "another process is publishing events for this run"
            )
            System.exit(2)
            error("unreachable")
        }
        is LeaseAcquisition.Unverifiable -> {
            // Fail closed: a process that cannot prove it is the authority must
            // not publish. Kept distinct from AlreadyOwned because the
            // operator response differs.
            runCatching { leaseStore.close() }
            System.err.println("Error: cannot establish ownership of run $runId: ${lease.reason}")
            System.exit(2)
            error("unreachable")
        }
    }
    return DurableLease(runOwner = runOwner, leaseStore = leaseStore, leaseEpoch = leaseEpoch)
}

/**
 * The scripted frontend of a durable run, when the source is generator-level.
 *
 * LFC-2R / R4B — frontend FORM selection. The compiler-backed mapping decides
 * whether this source contains runtime-effectful scripted calls; the CLI only
 * picks the closed artifact FORM, never an execution authority and never a
 * concrete Step (R4A law: the CLI may choose the frontend, never the backend).
 */
private data class ScriptedFrontend(
    val artifact: dev.rubentxu.pipeline.v2.application.scripted.ScriptedFrontendRunner.EntryPointArtifact?,
    val entryPoint: dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint?,
)

private fun resolveScriptedFrontend(
    compilation: ScriptCompilation,
    scriptPath: Path,
    scriptContent: String,
    pluginJars: List<String>,
    eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
    runId: String,
): ScriptedFrontend {
    val result = compilation.result
    val artifact: dev.rubentxu.pipeline.v2.application.scripted.ScriptedFrontendRunner.EntryPointArtifact? =
        if (result.isSuccess) {
            val mapping = KotlinScriptedSourceMapper().map(
                ScriptedSource(ScriptedSourceId(scriptPath.fileName.toString()), scriptContent),
            )
            val mappedCalls = if (mapping is dev.rubentxu.pipeline.v2.scripting.ScriptedSourceMapping.Mapped) {
                mapping.calls.filter { it.kind.isRuntimeReturning() }
            } else emptyList()
            // R4B scope: the R3 lowering emits a generator-level entry point
            // body only. A `pipeline { stages { stage { ... } } }` structure
            // keeps the eager PipelineSpec frontend this slice (no DSL-body
            // migration, per the R4B GO). Frontend FORM selection, never an
            // execution-authority switch.
            val isGeneratorLevelSource = mappedCalls.isNotEmpty() &&
                !Regex("""\bpipeline\s*\{""").containsMatchIn(scriptContent)
            if (isGeneratorLevelSource) {
                when (val lowered = ScriptedSourceLowering.lower(
                    sourceId = ScriptedSourceId(scriptPath.fileName.toString()),
                    sourceText = scriptContent,
                    mapper = KotlinScriptedSourceMapper(),
                    facadeSchemaVersion = ScriptedSourceLowering.FACADE_SCHEMA_VERSION,
                )) {
                    is LoweringResult.Generated -> dev.rubentxu.pipeline.v2.application.scripted.ScriptedFrontendRunner.EntryPointArtifact(
                        loweredSource = lowered.source,
                        identity = lowered.artifact,
                    )
                    is LoweringResult.InvalidSyntax -> {
                        // The source already compiled (host), so this is an internal
                        // invariant violation: fail closed, never fall back to eager.
                        System.err.println("Error: scripted source lowering failed: ${lowered.diagnostics}")
                        System.exit(2)
                        null // unreachable
                    }
                }
            } else null
        } else null

    val entryPoint: dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint? =
        artifact?.let { artifactValue ->
            val definitionScript = """
                ${artifactValue.loweredSource}
            """.trimIndent()
            val scriptedDefinition = dev.rubentxu.pipeline.v2.scripting.ScriptDefinition.inline(
                text = definitionScript,
                classpath = computeScriptClasspath(pluginJars),
            )
            // Compiled WITHOUT the eager RuntimeConfig injection: a runtime-returned
            // body must never observe the platform through the eager DSL port.
            val host = Kotlin24ScriptingHost(eventSink, runId)
            val scriptedResult = host.compile(scriptedDefinition)
            when {
                scriptedResult is dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult.Failure -> {
                    System.err.println("Error: scripted frontend compilation failed: ${scriptedResult.diagnostics}")
                    System.exit(2)
                    null // unreachable
                }
                else -> when (val out = (scriptedResult as dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult.Success).output) {
                    is dev.rubentxu.pipeline.v2.scripting.ScriptEvaluationOutput.CompiledEntryPoint -> out.entryPoint
                    else -> {
                        System.err.println("Error: scripted frontend did not produce a compiled entry point")
                        System.exit(2)
                        null // unreachable
                    }
                }
            }
        }
    return ScriptedFrontend(artifact = artifact, entryPoint = entryPoint)
}

/**
 * The exit decision of a durable run.
 *
 * When the coordinator ran, the typed outcome is the single authority for the
 * exit decision; the legacy event-based branch only covers compile-failure
 * paths where no definition was produced.
 */
/**
 * H8-D2 — says WHY the run failed, on the stream an operator is already reading.
 *
 * ## The defect this exists to end
 *
 * The exit decision has always been correct: a failure exits 1. But the exit carried
 * no reason. stderr said "Pipeline finished with FAILURE", the JSON envelope's
 * `RunFinished` carried an empty `diagnostics`, and the typed `PipelineFailure`
 * message went nowhere an operator could see it.
 *
 * That silence is not cosmetic — it is the reason three separate delivery defects
 * shipped: a Step refused at admission, refused at execute-time, and refused for a
 * missing network permission all produced **byte-identical** output. A user filing
 * that report could not have told them apart, and neither could triage.
 *
 * ## Why stderr and not the event stream
 *
 * The `RunFinished` event is a lifecycle bookend and its shape is a published
 * contract; widening it is a separate decision. stderr is where the outcome is
 * already summarised one line above, and where `--allow-network` and every other
 * operator-facing diagnostic already goes. A message that is emitted nowhere is a
 * message that does not exist, and this one is the difference between a bug report
 * and a fix.
 *
 * The message is printed as-is, with no prefix of its own: the failure kinds are
 * already typed, and re-labelling them here would create a second vocabulary.
 */
internal fun reportRunFailure(failure: PipelineFailure) {
    System.err.println("  cause [${failure.kind}]: ${failure.message}")
    failure.cause?.let { System.err.println("  origin: $it") }
}

private fun durableRunFailed(runOutcome: RunOutcome?, lastEvent: DomainEvent?): Boolean =
    if (runOutcome != null) {
        when (runOutcome) {
            is RunOutcome.Success -> {
                System.err.println("Pipeline finished with SUCCESS"); false
            }
            is RunOutcome.Unstable -> {
                System.err.println("Pipeline finished with UNSTABLE"); false
            }
            is RunOutcome.Failure -> {
                System.err.println("Pipeline finished with FAILURE")
                reportRunFailure(runOutcome.failure)
                true
            }
            is RunOutcome.Aborted -> {
                System.err.println("Pipeline finished with FAILURE")
                System.err.println("  cause [ABORTED]: the run was cancelled or interrupted before a terminal outcome")
                true
            }
        }
    } else {
        // The LEGACY path: no typed outcome was produced, so there are only two stories —
        // "it finished successfully" and "it did not". The "unstable" arm that used to live
        // here was UNREACHABLE: the word it compared against could only be those two, so the
        // arm could never run and only misled whoever read it.
        val succeeded = lastEvent is RunFinished && lastEvent.outcome == RunOutcome.SUCCESS_TOKEN
        if (succeeded) {
            System.err.println("Pipeline finished with SUCCESS"); false
        } else {
            System.err.println("Pipeline finished with FAILURE"); true
        }
    }
