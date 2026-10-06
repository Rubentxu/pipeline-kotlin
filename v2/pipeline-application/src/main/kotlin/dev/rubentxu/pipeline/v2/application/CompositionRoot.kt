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
    // RP034-Id: explicit owner of `workspaceBase`. `null` means the caller
    // could not state ownership, and the runtime then treats the root as
    // PipelineK-managed. The CLI always states it (WorkspaceIntent).
    workspaceOwnership: dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership? = null,
    withCredentialsExecutor: WithCredentialsExecutor? = null,
    /**
     * H5-B: the store the operator configured, if any.
     *
     * Turned into [BasicCredentialsCapabilityContributor] here, which is the ONE
     * place allowed to know both a credentials port and a plugin-facing capability
     * shape. `Main` passes the provider it already builds and never imports the
     * http plugin; the plugin declares `credentials.basic` and never imports this.
     *
     * A run with no store still contributes the capability, backed by a source that
     * refuses every lookup with a reason that says so. Withholding it would refuse
     * admission for every Step that declares the seam, including those that declare
     * no credential and would never call it.
     */
    credentialProvider: dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider? = null,
    /**
     * RP6-C / LFC-2E3 (`--allow-network`): permit outbound egress for this run.
     *
     * FALSE by default. The transport a plugin needs is contributed through
     * a capability contributor and the PERMISSION is produced here, so a plugin can
     * be present in the distribution and still be unable to reach the network
     * until an operator says so.
     */
    allowNetwork: Boolean = false,
    /**
     * RP6-C / LFC-2E3 (H2b): the capabilities OFFICIAL_PLUGINs contribute.
     *
     * A LIST OF CONTRIBUTORS, composed here and consulted at both admission and
     * execution, rather than a finished map. Composition is a value a reviewer
     * can read, collisions fail closed, and adding the next plugin is one more
     * element in this list — with no change here, in the coordinator, or in the
     * capability access.
     *
     * H4.5: the default is DISCOVERY, not `emptyList()`. It used to default to an
     * empty list that the CLI never filled, which meant `http.request` was refused
     * at admission in the installed distribution for a missing `http.transport` —
     * a Step that passed every contract test and could not run. The default is
     * evaluated per call, so a caller that injects its own contributors (a test
     * asserting a specific capability set) is unaffected and no discovery happens
     * on its behalf.
     */
    capabilityContributors: List<dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor> =
        ExternalCapabilityContributorDiscovery.discover() +
            // H5-B: the credential seam is per-run state — it wraps the store THIS
            // invocation opened — so it cannot come from classpath discovery the way
            // a plugin's own transport does. Discovery supplies the plugin side; this
            // supplies the runtime side, and the two never collide because the plugin
            // deliberately contributes only its transport.
            dev.rubentxu.pipeline.v2.credentials.executor.BasicCredentialsCapabilityContributor(
                credentialProvider,
            ),
    // LB-02 / EP-6: caller-composed registry (core + discovered external contributions).
    // Composition happens ONCE in the composition root, BEFORE the canonical-eligibility
    // gate, so contributed keys participate in the gate (eligibility is registry-derived).
    stepRegistry: StepRegistry = CoreStepRegistryFactory.registry(),
    secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    // S1-D: plugin classloader hosting external plugin JARs. When present, directive
    // contributions are discovered under this loader's TCCL and folded into the
    // registry handed to the coordinator. Absent/null => no directive registry
    // (legacy behaviour: a stage with directives denies; no directives runs as before).
    pluginClassLoader: ClassLoader? = null,
    // P3-B / S6.4: the composed open event registry. Null means "discover it under
    // [pluginClassLoader]", which is the ordinary production path; an explicit value exists so a
    // caller that already composed one (or a test that wants an empty one) is not made to re-run
    // discovery. Composition happens in the BODY, under the same classloader swap the directive
    // discovery below already performs, so the contributor and the Step that emits come from the
    // same loader — a registry naming codecs the executing side cannot load would otherwise fail
    // as a ClassCastException at emission rather than a refusal at composition.
    eventRegistry: dev.rubentxu.pipeline.v2.events.registry.EventRegistry? = null,
): RunOutcome = runBlocking {
    // P3-B / S6.4: ONE composition of the open event registry for this run, done here and nowhere
    // else. Under the plugin classloader when there is one, so a contributor and the Step that
    // emits come from the same loader.
    //
    // A broken contributor or a colliding kind aborts the run by throwing out of `compose`. That
    // is the intended outcome: a registry that admitted three of a plugin's four event kinds would
    // look like a working feature that quietly drops one of its own observations.
    val composedEventRegistry: dev.rubentxu.pipeline.v2.events.registry.EventRegistry =
        eventRegistry ?: if (pluginClassLoader != null) {
            val previousTccl = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = pluginClassLoader
            try {
                ExternalEventDefinitionDiscovery.compose().also { composed ->
                    if (composed.size() > 0) {
                        System.err.println(
                            "Discovered external event definitions: " +
                                composed.registeredKinds().joinToString(", ")
                        )
                    }
                }
            } finally {
                Thread.currentThread().contextClassLoader = previousTccl
            }
        } else {
            dev.rubentxu.pipeline.v2.events.registry.EventRegistry.create()
        }

    CanonicalDurableRunCoordinator(
        // P3-B/P3-C: the ONE registry this run resolves plugin event kinds against, on the write
        // side and on the read-back side.
        eventRegistry = composedEventRegistry,
        // H2b: ONE composite, consulted by both admission and execution. The
        // contributors are composed here and nowhere else; a plugin that needs a
        // seam adds one element to this list and changes nothing in the engine,
        // the boundary or the coordinator.
        capabilityContributor = CompositeCapabilityContributor(capabilityContributors),
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
            // the project root itself. Without --workspace we keep the legacy
            // per-stage layout.
            workspaceRoot = workspaceBase ?: controlDirRoot.resolve("workspace"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
            sandbox = SandboxConfigResolver.resolve(sandboxProfile),
            // RP034-Id: carry the ownership the CLI decided (ADR-0101) into the
            // runtime transport, so the ADR-0102 destructive guard reads the real
            // owner of this root instead of re-deriving `Managed`.
            workspaceOwnership = workspaceOwnership,
            // RP6-C / LFC-2E3: egress is DENIED unless the operator asked for it
            // with --allow-network. The bridge turns this gate into the generic
            // NETWORK_EGRESS_CAPABILITY, and a Step that needs the network declares
            // it — so the default run cannot reach one.
            //
            // H6: the two ends are gates, not verdicts. `--allow-network` is
            // [AllowAll] and the default is [DenyAll], which are the SAME interface
            // as a future per-destination allowlist — so adding one later is a new
            // value here and not a new code path through the runtime, the Step, or
            // the admission check.
            networkEgress = if (allowNetwork) AllowAll else DenyAll,
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
        // S4 retention: the run's terminal state is the ONLY thing that can authorise discarding
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
            retention = { OutputPlaneProvider.storeFor(controlDirRoot) },
            policy = RetainUntil.ExplicitReleaseOnly,
        ),
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
            // S3.1: `core.agent` enters through the SAME open registry as every other
            // directive. If resolving an execution target had needed its own
            // registration path, the seam would not be open, and the whole point of
            // the Resource policy is that the engine reads the policy rather than the key.
            builder.add(
                dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition(
                    AgentDirectiveDefinition()
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
