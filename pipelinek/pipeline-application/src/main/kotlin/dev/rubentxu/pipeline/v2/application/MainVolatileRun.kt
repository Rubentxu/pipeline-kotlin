package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.SagaVerdict
import dev.rubentxu.pipeline.v2.application.durable.analyzeCanonicalDurableExecution
import dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.ConsolePrintingEventSink
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import java.nio.file.Files
import java.nio.file.Path
import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.LiveOutputDrain
import dev.rubentxu.pipeline.v2.application.observation.LiveOutputPresentation

/**
 * The volatile world: a run without `--db`.
 *
 * ## Why this is a world and not a mode
 *
 * the single-runtime-spine storage rule (single runtime spine): the storage choice must not select a
 * different execution algorithm. This branch runs the SAME canonical
 * coordinator as the durable one, with volatile stores; nothing survives the
 * process. The differences live in the wiring below and not in the run.
 *
 * ## Where the two worlds are NOT the same
 *
 * The law is about the ALGORITHM, and the algorithm is shared: both worlds
 * reach [runCanonical] and both bind the same coordinator. Two facts of the
 * request differ on purpose — the credential provider (the pipeline-managed workspace owner: a run with nowhere
 * to look answers `NotConfigured`) and the control root (necessarily, because
 * the two worlds keep their control state in different places).
 *
 * And one route exists ONLY in the durable world: the scripted-frontend form
 * (`runScriptedFrontend`), which the durable branch resolves and this one does
 * not. That asymmetry predates this file; it is written down here because a
 * KDoc that says "the same in both worlds" when it is not is worse than no
 * KDoc at all.
 *
 * ## What is decided here and not in [main]
 *
 * The run id, the correlation id, the owner, the control root and the stores
 * are decided at the CLI boundary, once, and handed to the runtime as facts.
 * A runtime that re-derives them is a runtime that can disagree with the
 * record it is writing.
 */
internal fun runVolatilePipeline(
    config: CliFlags,
    secretPatternRegistry: SecretPatternRegistry,
    compiledQuery: CompiledObservationQuery,
    scriptPath: Path,
    outputProtocol: OutputProtocol,
) {
    // the single-runtime-spine storage rule (single runtime spine): storage choice must not select a
    // different execution algorithm. Without --db the run uses the
    // canonical durable coordinator with volatile (in-memory) stores;
    // nothing survives the process.
    // Durable run selection flags are rejected without persistent state.
    if (config.durableRunPolicy != DurableRunPolicy.ReusePriorRun) {
        System.err.println("Error: --resume and --rerun require --db (no durable state exists without a journal database)")
        System.exit(2)
        return
    }
    val eventStore = composeVolatileEventStore(config, secretPatternRegistry, compiledQuery)
    val scriptContent = scriptPath.toFile().readText()
    val definitionId = dev.rubentxu.pipeline.v2.domain.DeterministicIdGenerator.definitionId(
        scriptPath.toString(),
        scriptContent,
    )
    val controlDirRoot = resolveVolatileControlRoot(config)
    val identity = establishVolatileIdentity(
        config = config,
        eventStore = eventStore,
        controlDirRoot = controlDirRoot,
        definitionId = definitionId,
    )
    val runId: String = identity.runId
    val compilation = compileScriptForRun(
        eventStore = eventStore,
        runId = runId,
        scriptPath = scriptPath,
        pluginJars = config.pluginJars,
    )

    val clock: dev.rubentxu.pipeline.v2.domain.durable.Clock = SystemClock()
    val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal =
        InMemoryOperationJournal(clock)
    val cursorStore: dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore =
        InMemoryReplayCursorStore(clock)

    val compiledPipeline = compilation.pipelineSpec?.let { spec ->
        compileOrRefuse(
            spec = spec,
            sourcePath = scriptPath.toString(),
            sourceContent = scriptContent,
        )
    }

    // compose registry BEFORE the gate so contributed keys are eligible.
    // admission happens FIRST and inside the same function, so the in-memory and
    // durable branches cannot admit differently. S6/G's "one resolution for the whole run" now
    // also covers the decision about WHICH artifacts are allowed to resolve at all.
    val composition = admitThenComposeOrExit(config.pluginJars)
    val composedStepRegistry = composition.steps
    val nonCanonicalSteps = compiledPipeline
        ?.analyzeCanonicalDurableExecution(composedStepRegistry).orEmpty()
    // the default `pipeline run <script>` (in-memory) branch composes
    // the SAME credential injection stack as the durable branch, through the SAME
    // function. The default path is the temp in-memory control dir's sibling;
    // PIPELINE_CREDENTIALS_STORE env overrides, exactly like the durable path.
    //
    // The PROVIDER this seam also opens is deliberately NOT handed to the run
    // (the pipeline-managed workspace owner, below): a run with no store has nowhere to look, and a run that
    // does have one does not need the provider on the in-memory path.
    val credentialSeam = composeCredentialSeam(secretPatternRegistry, clock)
    // the workspace decision rule / the workspace origin rule clause 3.1 + the typed-owner decision: the workspace origin is decided
    // ONCE, here at the boundary. Both facts cross into the runtime — the shared
    // directory and its owner — because a runtime that receives only the root
    // re-derives ownership and lets `deleteDir` erase a user's project under the
    // local-first default. `--isolated` keeps the historical PipelineK scratch;
    // `--workspace <path>` stays authoritative.
    val workspaceTransport = WorkspaceIntent.resolveRuntimeTransport(
        config, Path.of("").toAbsolutePath().normalize(), controlDirRoot,
    )
    // the process output of a step lives in the Output Plane, not on the event spine,
    // so the event sink above never saw it. This observer follows the plane while the run is in
    // flight, which is what makes a build's progress visible while the build runs. It is
    // started BEFORE the pipeline (there is nothing to observe until a step produces bytes) and
    // closed after it returns, so the last committed bytes are flushed.
    val outputObserver = startLiveOutputObserver(runId, controlDirRoot, config.format)

    val runOutcome: RunOutcome? = try {
        val pipeline = compiledPipeline
        if (compilation.failure != null) {
            // Compilation must be checked FIRST. If the script failed to compile there is no
            // compiled pipeline to run; jumping to the canonical run would NPE on `!!`. This
            // regression was introduced when the per-step canonical gate was added (v0.33.1 P2
            // corpus-closure) but the compile-failure branch was left inside `else -> compileOutcome`
            // which only fires when nonCanonicalSteps is non-empty OR pipelineSpec is non-null.
            compilation.failure
        } else if (pipeline == null) {
            // compile "succeeded" but produced no PipelineSpec. Before this
            // branch this reached the canonical run with a `!!` and died as an NPE
            // with exit 0. A script that cannot produce its IR is an admission failure.
            RunOutcome.Failure(
                dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                    message = "the script compiled but did not produce a PipelineSpec; " +
                        "its body failed during construction and no pipeline can run",
                ),
            )
        } else if (nonCanonicalSteps.isEmpty()) {
            runCanonical(
                CanonicalRunRequest(
                    pipeline = pipeline,
                    runId = RunId(runId),
                    state = CanonicalRunState(
                        journal = journal,
                        cursorStore = cursorStore,
                        eventSink = eventStore,
                        // P6: the Saga seam does NOT depend on `--db`. This branch binds the
                        // VOLATILE store, so the capabilities are the same ones the durable
                        // branch exposes and the SAME script behaves the same in both worlds.
                        // What changes is the DURABILITY of the promise, not the algorithm:
                        // nothing survives the process here, exactly like the journal and the
                        // event log of this branch.
                        //
                        // Before this binding, this branch bound NO saga store at all, so the
                        // Saga capabilities were never exposed and a script that declares them
                        // was refused at admission — the same script behaving differently with
                        // and without `--db`, which is the contradiction the single-runtime-spine storage rule forbids.
                        //
                        // The epoch is 0 because a volatile run has no lease: there is no
                        // takeover to fence against, and one writer owns the run for as long as
                        // the process lives.
                        sagaBinding = dev.rubentxu.pipeline.v2.application.durable.SagaCompensationBinding(
                            store = InMemorySagaObligationStore(),
                            leaseEpoch = 0,
                        ),
                    ),
                    policy = CanonicalRunPolicy(
                        clock = clock,
                        effectReplayPolicy = DefaultEffectReplayPolicy(),
                        controlDirRoot = controlDirRoot,
                        sandboxProfile = config.sandboxProfile,
                        // the workspace decision rule / the workspace origin rule clause 3.1: the CLI default is local-first. The
                        // workspace origin is decided once, here at the boundary, and BOTH
                        // facts cross into the runtime: the shared directory and its owner.
                        // `--isolated` preserves the historical PipelineK-managed scratch;
                        // `--workspace <path>` stays authoritative.
                        workspace = workspaceTransport,
                        // NO credentialProvider on this path, deliberately. It composes
                        // with an in-memory journal and receives no store, so the seam takes
                        // its default: a source that refuses every lookup with
                        // `NotConfigured`. That is the honest answer for a run that has
                        // nowhere to look, and it is the same answer the durable path gives
                        // when the operator configured no store. Passing a provider here
                        // would mean opening a secret store for a path that has no use for
                        // one.
                        credentialProvider = null,
                        withCredentialsExecutor = credentialSeam.executor,
                        // this path composes its own coordinator and had the same
                        // omission as the durable one. `--allow-network` is a per-RUN
                        // permission; a run whose flag is ignored is a run where the
                        // operator believes they opened the network and did not.
                        allowNetwork = config.allowNetwork,
                        secretPatternRegistry = secretPatternRegistry,
                    ),
                    // S6/G: the frozen composition, not a step registry plus a classloader.
                    // There is no path here that can reach a registry without a directive registry
                    // having been resolved under the same loader.
                    composition = composition,
                ),
            )
        } else {
            // Fail-closed: non-canonical pipelines are not supported by the canonical bridge.
            // Same gate and message as the durable path (STEP SEMANTICS: fail-closed on every run path).
            System.err.println(NON_CANONICAL_CANONICAL_BRIDGE_ERROR)
            for (step in nonCanonicalSteps) {
                System.err.println("  - Step '${step.stepId}' (${step.pluginStepId}): ${step.reason}")
            }
            System.exit(2)
            null // unreachable
        }
    } finally {
        // The observer stops here, not before: everything the pipeline committed is already in
        // the plane, and stopping first would drop the final frames of the last step. `finally`
        // rather than a plain call so a throwing pipeline still releases the thread.
        outputObserver.close()
    }

    val events = eventStore.eventsFor(runId).toList()
    // human text was already streamed live by ConsolePrintingEventSink.
    // Only the machine formats still need a document at the end.
    if (config.format != ObservationFormat.TEXT) {
        writeEventDocument(
            events = events,
            view = config.view,
            format = config.format,
            query = compiledQuery,
            protocol = outputProtocol,
        )
    }
    val exitFailure = volatileRunFailed(runOutcome, events)
    // P6: the Saga verdict travels in BOTH worlds. A Saga can now run without
    // `--db` —the volatile store is bound exactly like the durable one—, so the
    // run has to SAY what happened to it, exactly as the durable path says. A
    // Saga that settled without saying so is a Saga the operator cannot see,
    // and "the run was green" is not a substitute for "the Saga was settled".
    SagaVerdict.verdicts(events.filterIsInstance<SagaTerminal>()).forEach { System.err.println(it) }
    if (exitFailure) System.exit(1)
}

/**
 * The identity a volatile run publishes: which run it is, which correlation it
 * answers to, and who owns it.
 *
 * The owner here is SYNTHETIC — a CLI pid plus a uuid — because a volatile run
 * has no lease: there is no takeover to fence against, and one writer owns the
 * run for as long as the process lives.
 */
private data class VolatileRunIdentity(
    val runId: String,
    val correlationId: CorrelationId,
    val ownerId: RunOwnerId,
)

private fun establishVolatileIdentity(
    config: CliFlags,
    eventStore: dev.rubentxu.pipeline.v2.events.EventSink,
    controlDirRoot: Path,
    definitionId: dev.rubentxu.pipeline.v2.domain.DefinitionId,
): VolatileRunIdentity {
    val runIdDirectory = RunIdDirectory(controlDirRoot.resolve("last-run"))
    val fresh = UuidRunIdGenerator().next()
    // build the correlationId once here at
    // the CLI boundary so both the durable last-run record and the
    // in-memory RunCorrelated event share the same token.
    val correlationId: CorrelationId = config.correlationId?.let { CorrelationId(it) }
        ?: UuidCorrelationIdGenerator().next()
    runIdDirectory.record(definitionId, fresh, correlationId)
    val runId: String = fresh.value
    // emit the RunCorrelated event on the
    // event sink so a consumer can join durable state to the
    // external correlationId. We emit after the rest of the run is wired
    // (so the event is observed in the same order as it would be in the
    // durable branch: lease -> RunCorrelated -> RunStarted).
    // We also emit the LaunchReceipt on stdout for the consumer.
    // For the in-memory branch the owner is a synthetic CLI pid +
    // uuid; for the durable branch the lease-acquired owner is
    // used (see below).
    val syntheticOwner: RunOwnerId = requireNotNull(
        RunOwnerId.of(
            "cli-inmem-" + ProcessHandle.current().pid() + "-" +
                java.util.UUID.randomUUID().toString().take(8),
        ),
    )
    // emit the RunCorrelated event and
    // the LaunchReceipt. The receipt is durable machine-readable
    // metadata, not a human message; in TEXT view it goes to
    // stderr so the human console is not polluted. In JSON/JSONL
    // view it is the first stdout line so a machine consumer can
    // join the receipt to the event stream by line index.
    emitCorrelationAndReceipt(
        eventStore = eventStore,
        runId = RunId(runId),
        correlationId = correlationId,
        ownerId = syntheticOwner,
        startedAtMs = System.currentTimeMillis(),
        controlDir = controlDirRoot,
        format = config.format,
    )
    return VolatileRunIdentity(runId = runId, correlationId = correlationId, ownerId = syntheticOwner)
}

/**
 * The event store of a volatile run: in memory, and mirrored live in TEXT.
 *
 * in TEXT the run is mirrored live as events are appended, so
 * the end-of-run dump is suppressed in the caller. Machine formats keep stdout
 * untouched and are written once, at the end, exactly as before.
 */
private fun composeVolatileEventStore(
    config: CliFlags,
    secretPatternRegistry: SecretPatternRegistry,
    compiledQuery: CompiledObservationQuery,
): EventSink {
    val rawEventStore = InMemoryEventStore()
    val redactedStore = RedactingEventSink(rawEventStore, secretPatternRegistry)
    return if (config.format == ObservationFormat.TEXT) {
        // The query rides along because this is the only place a TEXT run is printed: the
        // end-of-run dump is suppressed below, so a filter honoured only there would be
        // honoured by nothing. It suppresses presentation only; the store still receives
        // every event.
        ConsolePrintingEventSink(redactedStore, config.view, compiledQuery) { line ->
            System.out.println(line)
        }
    } else {
        redactedStore
    }
}

/**
 * Where a volatile run writes its control state.
 *
 * `--control-root` is honoured HERE TOO, and it used not to be. The flag is parsed and
 * validated in the durable branch and was silently ignored in this one, which
 * hard-coded a fresh temp directory per run. That made the flag mean two different
 * things depending on whether `--db` was present, and it made the Output Plane
 * unreachable from the CLI on the DEFAULT path: `pipeline console --control-dir X`
 * could not be pointed at a run that had not been given a database.
 *
 * That is not a cosmetic inconsistency. M1 moved process output into the plane
 * (the addressed-output rule D2/D3), so "where the plane is written" IS the product question, and a flag
 * that answers it only on one of two execution paths is a flag that cannot be relied on
 * to observe the default one. The default is unchanged: with no `--control-root`, this
 * still creates a private temp directory.
 */
private fun resolveVolatileControlRoot(config: CliFlags): Path =
    if (config.controlRoot != null) {
        try {
            validateControlRoot(config.controlRoot)
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.exit(2)
            throw e // unreachable
        }
    } else {
        Files.createTempDirectory("pipelinek-inmem-run")
    }

/**
 * The exit decision of a volatile run.
 *
 * ## Why the volatile world renders a failure its own way
 *
 * The durable world prints the typed failure report after the verdict line; this one
 * prints the message on the verdict line itself, and the E2E scenario
 * `negative/15-error` pins that exact text. The two renderings are not the same fact
 * read twice: the FACT is the typed [RunOutcome], and both worlds read it. What differs
 * is how a failure is rendered for a human, and that rendering is part of what the
 * scenarios observe.
 */
private fun volatileRunFailed(runOutcome: RunOutcome?, events: List<dev.rubentxu.pipeline.v2.events.DomainEvent>): Boolean =
    if (runOutcome != null) {
        reportRunOutcome(runOutcome)
    } else {
        // The LEGACY path: no typed outcome was produced, so there are only two stories —
        // "it finished successfully" and "it did not". The "unstable" arm that used to live
        // here was UNREACHABLE: the word it compared against could only be those two, so the
        // arm could never run and only misled whoever read it.
        val lastEvent = events.lastOrNull()
        val succeeded = lastEvent is RunFinished && lastEvent.outcome == RunOutcome.SUCCESS_TOKEN
        if (succeeded) {
            System.err.println("Pipeline finished with SUCCESS"); false
        } else {
            System.err.println("Pipeline finished with FAILURE"); true
        }
    }
