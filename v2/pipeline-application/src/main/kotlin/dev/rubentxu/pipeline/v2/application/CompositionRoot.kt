package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.FileBasedWaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.durable.credentials.WithCredentialsExecutorScopeAdapter
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * Canonical durable composition root extracted from [Main].
 *
 * C1-C (2026-09-26): keeps the exact 22-argument composition contract and the
 * same coordinator wiring while removing the durable runtime assembly from the
 * CLI entrypoint. The two existing Main call sites remain unchanged because
 * this package-level function keeps the same name and parameter order.
 *
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
    withCredentialsExecutor: WithCredentialsExecutor? = null,
    // LB-02 / EP-6: caller-composed registry (core + discovered external contributions).
    // Composition happens ONCE in the composition root, BEFORE the canonical-eligibility
    // gate, so contributed keys participate in the gate (eligibility is registry-derived).
    stepRegistry: InMemoryStepRegistry = CoreStepRegistryFactory.registry(),
    secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    // S1-D: plugin classloader hosting external plugin JARs. When present, directive
    // contributions are discovered under this loader's TCCL and folded into the
    // registry handed to the coordinator. Absent/null => no directive registry
    // (legacy behaviour: a stage with directives denies; no directives runs as before).
    pluginClassLoader: ClassLoader? = null,
): RunOutcome = runBlocking {
    CanonicalDurableRunCoordinator(
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
            // WU-LPR-071: with --workspace <dir>, the project's own directory IS the
            // workspace — stages share it (Jenkins-familiar semantics). Adding
            // .resolve("workspace") would point to a subdirectory of the project root
            // (typically nonexistent), and every `sh` step would fail with
            // "No such file or directory" because gradlew/mvn/node live in
            // the project root itself. Without --workspace, workspace/file operations
            // retain their legacy per-stage layout, but shell execution starts in the
            // CLI invocation directory. Keep that shell CWD explicit so dir scopes and
            // WorkspaceIdentity observe the same directory as the process.
            workspaceRoot = workspaceBase ?: controlDirRoot.resolve("workspace"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
            sandbox = SandboxConfigResolver.resolve(sandboxProfile),
            workingDirectory = if (workspaceBase == null) {
                Path.of("").toAbsolutePath().normalize()
            } else {
                null
            },
        ),
        // B1.2c3-S2.3 + LB-02/EP-6: core Steps first, then external plugin contributions.
        stepRegistry = stepRegistry,
        secretPatternRegistry = secretPatternRegistry,
        // RETRY-D (ADR-0075): production wire-up. The retry aggregate is reconciled against
        // the on-disk control journal so a `run` invocation with the same --db and
        // --control-root reuses the prior aggregate terminal state and does not re-launch
        // child bodies that already succeeded/failed terminally.
        retryControlJournal = FileBasedRetryControlJournal(controlDirRoot),
        // WU-G5R.5 (ADR-0075 analog): durable waitUntil control journal. Matches the
        // retryControlJournal pattern — persisted before child effects, read on plan(),
        // authoritative over the aggregate state on replay.
        waitUntilControlJournal = FileBasedWaitUntilControlJournal(controlDirRoot),
        // E1.2 / T1: per-run artifact index for the core.archiveArtifacts
        // -> core.artifact.query bridge. Constructed fresh per run; the same
        // instance is shared between the producer (archive with name=...) and
        // the consumer (artifactQuery) within the run.
        artifactIndex = dev.rubentxu.pipeline.v2.application.durable.ArtifactIndexAdapter.build(),
        // S1-D: fold external directive contributions discovered under the plugin
        // classloader's TCCL. Null loader => null registry => legacy behaviour.
        //
        // S2-A: `core.when` is a CORE definition, so it is registered here
        // unconditionally rather than through the plugin classpath. It enters
        // through the same open registry as any vendor directive, which is what
        // keeps "open by key" honest: if it needed a special case, the seam
        // would not be open.
        directiveRegistry = run {
            val builder = dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry.Builder()
            builder.add(
                dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition(
                    WhenDirectiveDefinition()
                )
            )
            if (pluginClassLoader != null) {
                val previousTccl = Thread.currentThread().contextClassLoader
                Thread.currentThread().contextClassLoader = pluginClassLoader
                try {
                    val contributed = ExternalDirectivePluginDiscovery.registerInto(builder)
                    if (contributed.isNotEmpty()) {
                        System.err.println(
                            "Discovered external directive plugins: " + contributed.joinToString(", ")
                        )
                    }
                } finally {
                    Thread.currentThread().contextClassLoader = previousTccl
                }
            }
            builder.build()
        },
        // S2-A: gates read the environment the STAGE actually declares, plus
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
    ).run(pipeline, runId)
}
