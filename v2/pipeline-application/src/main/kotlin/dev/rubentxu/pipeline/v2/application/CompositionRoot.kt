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
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking

/**
 * The ONE place a pipeline is run.
 *
 * C1-C (2026-09-26) extracted the durable runtime assembly from the CLI
 * entrypoint. This is the second half of that job: the function took
 * EIGHTEEN parameters and had TWO call sites -- the in-memory world and the
 * durable one -- which is exactly the shape in which two call sites drift.
 *
 * It now takes ONE [CanonicalRunRequest], and both worlds build the same
 * request. The difference between "runs in memory" and "runs in SQL" is the
 * [CanonicalRunState] group: the journal, the cursor store, the event sink
 * and the Saga seam. Everything else -- the clock, the replay policy, the
 * sandbox, the workspace, the credential seam -- is decided once and lands in
 * the request, so the two worlds cannot disagree about it.
 *
 * Two parameters are GONE, and their absence is the point:
 *
 * - `capabilityContributors` defaulted to `null`, which meant "whatever the
 *   composition resolved". The composition is now the only source of plugin
 *   capabilities (S6/G), and no caller ever passed anything else, so the knob
 *   was a way to inject a FOURTH authority. It is removed, not deprecated.
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
        // P3-B/P3-C: the ONE registry this run resolves plugin event kinds against, on the write
        // side and on the read-back side. S6/G: resolved in PluginComposition, before this
        // function was called, and already cross-checked against what the plugins emit.
        eventRegistry = request.composition.events,
        // H2b: ONE composite, consulted by both admission and execution.
        //
        // S6/G: the plugin side now comes from the composition, resolved under the same classloader
        // swap as the Steps these capabilities back. The credential side stays here because it is
        // PER-RUN state — it wraps the store THIS invocation opened, so it cannot come from
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
            // WU-LPR-071: with --workspace <dir>, the project's own directory IS the
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
            // RP034-Id: carry the ownership the CLI decided (ADR-0101) into the
            // runtime transport, so the ADR-0102 destructive guard reads the real
            // owner of this root instead of re-deriving `Managed`.
            workspaceOwnership = policy.workspace.ownership,
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
            networkEgress = if (policy.allowNetwork) AllowAll else DenyAll,
        ),
        // B1.2c3-S2.3 + LB-02/EP-6: core Steps first, then external plugin contributions,
        // frozen by PluginComposition before this call.
        stepRegistry = request.composition.steps,
        secretPatternRegistry = policy.secretPatternRegistry,
        // SAGA / PK-1: forwarded so the dispatch can build the invocation-bound intent port.
        sagaBinding = state.sagaBinding,
        // RETRY-D (ADR-0075): production wire-up. The retry aggregate is reconciled against
        // the on-disk control journal so a `run` invocation with the same --db and
        // --control-root reuses the prior aggregate terminal state and does not re-launch
        // child bodies that already succeeded/failed terminally.
        retryControlJournal = FileBasedRetryControlJournal(policy.controlDirRoot),
        // WU-G5R.5 (ADR-0075 analog): durable waitUntil control journal. Matches the
        // retryControlJournal pattern — persisted before child effects, read on plan(),
        // authoritative over the aggregate state on replay.
        waitUntilControlJournal = FileBasedWaitUntilControlJournal(policy.controlDirRoot),
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
            retention = { OutputPlaneProvider.storeFor(policy.controlDirRoot) },
            policy = RetainUntil.ExplicitReleaseOnly,
        ),
        // S6/G: resolved in PluginComposition, under the same classloader swap that produced
        // the step registry and the event registry, and frozen before this call.
        //
        // S2-A / S3.1 are preserved there rather than lost: `core.when` and `core.agent` are
        // CORE definitions that enter through the SAME open registry as any vendor directive,
        // which is what keeps "open by key" honest. If either had needed its own registration
        // path, the seam would not be open.
        directiveRegistry = request.composition.directives,
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
    ).run(request.pipeline, request.runId)
}
