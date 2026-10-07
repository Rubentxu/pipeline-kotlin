package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.FileBasedWaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.application.durable.NonCanonicalStep
import dev.rubentxu.pipeline.v2.application.durable.analyzeCanonicalDurableExecution
import dev.rubentxu.pipeline.v2.application.durable.credentials.WithCredentialsExecutorScopeAdapter
import dev.rubentxu.pipeline.v2.application.durable.supportsCanonicalDurableExecution
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.RunIdGenerator
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider
import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.credentials.multipart.LocalFileMaterialization
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.local.MainCredentialsCli
import dev.rubentxu.pipeline.v2.credentials.local.PassphraseResolver
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.ConsolePrintingEventSink
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import kotlinx.serialization.json.Json
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import dev.rubentxu.pipeline.v2.events.durable.SqliteReplayCursorStoreImpl
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.scripting.ScriptEvaluationOutput
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptedSource
import dev.rubentxu.pipeline.v2.scripting.isRuntimeReturning
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files

fun main(args: Array<String>) {
    // WU-LPR-011 F1: `version` is a real subcommand. Reports the CLI version
    // from the jar manifest (authoritative source = the build artifact) and exits 0.
    // WU-LPR-071: the version MUST come from the jar manifest populated by Gradle from
    // project.version (which is sourced from the git tag at release time). The legacy
    // "0.1.0-SNAPSHOT" sentinel is removed: if the manifest is missing the
    // Implementation-Version attribute, that is a packaging defect, NOT a fallback case.
    // Fail-closed: print an explicit error and exit non-zero so CI cannot ship an
    // unversioned artifact.
    if (args.firstOrNull() == "version") {
        val manifestVersion = object {}.javaClass.getPackage().implementationVersion
        if (manifestVersion.isNullOrBlank()) {
            System.err.println(
                "pipeline: FATAL — jar manifest is missing Implementation-Version. " +
                    "Refusing to report a version derived from a hard-coded sentinel. " +
                    "Rebuild via Gradle so the manifest is populated from project.version."
            )
            System.exit(3)
            return
        }
        println("pipeline $manifestVersion")
        System.exit(0)
        return
    }

    // WU-LPR-011 F1: `doctor` is a real subcommand. Local-only health
    // diagnostics: JDK version, working directory writability probe. Exits
    // 0 when the local runtime is healthy, 2 on an environment defect.
    if (args.firstOrNull() == "doctor") {
        // WU-LPR-071: route host-environment reads through the canonical
        // RuntimeConfig adapter (single authority); direct System.getProperty
        // here would be a second runtime-value authority (WULpr402 property 6).
        val runtimeConfig = dev.rubentxu.pipeline.v2.application.SystemRuntimeConfig()
        val checks = buildList {
            add("jdk: ${runtimeConfig.property("java.version")} (${runtimeConfig.property("java.vendor")})")
            add("os: ${runtimeConfig.osName()} ${runtimeConfig.property("os.version")}")
            val cwd = Paths.get("").toAbsolutePath()
            val writable = try {
                val probe = Files.createTempFile(cwd, "pipelinek-doctor-", ".probe")
                Files.deleteIfExists(probe)
                true
            } catch (_: java.io.IOException) {
                false
            }
            add("workdir: $cwd (${if (writable) "writable" else "NOT WRITABLE"})")
        }
        checks.forEach(::println)
        System.exit(if (checks.any { it.contains("NOT WRITABLE") }) 2 else 0)
        return
    }

    // Events subcommand (EVT-2): structured local history inspection
    if (args.firstOrNull() == "events") {
        if (args.getOrNull(1) == "verify") {
            val exitCode = MainEventsVerifyCli.main(args.drop(2).toTypedArray())
            System.exit(exitCode)
            return
        }
        val exitCode = MainEventsCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return
    }

    // Console subcommand (M1-P3): read process output from the Output Plane by OutputCursor.
    //
    // Deliberately a SEPARATE verb from `events`. The two planes are different orders and a
    // different authority: `events` continues on a store-assigned sequence, `console` continues on
    // a committed byte offset and never reads an event. Folding console into `events` — as a view,
    // or as a flag — is the conflation ADR-M1 D3 exists to prevent, and it is exactly what the
    // deprecated EventViewProjection.CONSOLE was.
    if (args.firstOrNull() == "console") {
        val exitCode = MainConsoleCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return
    }

    // Credentials subcommand — delegated to MainCredentialsCli
    if (args.firstOrNull() == "credentials") {
        val exitCode = MainCredentialsCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return
    }

    val config = when (val parsed = CliParser.parse(args)) {
        is CliParseResult.Parsed -> parsed.flags
        is CliParseResult.Rejected -> {
            System.err.println("Invalid CLI arguments: ${parsed.error}")
            System.err.println("Usage: pipeline <validate|run> [--db <path>] [--resume|--rerun] [--control-root <path>] <script>")
            System.exit(1)
            return
        }
    }

    val command = config.command

    // The parser already refused an unusable query (empty pattern, uncompilable
    // regex, `--grep-invert` with nothing to negate), so this is the
    // interpretation step and not a second validation. Compiling once here means
    // the query is built a single time for every output path below.
    val compiledQuery = (compileQuery(config.query) as SelectorCompileResult.Ok).value

    // Shared secret pattern registry for redaction (T6)
    // Both InMemoryEventStore and SqliteEventStore are wrapped at construction time
    // so all downstream consumers receive already-sanitized events.
    val secretPatternRegistry = SecretPatternRegistry()

    // CR-RD-008 canary: synthetic secret registered at engine startup for round-gate verification.
    // The canary value GHS6_CANARY_7f3a9c2e1b4d5e6f is never used in any real credential.
    secretPatternRegistry.addSecret(SecretHandle.plain("GHS6_CANARY_7f3a9c2e1b4d5e6f"))

    // CR-RD-021 ssh canary: synthetic secret for SSH channel round-gate verification.
    // The canary value __ssh_canary__ is never used in any real SSH credential.
    secretPatternRegistry.addSecret(SecretHandle.plain("__ssh_canary__"))

    // ARC-CANARY-001 / CR-RD-022 artefact canary: synthetic secret for artefact step round-gate.
    // The canary value __artefact_canary__ is never used in any real artefact.
    secretPatternRegistry.addSecret(SecretHandle.plain("__artefact_canary__"))

    val scriptPath = Paths.get(config.scriptPath)

    // DEBT-CLI-SCRIPT-NOT-FOUND: a missing/unreadable script used to reach
    // `scriptPath.toFile().readText()` deep in each mode and surface as a raw
    // `NoSuchFileException` stacktrace. Reject it here — at the CLI boundary, before
    // any store, journal or process is created — with a typed, actionable message.
    // Exit code 2 matches the other input/usage rejections (e.g. --control-root).
    if (!Files.isRegularFile(scriptPath) || !Files.isReadable(scriptPath)) {
        System.err.println(
            "Error: pipeline script not found or not readable: ${scriptPath.toAbsolutePath()}",
        )
        System.exit(2)
        return
    }

    if (command == CliCommand.VALIDATE) {
        // M2-002: validate NEVER starts processes. It compiles the script
        // and reports diagnostics — nothing else.
        val rawStore = InMemoryEventStore()
        val store = RedactingEventSink(rawStore, secretPatternRegistry)
        val scriptContent = scriptPath.toFile().readText()
        val validateRunId = java.util.UUID.randomUUID().toString()
        val host = Kotlin24ScriptingHost(store, validateRunId)
        val dslClasspath = computeScriptClasspath(config.pluginJars)
        val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
        // Inject the production RuntimeConfig so DSL `pwd()` / `isUnix()` synchronous
        // return values reflect the host environment during validation.
        dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.set(SystemRuntimeConfig())
        val compileResult = try {
            host.compile(definition)
        } finally {
            dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.clear()
        }
        val events = store.eventsFor(validateRunId).toList()
        print(RunObservationOutput.encode(events, config.view, config.format, compiledQuery))
        // TRAIN-DSL-HONESTY: a script that compiles but throws while building
        // its IR reaches here with isSuccess == true and no usable spec.
        // Reporting SUCCESS for it was the same DEFAULT_SUCCESS the run path
        // turned into an NPE.
        val evalFailure = compileResult.diagnostics.lastOrNull {
            it.severity == dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity.ERROR
        }?.message?.takeIf { it.isNotBlank() }
        val noSpec = compileResult.isSuccess &&
            compileResult.scriptInstance?.javaClass?.methods?.any { it.name == "get\$\$result" } != true
        if (!compileResult.isSuccess || evalFailure != null || noSpec) {
            // WU-LPR-011 F3: compile failure is an invocation/admission error,
            // not a pipeline execution failure. Exit 2 per the canonical
            // contract (0 success / 1 pipeline fail / 2 invocation+compile).
            if (evalFailure != null) System.err.println("VALIDATION FAILED: $evalFailure")
            else System.err.println("VALIDATION FAILED")
            System.exit(2)
        } else {
            System.err.println("VALIDATION SUCCESSFUL")
        }
        return
    }

    // "run" command.
    if (config.dbPath == null) {
        // LF-0208 (Single Runtime Spine): storage choice must not select a
        // different execution algorithm. Without --db the run uses the
        // canonical durable coordinator with volatile (in-memory) stores;
        // nothing survives the process.
        // Durable run selection flags are rejected without persistent state.
        if (config.durableRunPolicy != DurableRunPolicy.ReusePriorRun) {
            System.err.println("Error: --resume and --rerun require --db (no durable state exists without a journal database)")
            System.exit(2)
            return
        }
        val rawEventStore = InMemoryEventStore()
        val redactedStore = RedactingEventSink(rawEventStore, secretPatternRegistry)
        // ADR-0088: in TEXT the run is mirrored live as events are appended, so
        // the end-of-run dump is suppressed below. Machine formats keep stdout
        // untouched and are written once, at the end, exactly as before.
        val eventStore: dev.rubentxu.pipeline.v2.events.EventSink =
            if (config.format == ObservationFormat.TEXT) {
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

        val scriptContent = scriptPath.toFile().readText()
        val definitionId = dev.rubentxu.pipeline.v2.domain.DeterministicIdGenerator.definitionId(
            scriptPath.toString(),
            scriptContent,
        )
        // `--control-root` is honoured HERE TOO, and it used not to be. The flag is parsed and
        // validated in the durable branch (ML-R1) and was silently ignored in this one, which
        // hard-coded a fresh temp directory per run. That made the flag mean two different
        // things depending on whether `--db` was present, and it made the Output Plane
        // unreachable from the CLI on the DEFAULT path: `pipeline console --control-dir X`
        // could not be pointed at a run that had not been given a database.
        //
        // That is not a cosmetic inconsistency. M1 moved process output into the plane
        // (ADR-M1 D2/D3), so "where the plane is written" IS the product question, and a flag
        // that answers it only on one of two execution paths is a flag that cannot be relied on
        // to observe the default one. The default is unchanged: with no `--control-root`, this
        // still creates a private temp directory.
        val controlDirRoot: Path = if (config.controlRoot != null) {
            try {
                validateControlRoot(config.controlRoot)
            } catch (e: IllegalArgumentException) {
                System.err.println("Error: ${e.message}")
                System.exit(2)
                throw e // unreachable
            }
        } else {
            java.nio.file.Files.createTempDirectory("pipelinek-inmem-run")
        }
        val runIdDirectory = RunIdDirectory(controlDirRoot.resolve("last-run"))
        val fresh = UuidRunIdGenerator().next()
        runIdDirectory.record(definitionId, fresh)
        val runId: String = fresh.value
        val host = Kotlin24ScriptingHost(eventStore, runId)
        val dslClasspath = computeScriptClasspath(config.pluginJars)
        val definition0 = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
        // Inject the production RuntimeConfig so DSL `pwd()` / `isUnix()` synchronous
        // return values reflect the host environment. Lfc0GlobalStateFitnessTest
        // requires :pipeline-scripting-api to read no global state; this scope is
        // the single bridge. Cleared in a finally block.
        dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.set(SystemRuntimeConfig())
        val result = try {
            host.compile(definition0)
        } finally {
            dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.clear()
        }

        val pipelineSpec: PipelineSpec? = if (result.isSuccess) {
            val scriptInstance = result.scriptInstance
            scriptInstance?.let { inst ->
                try {
                    val resultMethod = inst.javaClass.getMethod("get\$\$result")
                    @Suppress("UNCHECKED_CAST")
                    resultMethod.invoke(inst) as? PipelineSpec
                } catch (_: Exception) {
                    null
                }
            }
        } else null

        val compileOutcome: dev.rubentxu.pipeline.v2.domain.RunOutcome? = if (result is dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult.Failure) {
            // TRAIN-DSL-HONESTY: evaluation failures (any exception thrown while
            // the DSL body constructs its IR, including the fail-closed
            // rejections of whenCondition/retry) arrive here as a Failure. The
            // user's own diagnostic message must survive instead of becoming an
            // anonymous NPE.
            val reason = result.diagnostics
                .lastOrNull { it.severity == dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity.ERROR }
                ?.message
                ?.takeIf { it.isNotBlank() }
                ?: "Kotlin compilation failed"
            dev.rubentxu.pipeline.v2.domain.RunOutcome.Failure(
                dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                    message = reason
                )
            )
        } else null

        val clock: dev.rubentxu.pipeline.v2.domain.durable.Clock = SystemClock()
        val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal =
            dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal(clock)
        val cursorStore: dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore =
            dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore(clock)

        val compiledPipeline = pipelineSpec?.let { spec ->
            DslCompiledPipelineCompiler.compile(
                spec = spec,
                sourcePath = scriptPath.toString(),
                sourceContent = scriptContent,
                pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("builtin"),
            )
        }

        // LB-02 / EP-6: compose registry BEFORE the gate so contributed keys are eligible.
        val pluginClassLoader = pluginClassLoaderFor(config.pluginJars)
        // S6/G: ONE resolution for the whole run, before anything is analysed or executed.
        // Steps, Directives and Event kinds come from the same classloader window and are frozen
        // together, so a plugin can never be half-present.
        val composition = PluginComposition.resolve(pluginClassLoader)
        composition.reportTo { line -> System.err.println(line) }
        val composedStepRegistry = composition.steps
        val nonCanonicalSteps = compiledPipeline
            ?.analyzeCanonicalDurableExecution(composedStepRegistry).orEmpty()
        // WU-LPR-103: the default `pipeline run <script>` (in-memory) branch composes
        // the SAME credential injection stack as the durable branch. The default path
        // is the temp in-memory control dir's sibling; PIPELINE_CREDENTIALS_STORE env
        // overrides, exactly like the durable path.
        val withCredentialsExecutor = composeWithCredentialsExecutor(controlDirRoot)
        // RP034-H / ADR-0101 clause 3.1 + RP034-Id: the workspace origin is decided
        // ONCE, here at the boundary. Both facts cross into the runtime — the shared
        // directory and its owner — because a runtime that receives only the root
        // re-derives ownership and lets `deleteDir` erase a user's project under the
        // local-first default. `--isolated` keeps the historical PipelineK scratch;
        // `--workspace <path>` stays authoritative.
        val workspaceTransport = WorkspaceIntent.resolveRuntimeTransport(
            config, Path.of("").toAbsolutePath().normalize(), controlDirRoot,
        )
        val runOutcome: dev.rubentxu.pipeline.v2.domain.RunOutcome? = when {
            // Compilation must be checked FIRST. If the script failed to compile there is no
            // compiled pipeline to run; jumping to runCanonicalPipeline would NPE on `!!`. This
            // regression was introduced when the per-step canonical gate was added (v0.33.1 P2
            // corpus-closure) but the compile-failure branch was left inside `else -> compileOutcome`
            // which only fires when nonCanonicalSteps is non-empty OR pipelineSpec is non-null.
            compileOutcome != null -> compileOutcome
            // TRAIN-DSL-HONESTY: compile "succeeded" but produced no PipelineSpec. Before this
            // branch this reached runCanonicalPipeline(compiledPipeline!!) and died as an NPE
            // with exit 0. A script that cannot produce its IR is an admission failure.
            compiledPipeline == null -> dev.rubentxu.pipeline.v2.domain.RunOutcome.Failure(
                dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                    message = "the script compiled but did not produce a PipelineSpec; " +
                        "its body failed during construction and no pipeline can run"
                )
            )
            nonCanonicalSteps.isEmpty() -> runCanonicalPipeline(
                pipeline = compiledPipeline!!,
                runId = RunId(runId),
                journal = journal,
                cursorStore = cursorStore,
                clock = clock,
                effectReplayPolicy = DefaultEffectReplayPolicy(),
                eventSink = eventStore,
                controlDirRoot = controlDirRoot,
                sandboxProfile = config.sandboxProfile,
                // RP034-H / ADR-0101 clause 3.1: the CLI default is local-first. The
                // workspace origin is decided once, here at the boundary, and BOTH
                // facts cross into the runtime: the shared directory and its owner.
                // `--isolated` preserves the historical PipelineK-managed scratch;
                // `--workspace <path>` stays authoritative.
                workspaceBase = workspaceTransport.base,
                workspaceOwnership = workspaceTransport.ownership,
                // S6/G: the frozen composition, not a step registry plus a classloader.
                // There is no path here that can reach a registry without a directive registry
                // having been resolved under the same loader.
                composition = composition,
                secretPatternRegistry = secretPatternRegistry,
                withCredentialsExecutor = withCredentialsExecutor,
                // H5-B: NO credentialProvider on this path, deliberately. It composes
                // with an in-memory journal and receives no store, so the seam takes
                // its default: a source that refuses every lookup with
                // `NotConfigured`. That is the honest answer for a run that has
                // nowhere to look, and it is the same answer the durable path gives
                // when the operator configured no store. Passing a provider here
                // would mean opening a secret store for a path that has no use for
                // one.
                // H8-D1: this path composes its own coordinator and had the same
                // omission as the durable one. `--allow-network` is a per-RUN
                // permission; a run whose flag is ignored is a run where the
                // operator believes they opened the network and did not.
                allowNetwork = config.allowNetwork,
            )
            else -> {
                // Fail-closed: non-canonical pipelines are not supported by the canonical bridge.
                // Same gate and message as the durable path (STEP SEMANTICS: fail-closed on every run path).
                System.err.println(NON_CANONICAL_CANONICAL_BRIDGE_ERROR)
                for (step in nonCanonicalSteps) {
                    System.err.println("  - Step '${step.stepId}' (${step.pluginStepId}): ${step.reason}")
                }
                System.exit(2)
                null // unreachable
            }
        }

        val events = eventStore.eventsFor(runId).toList()
        // ADR-0088: human text was already streamed live by ConsolePrintingEventSink.
        // Only the machine formats still need a document at the end.
        if (config.format != ObservationFormat.TEXT) {
            print(RunObservationOutput.encode(events, config.view, config.format, compiledQuery))
        }
        val exitFailure: Boolean = if (runOutcome != null) {
            when (runOutcome) {
                is dev.rubentxu.pipeline.v2.domain.RunOutcome.Success -> {
                    System.err.println("Pipeline finished with SUCCESS"); false
                }
                is dev.rubentxu.pipeline.v2.domain.RunOutcome.Unstable -> {
                    System.err.println("Pipeline finished with UNSTABLE"); false
                }
                is dev.rubentxu.pipeline.v2.domain.RunOutcome.Failure -> {
                    // TRAIN-DSL-HONESTY: an admission failure (compile or DSL
                    // construction) must carry its diagnostic. "FAILURE" alone
                    // sent users hunting with zero context.
                    runOutcome.failure.message
                        ?.takeIf { it.isNotBlank() && it != "Kotlin compilation failed" }
                        ?.let { System.err.println("Pipeline finished with FAILURE: $it") }
                        ?: System.err.println("Pipeline finished with FAILURE")
                    true
                }
                else -> {
                    System.err.println("Pipeline finished with FAILURE"); true
                }
            }
        } else {
            val lastEvent = events.lastOrNull()
            val legacyOutcome = if (lastEvent is RunFinished && lastEvent.outcome == "success") "success" else "failure"
            when (legacyOutcome) {
                "success" -> { System.err.println("Pipeline finished with SUCCESS"); false }
                "unstable" -> { System.err.println("Pipeline finished with UNSTABLE"); false }
                else -> { System.err.println("Pipeline finished with FAILURE"); true }
            }
        }
        if (exitFailure) System.exit(1)
        return
    }

    // Durable mode: SqliteEventStore + PipelineOrchestrator for replay/divergence gating.
    // Both stores are wrapped with RedactingEventSink at construction time (design §Data Flow).
    val rawEventStore = SqliteEventStore(config.dbPath)
    // Call raw methods BEFORE wrapping — RedactingEventSink delegates these to the inner store
    val factory = rawEventStore.underlyingConnectionFactory()
    val dbPathStr = rawEventStore.databasePath()
    val eventStore = RedactingEventSink(rawEventStore, secretPatternRegistry)

    // The script-derived hash is the DefinitionId. Durable policy decides
    // whether its persisted RunId is reused or intentionally replaced.
    val scriptContent = scriptPath.toFile().readText()
    val definitionId = dev.rubentxu.pipeline.v2.domain.DeterministicIdGenerator.definitionId(
        scriptPath.toString(),
        scriptContent,
    )
    // ML-R1: controlDirRoot is the parent directory of the SQLite db file (default).
    // Can be overridden via --control-root flag for testing.
    // Each step gets a subdirectory: $controlDirRoot/$runId-$stageIndex-$stepIndex/
    // C5: Validate control-root path before use
    val dbPath = Paths.get(config.dbPath!!)
    val controlDirRoot: Path = if (config.controlRoot != null) {
        try {
            validateControlRoot(config.controlRoot)
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.exit(2)
            throw e // unreachable
        }
    } else {
        dbPath.parent.resolve("durable-shell")
    }
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
    val runIdDirectory = RunIdDirectory(controlDirRoot.resolve("last-run"))
    val runSelection = try {
        selectDurableRun(
            policy = config.durableRunPolicy,
            runIdDirectory = runIdDirectory,
            definitionId = definitionId,
            runIdGenerator = UuidRunIdGenerator(),
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
    when (lease) {
        is LeaseAcquisition.Acquired, is LeaseAcquisition.TakenOver,
        is LeaseAcquisition.Reentered -> Unit
        is LeaseAcquisition.AlreadyOwned -> {
            // Typed admission rejection: this run already has a live owner.
            // Exit 2 (invocation/admission), not a pipeline failure.
            runCatching { leaseStore.close() }
            System.err.println(
                "Error: run $runId is already owned by '${lease.heldBy.value}'; " +
                    "another process is publishing events for this run"
            )
            System.exit(2)
            throw IllegalStateException("unreachable")
        }
        is LeaseAcquisition.Unverifiable -> {
            // Fail closed: a process that cannot prove it is the authority must
            // not publish. Kept distinct from AlreadyOwned because the
            // operator response differs.
            runCatching { leaseStore.close() }
            System.err.println("Error: cannot establish ownership of run $runId: ${lease.reason}")
            System.exit(2)
            throw IllegalStateException("unreachable")
        }
    }
    val host = Kotlin24ScriptingHost(eventStore, runId)
    val dslClasspath = computeScriptClasspath(config.pluginJars)
    val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
    // Inject the production RuntimeConfig so DSL `pwd()` / `isUnix()` synchronous
    // return values reflect the host environment for the durable run path.
    dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.set(SystemRuntimeConfig())
    val result = try {
        host.compile(definition)
    } finally {
        dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.clear()
    }

    val pipelineSpec: PipelineSpec? = if (result.isSuccess) {
        val scriptInstance = result.scriptInstance
        scriptInstance?.let { inst ->
            try {
                val resultMethod = inst.javaClass.getMethod("get\$\$result")
                @Suppress("UNCHECKED_CAST")
                resultMethod.invoke(inst) as? PipelineSpec
            } catch (invocationError: java.lang.reflect.InvocationTargetException) {
                // S0-C1 / TRAIN-DSL-HONESTY: `get$$result` runs the script BODY,
                // so this is where a fail-closed DSL guard fires (e.g. the Pure
                // Builder Consumption gate rejecting an unconsumed MUST_CONSUME
                // carrier). Swallowing it into `null` turned a construction
                // rejection into "no pipeline", which the runner then reported
                // downstream as a runtime step failure — the author's discarded
                // value disappeared and a StepFailed appeared instead.
                //
                // Unwrap the reflective wrapper so the real, actionable message
                // reaches the author verbatim.
                throw (invocationError.targetException ?: invocationError)
            }
        }
    } else null

    // LFC-2R / R4B — frontend FORM selection. The compiler-backed mapping decides
    // whether this source contains runtime-effectful scripted calls; Main only
    // picks the closed artifact FORM, never an execution authority and never a
    // concrete Step (R4A law: Main may choose the frontend, never the backend).
    val scriptedFrontend: dev.rubentxu.pipeline.v2.application.scripted.ScriptedFrontendRunner.EntryPointArtifact? =
        if (result.isSuccess) {
            val mapping = KotlinScriptedSourceMapper().map(ScriptedSource(ScriptedSourceId(scriptPath.fileName.toString()), scriptContent))
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

    val scriptedEntryPoint: dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint? =
        scriptedFrontend?.let { artifact ->
            val definitionScript = """
                ${artifact.loweredSource}
            """.trimIndent()
            val scriptedDefinition = ScriptDefinition.inline(
                text = definitionScript,
                classpath = computeScriptClasspath(config.pluginJars),
            )
            // Compiled WITHOUT the eager RuntimeConfig injection: a runtime-returned
            // body must never observe the platform through the eager DSL port.
            val scriptedResult = host.compile(scriptedDefinition)
            when {
                scriptedResult is dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult.Failure -> {
                    System.err.println("Error: scripted frontend compilation failed: ${scriptedResult.diagnostics}")
                    System.exit(2)
                    null // unreachable
                }
                else -> when (val out = (scriptedResult as dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult.Success).output) {
                    is ScriptEvaluationOutput.CompiledEntryPoint -> out.entryPoint
                    else -> {
                        System.err.println("Error: scripted frontend did not produce a compiled entry point")
                        System.exit(2)
                        null // unreachable
                    }
                }
            }
        }

    val compileOutcome: RunOutcome? = if (result is dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult.Failure) {
        RunOutcome.Failure(
            dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                message = "Kotlin compilation failed"
            )
        )
    } else null

    val compiledPipeline = pipelineSpec?.let { spec ->
        DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = scriptPath.toString(),
            sourceContent = scriptContent,
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("builtin"),
        )
    }

    // Build orchestrator with all durable dependencies
    val clock: Clock = SystemClock()
    val journal: OperationJournal = SqliteOperationJournalImpl(factory, clock, Json { ignoreUnknownKeys = true; encodeDefaults = true }, dbPathStr)
    val cursorStore: ReplayCursorStore = SqliteReplayCursorStoreImpl(factory, clock)
    val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector()
    val effectPolicy: EffectReplayPolicy = DefaultEffectReplayPolicy()

    // T12: Resolve SecretStore for credential injection in withCredentials blocks.
    //
    // Design: Option A — env var PIPELINE_CREDENTIALS_STORE for store path,
    // PIPELINE_STORE_PASSPHRASE for passphrase.  Default path is
    // <controlDirRoot>/../credentials.bin (sibling to the journal db).
    //
    // Behavior:
    // - Store file does not exist → pass null (user runs `pipeline credentials add`
    //   first to create it; no error at startup)
    // - Store file exists + passphrase available → inject credentials
    // - Store file exists + passphrase wrong/missing → fail fast with actionable error
    val credentialsStorePath: Path = System.getenv("PIPELINE_CREDENTIALS_STORE")?.let { Paths.get(it) }
        ?: controlDirRoot.parent.resolve("credentials.bin")

    val secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore? =
        if (!credentialsStorePath.toFile().exists()) {
            // File doesn't exist yet — user must create it via `pipeline credentials add`
            null
        } else {
            // File exists — resolve passphrase and open the store
            try {
                val passphraseChars = PassphraseResolver.resolve()
                val store = LocalSecretStore(credentialsStorePath, passphraseChars)
                // Do NOT wipe passphraseChars here — LocalSecretStore stores the
                // same CharArray reference and would lose its own copy. The store
                // zeros the passphrase in its own close().
                store
            } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
                System.err.println("Error: ${e.message}")
                System.err.println("Hint: set PIPELINE_STORE_PASSPHRASE env var, or run interactively in a TTY.")
                System.exit(3)
                null // unreachable
            } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
                System.err.println("Error: $e.message")
                System.err.println("Hint: the passphrase does not match. Check PIPELINE_STORE_PASSPHRASE.")
                System.exit(3)
                null // unreachable
            } catch (e: LocalSecretStore.SecretStoreTamperException) {
                System.err.println("Error: credentials store tampered: ${e.message}")
                System.exit(4)
                null // unreachable
            }
        }

    // H0 composition root: construct credential ports and executor
    // LocalSecretStore (existing) -> LocalCredentialProvider -> CredentialMaterializer -> LocalFileMaterialization -> WithCredentialsExecutor
    val credentialProvider: dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider? = secretStore?.let { LocalCredentialProvider(it) }
    val credentialMaterialization: dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization? = secretStore?.let { LocalFileMaterialization(CredentialMaterializer(it)) }
    val withCredentialsExecutor: WithCredentialsExecutor? = if (credentialProvider != null && credentialMaterialization != null) {
        WithCredentialsExecutor(credentialProvider, credentialMaterialization, clock)
    } else {
        null
    }

    // LF-0205 (closed LEG-1.1): the legacy PipelineOrchestrator was constructed here but
    // never invoked. The CLI reaches the durable runtime ONLY through the canonical
    // RunCoordinator (fail-closed exit 2 otherwise). LEG-1 burn-down deleted the class.
    // LB-02 / EP-6: compose the registry BEFORE the canonical-eligibility gate —
    // eligibility is registry-derived, so external plugin contributions must be
    // visible to the gate or a contributed key would be wrongly rejected as
    // non-canonical. Same composed registry is handed to the coordinator below.
    val pluginClassLoader = pluginClassLoaderFor(config.pluginJars)
    // S6/G: ONE resolution for the whole run, before eligibility is checked and before the
    // coordinator is built. This branch previously composed the Step registry a SECOND time,
    // with the same code the in-memory branch above already ran: two answers to one question,
    // one per branch.
    val composition = PluginComposition.resolve(pluginClassLoader)
    composition.reportTo { line -> System.err.println(line) }
    val composedStepRegistry = composition.steps
    // RP034-H / ADR-0101 clause 3.1 + RP034-Id: the workspace origin is decided
    // ONCE, here at the boundary, and both facts cross into the runtime — the
    // shared directory and its owner. `--isolated` keeps the historical
    // PipelineK scratch; `--workspace <path>` stays authoritative.
    val workspaceTransport = WorkspaceIntent.resolveRuntimeTransport(
        config, Path.of("").toAbsolutePath().normalize(), controlDirRoot,
    )
    // UAT-RP-024 collateral finding fix (shutdown race 1/3): the sqlite
    // single-writer thread is NON-daemon. If any exception escapes the run
    // or the stdout envelope streaming below, rawEventStore.close() is
    // skipped, the writer keeps blocking on queue.take(), and
    // DestroyJavaVM hangs forever (observed: first replay of the dogfood
    // receipt hung until manual kill). Compute the outcome and the last
    // event inside the try; guarantee the close in finally on every path.
    val runOutcomeAndLastEvent: Pair<RunOutcome?, dev.rubentxu.pipeline.v2.events.DomainEvent?> = try {
    val outcome: RunOutcome? = when {
        // LFC-2R / R4B: the scripted FRONTEND form runs against the SAME durable
        // authority (journal, registry, event sink, control root) composed for the
        // canonical path. This is a frontend selection, never a second runner: the
        // invocation authority is the registry seam proven in R1/R2 (R4A model B).
        scriptedEntryPoint != null -> runScriptedFrontend(
            entryPoint = scriptedEntryPoint,
            runId = runId,
            artifact = requireNotNull(scriptedFrontend) { "scripted frontend artifact" }.identity,
            stepRegistry = composedStepRegistry,
            journal = journal,
            eventSink = eventStore,
            clock = clock,
            controlDirRoot = controlDirRoot,
            sandboxProfile = config.sandboxProfile,
        )
        compiledPipeline?.supportsCanonicalDurableExecution(composedStepRegistry) == true -> runCanonicalPipeline(
            pipeline = compiledPipeline,
            runId = RunId(runId),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = effectPolicy,
            eventSink = eventStore,
            controlDirRoot = controlDirRoot,
            sandboxProfile = config.sandboxProfile,
            // RP034-H / ADR-0101 clause 3.1: the CLI default is local-first. The
            // workspace origin is decided once, here at the boundary, and BOTH
            // facts cross into the runtime: the shared directory and its owner.
            // `--isolated` preserves the historical PipelineK-managed scratch;
            // `--workspace <path>` stays authoritative.
            workspaceBase = workspaceTransport.base,
            workspaceOwnership = workspaceTransport.ownership,
            withCredentialsExecutor = withCredentialsExecutor,
            // H5-B: same on the canonical run path. The credential seam is offered on
            // EVERY path or it is offered inconsistently, and an inconsistently
            // admitted Step is a Step whose behaviour depends on which branch the CLI
            // happened to take.
            credentialProvider = credentialProvider,
            // S6/G: the frozen composition, not a step registry plus a classloader.
            composition = composition,
            secretPatternRegistry = secretPatternRegistry,
            allowNetwork = config.allowNetwork,
        )
        pipelineSpec != null -> {
            // Fail-closed: non-canonical pipelines are not supported by the canonical bridge
            System.err.println(NON_CANONICAL_CANONICAL_BRIDGE_ERROR)
            System.exit(2)
            null // unreachable
        }
        else -> compileOutcome
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
    // WU-RP-044 (M5 RSS debt): stream the JSON envelope to stdout one event at a
    // time (identical byte output to JsonEventLog.encode) and track only the last
    // event for the legacy outcome branch — never materialise the full list or a
    // single monolithic JSON String (1 GiB transcripts made this multi-GB).
    val eventSequence = eventStore.eventsFor(runId)
    var lastEventCaptured: dev.rubentxu.pipeline.v2.events.DomainEvent? = null
    val stdout = System.out
    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(stdout, Charsets.UTF_8), 1 shl 16)
    val lastEventRef = { event: dev.rubentxu.pipeline.v2.events.DomainEvent -> lastEventCaptured = event }
    RunObservationOutput.writeTo(
        eventSequence.map { event ->
            lastEventRef(event)
            event
        },
        writer,
        config.view,
        config.format,
        compiledQuery,
    )
    writer.flush()
    rawEventStore.close()
    Pair(outcome, lastEventCaptured)
    } finally {
        // S2-R0: release ownership on every exit path, including the
        // exception paths, so a crashed run is immediately recoverable by the
        // next owner instead of waiting for the OS to reap the lock.
        runCatching { leaseStore.release(requireNotNull(runOwner)) }
        runCatching { leaseStore.close() }
        runCatching { rawEventStore.close() }
    }
    val runOutcome = runOutcomeAndLastEvent.first
    val lastEvent = runOutcomeAndLastEvent.second
    // When the coordinator ran, the typed outcome is the single authority for the
    // exit decision; the legacy event-based branch only covers compile-failure
    // paths where no definition was produced.
    val exitFailure: Boolean = if (runOutcome != null) {
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
        val last = lastEvent
        val legacyOutcome = if (last is RunFinished && last.outcome == "success") "success" else "failure"
        when (legacyOutcome) {
            "success" -> { System.err.println("Pipeline finished with SUCCESS"); false }
            "unstable" -> { System.err.println("Pipeline finished with UNSTABLE"); false }
            else -> { System.err.println("Pipeline finished with FAILURE"); true }
        }
    }
    if (exitFailure) System.exit(1)
}

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
private fun reportRunFailure(failure: PipelineFailure) {
    System.err.println("  cause [${failure.kind}]: ${failure.message}")
    failure.cause?.let { System.err.println("  origin: $it") }
}

