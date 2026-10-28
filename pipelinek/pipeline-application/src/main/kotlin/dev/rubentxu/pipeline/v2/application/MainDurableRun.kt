package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.SagaCompensationBinding
import dev.rubentxu.pipeline.v2.application.durable.SagaVerdict
import dev.rubentxu.pipeline.v2.application.durable.analyzeCanonicalDurableExecution
import dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
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
/**
 * The per-run state bundle the canonical coordinator runs with: the
 * authorities this run owns, plus the wake signal its live observer parks
 * on and the shell path announces to.
 */
private fun durableRunState(
    journal: OperationJournal,
    cursorStore: ReplayCursorStore,
    eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
    sagaObligationStore: SqliteSagaObligationStore,
    lease: DurableLease,
    outputWakeups: dev.rubentxu.pipeline.v2.application.observation.WakeupSignal,
): CanonicalRunState = CanonicalRunState(
    outputWakeups = outputWakeups,
    journal = journal,
    cursorStore = cursorStore,
    eventSink = eventSink,
    // The run's Saga seam: the durable store plus the lease epoch that
    // fences a writer that lost this run to a takeover.
    sagaBinding = SagaCompensationBinding(sagaObligationStore, lease.leaseEpoch),
)

/**
 * A durable run, and the exit code it leaves behind.
 *
 * ## WHY THE SCOPE OPENS AT THE FIRST ACQUISITION
 * The run acquires its event store and its lease before it does anything else, and it owns both
 * from that moment. A refusal that ends the process in that window leaves the lease record
 * claiming an owner that is gone, and the store's writer thread open: the next reader is told
 * that the run is still being published by a process that no longer exists. The scope therefore
 * opens with the first acquisition, closes on every path, and the refusals travel as values so
 * that they can unwind through it.
 */
internal fun runDurablePipeline(
    config: CliFlags,
    dbPath: String,
    secretPatternRegistry: SecretPatternRegistry,
    compiledQuery: CompiledObservationQuery,
    scriptPath: Path,
): Int =
    try {
        ResourceScope().use { scope ->
            runDurablePipelineOwned(scope, config, dbPath, secretPatternRegistry, compiledQuery, scriptPath)
        }
    } catch (refused: CommandRefused) {
        refused.exitCode
    }

/**
 * The body of a durable run, with the scope that owns what the run acquires.
 */
private fun runDurablePipelineOwned(
    scope: ResourceScope,
    config: CliFlags,
    dbPath: String,
    secretPatternRegistry: SecretPatternRegistry,
    compiledQuery: CompiledObservationQuery,
    scriptPath: Path,
): Int {
    // Durable mode: the run's events are kept in a SQLite journal. The narration is composed by
    // the SAME authority the volatile world asks, so the two worlds differ in WHERE the events
    // are kept and in nothing else: a run tells its story the same way with a database as
    // without one.
    val store = admitDurableEventStore(dbPath)
    scope.own { store.rawEventStore.close() }
    val rawEventStore = store.rawEventStore
    val eventStore = composeNarratingEventStore(
        raw = rawEventStore,
        policy = NarrationPolicy(config.format, config.view, compiledQuery),
        secretPatternRegistry = secretPatternRegistry,
    )

    // The script-derived hash is the DefinitionId. Durable policy decides
    // whether its persisted RunId is reused or intentionally replaced.
    val definition = readDefinitionSource(scriptPath)
    val controlDirRoot = resolveDurableControlRoot(config, dbPath)
    // the canonical engine threads `workspaceBase` through
    // `CanonicalRuntimeContext.workspaceBase`; the capability bridge
    // populates `WORKSPACE_IDENTITY_CAPABILITY` from
    // `context.shOptions.workspaceRoot`. Plugins consume the typed
    // seam via `StepHandlerContext.capabilities.get<WorkspaceIdentity>(...)`
    // instead of reading a process-global property. The system-property
    // bridge has been removed: no production code reads
    // `pipeline.workspace.root` any more (the historical writers were
    // F5.1 SCM/Git and F5.2 JUnit; F5.2 JUnit migrated to the typed
    // capability here; F5.1 SCM/Git migration is a sibling follow-up.
    val ownership = acquireDurableOwnership(
        scope = scope,
        config = config,
        controlDirRoot = controlDirRoot,
        eventStore = eventStore,
        definitionId = definition.definitionId,
    )
    val runId = ownership.runId
    val lease = ownership.lease
    val source = compileDurableScript(
        eventStore = eventStore,
        runId = runId,
        scriptPath = scriptPath,
        scriptContent = definition.content,
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
    // SAGA / durable run: the durable Saga obligations live in the SAME database as the journal and
    // the event log, through the same connection factory. One authority for the run's durable
    // state, not a second store with its own lifecycle.
    val sagaObligationStore = SqliteSagaObligationStore(
        connectionFactory = store.factory,
        dbPath = store.dbPathStr,
    )
    val cursorStore: ReplayCursorStore = SqliteReplayCursorStoreImpl(store.factory, clock)
    val effectPolicy: EffectReplayPolicy = DefaultEffectReplayPolicy()
    // The need for the secret store is a fact about this RUN, and the run knows
    // it: the compiled script says, in its own declaration, whether it binds a
    // credential.
    val credentials = composeCredentialSeam(secretPatternRegistry, clock, compilation.pipelineSpec)

    // The legacy PipelineOrchestrator was constructed here but
    // never invoked. The CLI reaches the durable runtime ONLY through the canonical
    // RunCoordinator (fail-closed exit 2 otherwise). The legacy orchestrator class has been deleted.
    // compose the registry BEFORE the canonical-eligibility gate —
    // eligibility is registry-derived, so external plugin contributions must be
    // visible to the gate or a contributed key would be wrongly rejected as
    // non-canonical. Same composed registry is handed to the coordinator below.
    // same single entry point as the in-memory branch above. Writing the admission
    // wiring out a second time here would let the two branches disagree about which plugins were
    // admitted, which is precisely the defect S6/G removed once already.
    // The refusal travels as a value: the scope still holds the lease and the
    // store, and it gives them back before the process ends.
    val composition = when (val admission = admitPluginsForRun(config, controlDirRoot)) {
        is PluginAdmission.Admitted -> admission.composition
        is PluginAdmission.Refused -> throw CommandRefused(admission.exitCode)
    }
    val composedStepRegistry = composition.steps
    // the workspace decision rule + the typed owner decision: the workspace origin is decided
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
    val outputObserver = startLiveOutputObserver(runId, controlDirRoot, config.format)
    // The sqlite single-writer thread is NON-daemon: if an exception escapes, the writer keeps
    // blocking on queue.take() and the JVM hangs. The outcome is computed inside the try and
    // the close is guaranteed in the finally, on every path.
    val runOutcomeAndLastEvent: Pair<RunOutcome?, DomainEvent?> = try {
        val outcome: RunOutcome? = when {
            // the scripted FRONTEND form runs against the SAME durable
            // authority (journal, registry, event sink, control root) composed for the
            // canonical path. This is a frontend selection, never a second runner: the
            // invocation authority is the registry seam established through the durable prove-out.
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
                outputWakeups = outputObserver.wakeups,
            )
            compiledPipeline != null -> {
                // The SAME analysis the volatile world runs, and the SAME refusal.
                // The reasons are NOT discarded: reducing the analysis to a boolean
                // is what left the operator with a rule violation and no way to
                // locate it (see refuseNonCanonicalExecution).
                val nonCanonicalSteps = compiledPipeline.analyzeCanonicalDurableExecution(composedStepRegistry)
                if (nonCanonicalSteps.isEmpty()) {
                    runCanonical(
                        CanonicalRunRequest(
                            pipeline = compiledPipeline,
                            runId = RunId(runId),
                            state = durableRunState(journal, cursorStore, eventStore, sagaObligationStore, lease, outputObserver.wakeups),
                            policy = CanonicalRunPolicy(
                                clock = clock,
                                effectReplayPolicy = effectPolicy,
                                controlDirRoot = controlDirRoot,
                                sandboxProfile = config.sandboxProfile,
                                // the workspace decision rule: the CLI default is local-first. The
                                // workspace origin is decided once, here at the boundary, and BOTH
                                // facts cross into the runtime: the shared directory and its owner.
                                // `--isolated` preserves the historical PipelineK-managed scratch;
                                // `--workspace <path>` stays authoritative.
                                workspace = workspaceTransport,
                                // the CAPABILITY is offered on every path — the coordinator always
                                // contributes `credentials.basic`, and a run with no store is backed by a
                                // source that refuses every lookup with `NotConfigured`. What this path
                                // adds is the PROVIDER, because this world opened the store the operator
                                // configured. The volatile world offers the same capability with no
                                // provider, and that is the honest answer for a run with nowhere to look.
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
                    refuseNonCanonicalExecution(nonCanonicalSteps)
                }
            }
            else -> {
                // No pipeline to run: either the script did not compile, or it
                // compiled and produced no PipelineSpec. The SAME answer the
                // volatile world gives, from the SAME function, so the two
                // worlds cannot read one fact and report two different reasons
                // (see absentPipelineOutcome).
                absentPipelineOutcome(compilation)
            }
        }

        // durable pipeline work + durable subscriber regression fix: the single-writer thread
        // is non-daemon and processes events asynchronously. We must FLUSH the pending
        // writes BEFORE reading the events back from SQL, otherwise async events emitted
        // just before run-finished (e.g. StashCreated/StashRestored from a final stage)
        // are lost from the stdout JSON envelope (the SQL row exists but hasn't been
        // committed when eventsFor(runId) runs). The scope closes the store when the run
        // returns, which stops the writer and releases the persistent connection; the JVM
        // exits cleanly because DestroyJavaVM no longer waits on queue.take().
        rawEventStore.flush()
        val lastEventCaptured = publishDurableObservations(
            eventStore = eventStore,
            runId = runId,
            sagaTerminals = sagaTerminals,
            view = config.view,
            format = config.format,
            compiledQuery = compiledQuery,
        )
        Pair(outcome, lastEventCaptured)
    } finally {
        // The observer is the last thing this run acquires and the first it
        // gives back. The lease and the event store belong to the scope, which
        // closes after this: they are given back on the refusal paths too, and
        // a run that never reaches this block still gives them back.
        runCatching { outputObserver.close() }
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
    // The exit code is a value the caller owns: only the caller ends the
    // process, and it does so after the scope has given back what the run
    // acquired.
    return if (exitFailure) 1 else 0
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
 * The end of a durable run: what reaches stdout, and the two facts the verdict is made of.
 *
 * ## Why the human story is not told twice
 * The console was told the story while the run was happening, because the narration is
 * composed by the same authority the volatile world asks. Printing the same events again
 * here would repeat it, and the repetition would arrive after the process output it exists
 * to explain — a transcript that reads as if the run had happened twice.
 *
 * The machine formats are the opposite case: a JSON document cannot be streamed, so it is
 * written once, at the end, from the journal.
 *
 * ## Why the journal is read back at all
 * The verdict of a run is decided by its last event, and the Saga verdict by the Saga
 * terminals among them. Both live in the journal, so both are read from it — once, here,
 * where the decision they feed is made.
 */
private fun publishDurableObservations(
    eventStore: dev.rubentxu.pipeline.v2.events.EventSink,
    runId: String,
    sagaTerminals: MutableList<SagaTerminal>,
    view: ObservationView,
    format: ObservationFormat,
    compiledQuery: CompiledObservationQuery,
): DomainEvent? {
    var lastEventCaptured: DomainEvent? = null
    val observed = eventStore.eventsFor(runId).onEach { event ->
        lastEventCaptured = event
        if (event is SagaTerminal) sagaTerminals += event
    }
    if (format == ObservationFormat.TEXT) {
        // The story was told live; this walk only recovers the facts the verdict needs. It
        // walks WITHOUT keeping: a transcript too large to hold in memory must not become a
        // list just to be discarded, which is the debt the durable path once paid.
        val iterator = observed.iterator()
        while (iterator.hasNext()) {
            iterator.next()
        }
        return lastEventCaptured
    }
    // The JSON envelope goes out one event at a time (identical byte output to
    // JsonEventLog.encode) and only the last event is tracked for the outcome branch — never
    // the full list, never a single monolithic JSON String (1 GiB transcripts made this
    // multi-GB).
    val stdout = System.out
    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(stdout, Charsets.UTF_8), 1 shl 16)
    RunObservationOutput.writeTo(observed, writer, view, format, compiledQuery)
    writer.flush()
    return lastEventCaptured
}

/**
 * The identity a durable run publishes: which run it is and which correlation
 * it answers to.
 *
 * ## WHY THE LEASE IS NOT PART OF THE IDENTITY
 * The lease is taken AFTER the run is selected and BEFORE the correlation is
 * emitted, because the owner in the receipt has to be the owner that holds the
 * run. Keeping the three steps apart is what lets the caller hand the lease to
 * the scope that owns it before anything else can fail.
 */
private data class DurableRunIdentity(
    val runId: String,
    val correlationId: CorrelationId,
)

private fun selectDurableRunIdentity(
    config: CliFlags,
    controlDirRoot: Path,
    definitionId: dev.rubentxu.pipeline.v2.domain.DefinitionId,
): DurableRunIdentity {
    val runIdDirectory = RunIdDirectory(controlDirRoot.resolve("last-run"))
    // build the correlationId once here at
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
        // --resume without a prior durable run is an
        // invocation/admission error, not a pipeline failure. Typed
        // rejection, no stack trace, exit 2 per the CLI exit contract.
        System.err.println("Error: ${e.message}")
        throw CommandRefused(exitCode = 2)
    }
    return DurableRunIdentity(runId = runSelection.runId.value, correlationId = pk1Correlation)
}

/**
 * The stores of a durable run, admitted once.
 *
 * D3 — a run CREATES its event store, so the file is legitimately absent. That is why the purpose
 * is part of the admission and not a flag at each site: `run` used to reach the same constructor
 * as the read verbs and inherited neither the refusal nor the exit code, so a `--db` under a
 * missing directory escaped as a raw SQLException with exit 1 — the code this CLI reserves for a
 * pipeline that ran and failed.
 *
 * The run is admitted as [EventStorePurpose.Produce] because it is also the owner of the place it
 * writes into: a run creates the control root it works in, and the directory that holds its store
 * is the same kind of thing. A verb that writes into a run that already happened keeps the other
 * lane, where the place has to be there already.
 */
private data class DurableEventStores(
    val rawEventStore: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
    val factory: () -> java.sql.Connection,
    val dbPathStr: String,
)

private fun admitDurableEventStore(dbPath: String): DurableEventStores {
    val dbAdmission = admitEventStore(dbPath, EventStorePurpose.Produce)
    if (dbAdmission is EventStoreAdmission.Refused) {
        System.err.println(dbAdmission.refusal.render())
        // The refusal is a value the caller turns into an exit code: the run
        // may already own a lease and an event store, and the process must not
        // end while it holds them.
        throw CommandRefused(exitCode = 2)
    }
    val rawEventStore = (dbAdmission as EventStoreAdmission.Admitted).store
    // The raw methods are read before anything wraps the store: the connection factory and the
    // database path belong to the store itself, not to the decorators that sit on top of it.
    val factory = rawEventStore.underlyingConnectionFactory()
    val dbPathStr = rawEventStore.databasePath()
    return DurableEventStores(rawEventStore, factory, dbPathStr)
}

/**
 * Where a durable run writes its control state.
 *
 * controlDirRoot is the parent directory of the SQLite db file (default).
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
            throw CommandRefused(exitCode = 2)
        }
    } else {
        // The store's directory, made absolute. A relative `--db` has no parent component, and
        // the working directory is the directory that holds it; asking the path for its parent
        // without that step is a null dereference waiting for the first relative store path.
        val storeDirectory = Paths.get(dbPath).toAbsolutePath().parent
        if (storeDirectory == null) {
            System.err.println("Error: the store path has no directory to hold it: ${dbPath}")
            throw CommandRefused(exitCode = 2)
        }
        storeDirectory.resolve("durable-shell")
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
    // first-class run ownership. Before any event is written for this
    // run we must prove we are the only process writing to it. Without this,
    // two concurrent `--resume` invocations of the same definition both proceed
    // and collide on the durable `ux_events_run_sequence` uniqueness index, so
    // the loser sees a raw SQLite constraint violation instead of a typed
    // refusal. The lease turns that crash into an explicit admission decision.
    val runOwner = RunOwnerId.of("cli-" + ProcessHandle.current().pid() + "-" +
        java.util.UUID.randomUUID().toString().take(8))
    val leaseStore = FileBackedRunExecutionLeaseStore(controlDirRoot.resolve("leases"))
    val lease = leaseStore.acquire(LeaseRequest(runId, requireNotNull(runOwner)))
    // SAGA / durable run: the fencing token of the lease THIS process holds. It is the epoch the
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
            throw CommandRefused(exitCode = 2)
        }
        is LeaseAcquisition.Unverifiable -> {
            // Fail closed: a process that cannot prove it is the authority must
            // not publish. Kept distinct from AlreadyOwned because the
            // operator response differs.
            runCatching { leaseStore.close() }
            System.err.println("Error: cannot establish ownership of run $runId: ${lease.reason}")
            throw CommandRefused(exitCode = 2)
        }
    }
    return DurableLease(runOwner = runOwner, leaseStore = leaseStore, leaseEpoch = leaseEpoch)
}

/**
 * The ownership a durable run takes on itself: which run it is, and the lease that says this
 * process is the one publishing it.
 */
private data class DurableOwnership(
    val runId: String,
    val lease: DurableLease,
)

/**
 * Become the owner of the run, and say so.
 *
 * ## WHY THE THREE STEPS ARE ONE
 * The run is selected, the lease is taken and the receipt is published together, because the
 * receipt names the lease's owner: a receipt published before the lease existed would name an
 * owner the run does not have, and a receipt published without the run selected would name a run
 * nobody can find.
 *
 * ## WHY THE SCOPE OWNS THE LEASE FROM HERE
 * The lease is the run's ownership of itself, and the scope owns it from the moment it is
 * acquired: it is given back on every path, including the refusals that unwind through the
 * caller. The record the lease leaves behind is what the next reader believes, so it has to say
 * that the run is over.
 */
private fun acquireDurableOwnership(
    scope: ResourceScope,
    config: CliFlags,
    controlDirRoot: Path,
    eventStore: dev.rubentxu.pipeline.v2.events.EventSink,
    definitionId: dev.rubentxu.pipeline.v2.domain.DefinitionId,
): DurableOwnership {
    val identity = selectDurableRunIdentity(
        config = config,
        controlDirRoot = controlDirRoot,
        definitionId = definitionId,
    )
    val runId = identity.runId
    val lease = acquireDurableLease(controlDirRoot, runId)
    scope.own {
        runCatching { lease.leaseStore.release(requireNotNull(lease.runOwner)) }
        lease.leaseStore.close()
    }
    // lease is held; emit the RunCorrelated
    // event on the durable event sink and the LaunchReceipt on stdout.
    // The owner is the lease-acquired runOwner.
    // the durable branch's correlation is the
    // one persisted alongside the runId; fall back to the CLI-bound
    // token if the durable record has none.
    emitCorrelationAndReceipt(
        eventStore = eventStore,
        runId = RunId(runId),
        correlationId = identity.correlationId,
        ownerId = requireNotNull(lease.runOwner),
        startedAtMs = System.currentTimeMillis(),
        controlDir = controlDirRoot,
        format = config.format,
    )
    return DurableOwnership(runId = runId, lease = lease)
}

/**
 * The scripted frontend of a durable run, when the source is generator-level.
 *
 * frontend FORM selection. The compiler-backed mapping decides
 * whether this source contains runtime-effectful scripted calls; the CLI only
 * picks the closed artifact FORM, never an execution authority and never a
 * concrete Step (the CLI may choose the frontend, never the backend).
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
            // The R3 lowering emits a generator-level entry-point body only. A
            // `pipeline { stages { stage { ... } } }` structure keeps the eager
            // PipelineSpec frontend as-is (no DSL-body migration). Frontend FORM
            // selection, never an execution-authority switch.
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
                        throw CommandRefused(exitCode = 2)
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
                    throw CommandRefused(exitCode = 2)
                }
                else -> when (val out = (scriptedResult as dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult.Success).output) {
                    is dev.rubentxu.pipeline.v2.scripting.ScriptEvaluationOutput.CompiledEntryPoint -> out.entryPoint
                    else -> {
                        System.err.println("Error: scripted frontend did not produce a compiled entry point")
                        throw CommandRefused(exitCode = 2)
                    }
                }
            }
        }
    return ScriptedFrontend(artifact = artifact, entryPoint = entryPoint)
}

/**
 * says WHY the run failed, on the stream an operator is already reading.
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
/**
 * The exit decision of a durable run.
 *
 * When the coordinator ran, the typed outcome is the single authority for the
 * exit decision; the legacy event-based branch only covers compile-failure
 * paths where no definition was produced.
 */
private fun durableRunFailed(runOutcome: RunOutcome?, lastEvent: DomainEvent?): Boolean =
    if (runOutcome != null) {
        reportRunOutcome(runOutcome)
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
