package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.PLATFORM_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PlatformIdentity
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.EXECUTION_BUDGET_CAPABILITY
import dev.rubentxu.pipeline.v2.application.EXECUTION_LANE_CAPABILITY
import dev.rubentxu.pipeline.v2.application.ExecutionBudget
import dev.rubentxu.pipeline.v2.application.ExecutionLaneId
import dev.rubentxu.pipeline.v2.application.FileLockCoordinator
import dev.rubentxu.pipeline.v2.application.FileInputDecisions
import dev.rubentxu.pipeline.v2.application.INPUT_DECISIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.LOCK_COORDINATION_CAPABILITY
import dev.rubentxu.pipeline.v2.application.SHELL_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.STAGE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.StageIdentity
import dev.rubentxu.pipeline.v2.application.ShellOperations
import dev.rubentxu.pipeline.v2.application.TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.TemporaryWorkspaceOperations
import dev.rubentxu.pipeline.v2.application.WORKSPACE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.application.WorkspaceOperations
import dev.rubentxu.pipeline.v2.application.WorkspaceOperationsAdapter
import dev.rubentxu.pipeline.v2.application.ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.ArchiveArtifactsOperations
import dev.rubentxu.pipeline.v2.application.ArchiveArtifactsOperationsAdapter
import dev.rubentxu.pipeline.v2.application.STASH_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.StashOperations
import dev.rubentxu.pipeline.v2.application.StashOperationsAdapter
import dev.rubentxu.pipeline.v2.application.PUBLISH_HTML_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PublishHtmlOperations
import dev.rubentxu.pipeline.v2.application.PublishHtmlOperationsAdapter
import dev.rubentxu.pipeline.v2.application.DELETE_DIR_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.DeleteDirOperations
import dev.rubentxu.pipeline.v2.application.durable.DeleteDirOperationsAdapter
import dev.rubentxu.pipeline.v2.application.CLEAN_WS_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.CleanWsOperations
import dev.rubentxu.pipeline.v2.application.durable.CleanWsOperationsAdapter
import dev.rubentxu.pipeline.v2.application.MILESTONE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.MilestoneOperations
import dev.rubentxu.pipeline.v2.application.MilestoneOperationsAdapter
import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.application.ARTIFACT_INDEX_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BODY_INVOKER_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/**
 * CDE.3-d1: explicit, small capability bridge from a [CanonicalRuntimeContext] to a
 * [StepCapabilityAccess].
 *
 * It exposes ONLY the capabilities the canonical runtime actually declares and provides today
 * ([EVENT_SINK_CAPABILITY] backed by the runtime's [CanonicalRuntimeContext.eventSink], and — since
 * LB-02 / A4 — [SHELL_OPERATIONS_CAPABILITY] backed by a fresh [ShOperationsAdapter] constructed
 * from the runtime context). It derives the capability values from the runtime context WITHOUT
 * handing the whole [CanonicalRuntimeContext] to a handler and without rebuilding a pipeline
 * context. Direction is contract.requiredCapabilities -> capability admission ->
 * [StepCapabilityAccess] -> handler; a handler must never receive the raw runtime context.
 *
 * Lookup is fail-closed: [get] on a capability that is not available throws rather than returning a
 * nullable/`Any?` sentinel, so a handler can only ever start once capability admission has confirmed
 * availability.
 */
open class CanonicalRuntimeCapabilityAccess(
    context: CanonicalRuntimeContext,
    // S2-A9: milestone state store for MILESTONE_OPERATIONS_CAPABILITY.
    // Always non-null in production (coordinator provides it); nullable for test/adapter flexibility.
    private val milestoneStateStore: MilestoneStateStore? = null,
    // E1.ecosystem-local-first / T1: per-run artifact index. Bound at
    // composition root; shared between the producer (core.archiveArtifacts)
    // and the consumer (core.artifact.query). When null (e.g. legacy /
    // nested-step sub-contexts) the capability stays unexposed and BOTH
    // Steps fail closed at registry-prepare-time / availability-check time.
    private val artifactIndex: ArtifactIndexCapability? = null,
    /**
     * Behaviour supplying the capabilities an OFFICIAL_PLUGIN contributes.
     *
     * A CONTRIBUTOR rather than a map, and the difference is the whole point of
     * H2b. A `Map<StepCapability, Any>` has to live somewhere, and it was
     * briefly parked on `ShOptions` to spare this class a parameter — which
     * turned a carrier of execution FACTS into a runtime service locator and
     * bought nothing but a line count. A contributor is composed at the
     * composition root, asked what it provides, and consulted at the two
     * moments that must agree; it never becomes ambient configuration.
     *
     * Core supplies the PERMISSION (`network.egress`); the plugin supplies its
     * own SEAM (`http.transport`); neither names the other's types.
     */
    private val capabilityContributor: RuntimeCapabilityContributor = RuntimeCapabilityContributor { emptyMap() },
) : StepCapabilityAccess {

    private val provided: Map<StepCapability, Any> = buildProvided(context)

    override fun available(): Set<StepCapability> = provided.keys

    @Suppress("UNCHECKED_CAST")
    open override fun <T : Any> get(key: StepCapability): T =
        provided[key] as? T
            ?: throw IllegalArgumentException("capability unavailable to this invocation: $key")

    /**
     * Exposes [NETWORK_EGRESS_CAPABILITY] only when this run is entitled to ASK.
     *
     * The runtime owns this decision and the Step declares the requirement, so a
     * pipeline that reaches for the network without `--allow-network` is rejected at
     * prepare-time by the ordinary fail-closed admission path — before any handler
     * runs and before a socket could exist.
     *
     * H6 reads `permitsAny` rather than testing the gate against its
     * implementations. A run with no network entitlement at all never sees the
     * capability, so the default is still refused at ADMISSION and not inside a
     * handler; a run with an allowlist does get the capability and is refused per
     * destination by the gate itself. Both are refusals, but they are different
     * operator facts and they arrive through different mechanisms on purpose — which
     * is also why this is a PROPERTY read and not a `when (gate)`: the runtime does
     * not know which gates exist, only that a gate can say whether the question is on
     * the table.
     */
    private fun egressPermission(
        context: CanonicalRuntimeContext,
        builder: MutableMap<StepCapability, Any>,
    ) {
        val gate = context.shOptions.networkEgress
        if (gate.permitsAny) {
            builder[NETWORK_EGRESS_CAPABILITY] = gate
        }
    }

    /**
     * Build the capability table for a given runtime context.
     *
     * [SHELL_OPERATIONS_CAPABILITY] is exposed ONLY when the runtime context can produce a
     * working [ShellOperations] (i.e. when the adapter can be constructed). The adapter binds
     * the runtime's [runId], [ShOptions], [controlDirRoot] and [EventSink] — exactly the
     * four inputs the existing durable shell substrate expects. If any of those are absent,
     * the adapter still constructs (controlDirRoot=null is the documented non-durable fallback
     * path) but the capability stays exposed so `core.sh` retain its declared require-set.
     */
    // protected (not private) so test harnesses can substitute a synthetic platform
    // observation without touching production logic; production always uses this impl.
    protected fun buildProvided(context: CanonicalRuntimeContext): Map<StepCapability, Any> {
        val builder: MutableMap<StepCapability, Any> = mutableMapOf(
            EVENT_SINK_CAPABILITY to context.eventSink,
        )
        // The table is assembled in a fixed order and the order IS a property:
        // `available()` hands out the key set, so insertion order is observable.
        // Each binding below therefore lands in the sequence its anchor implies —
        // permission, then plugin seams, then what the run IS, then what it can
        // reach, then what it may destroy, then what was injected. The split is
        // by anchor, not by line count, so a capability stays next to the reason
        // it is exposed at all.
        egressPermission(context, builder)
        bindContributedCapabilities(builder)
        bindProcessAndStageCapabilities(context, builder)
        bindDurableChannelCapabilities(context, builder)
        bindWorkspaceObservationCapabilities(context, builder)
        bindWorkspaceDestructionCapabilities(context, builder)
        bindWorkspaceContentCapabilities(context, builder)
        bindOptionalSeamCapabilities(context, builder)
        return builder.toMap()
    }

    /**
     * Plugin-contributed seams, so a plugin can add a capability without the
     * runtime enumerating it.
     *
     * A contribution that collides with one the runtime already granted is
     * REFUSED rather than resolved by order: a plugin that silently overwrote
     * a core capability would be a plugin changing the meaning of a Step it
     * does not own. Identity is allowed through, so a plugin contributing the
     * very same instance is not a conflict.
     */
    private fun bindContributedCapabilities(builder: MutableMap<StepCapability, Any>) {
        for ((capability, value) in capabilityContributor.capabilities()) {
            val existing = builder[capability]
            require(existing == null || existing === value) {
                "capability $capability is contributed twice: by the runtime core and by a plugin"
            }
            builder[capability] = value
        }
    }

    /**
     * What the run IS: the shell it drives, the stage it is in, the budget it
     * owes and the lane it holds. None of these depend on where durable state
     * lives, so none of them can be absent.
     */
    private fun bindProcessAndStageCapabilities(
        context: CanonicalRuntimeContext,
        builder: MutableMap<StepCapability, Any>,
    ) {
        val shellOps: ShellOperations = ShOperationsAdapter(
            runIdString = context.runId,
            opId = context.opId,
            shOptions = context.shOptions,
            controlDirRoot = context.controlDirRoot,
            eventSink = context.eventSink,
            secretPatternRegistry = context.secretPatternRegistry,
        )
        builder[SHELL_OPERATIONS_CAPABILITY] = shellOps
        // S2-A3 / G1: workspace file operations bound to the current stage identity.
        //
        // RP034-D: the adapter also receives the shared execution location, so
        // file Steps resolve against the same current directory the shell vertical
        // observes inside a `dir` scope. Previously it rebuilt its own
        // WorkspaceResolver per operation and could never see the scope, which is
        // the divergence recorded by ShellFilesystemCwdDivergenceTest.
        builder[WORKSPACE_OPERATIONS_CAPABILITY] = workspaceOperationsFor(context)
        // S2-A4 / G1: narrow stage identity (name + index) for handlers needing the current
        // stage as a default (core.emit.event StageMarkedUnstable fallback). Derived from the
        // runtime context; never exposes the context itself.
        builder[STAGE_IDENTITY_CAPABILITY] = StageIdentity(
            name = context.stageName,
            index = context.stageIndex,
        )
        // RP6-A / WU-091: the durable EXECUTION LANE, derived HERE from the
        // runtime's own operation identity — run id plus the parallel lineage the
        // OpId already carries. Deriving it in the bridge is the point: a handler
        // must not be handed the OpId and left to work out its own lane, and
        // `StepHandlerContext` is deliberately not widened to carry it.
        //
        // `core.lock` uses this to decide re-entrancy: same lane re-enters (Jenkins
        // is re-entrant per build, so a nested `lock` must not deadlock against its
        // own hold), while a SIBLING `parallel` branch is a different lane and must
        // contend — which is the exclusion the lock exists to provide.
        // RP6-A / WU-091: the projected scope budget, the same value the child
        // shell watchdog consumes. Exposed so a SUSPENDING Step (one that runs no
        // process, like core.lock) can honour the block deadline through a typed
        // capability instead of escaping the watchdog. "No budget" is a real
        // value (null), hence unconditional exposure: admission stays deterministic.
        builder[EXECUTION_BUDGET_CAPABILITY] = ExecutionBudget(context.shOptions.timeoutMs)
        builder[EXECUTION_LANE_CAPABILITY] = ExecutionLaneId.of(
            runId = context.runId,
            branchLineage = context.opId.parallelLineage,
        )
    }

    /**
     * The two capabilities a run PUBLISHES into the engine's own durable
     * territory: a lock it holds, and a question waiting to be answered.
     *
     * Both are anchored to the CONTROL ROOT — the journal db parent,
     * `--control-root` — and for the same reason: they are facts about the
     * ENGINE's durable territory, not about a workspace that is NULL under the
     * local-first Managed lease and not about a run that ends before its
     * question is answered. Conditional exposure mirrors the destructive
     * capabilities below: registered ONLY when `controlDirRoot != null`, absent
     * otherwise, so capability admission fails closed for the owning Step
     * without affecting the rest of the registry.
     */
    private fun bindDurableChannelCapabilities(
        context: CanonicalRuntimeContext,
        builder: MutableMap<StepCapability, Any>,
    ) {
        // RP6-A / WU-091 G4: the POSIX file lock coordinator for core.lock.
        //
        // The lock namespace is anchored to the CONTROL ROOT — the engine's own
        // durable territory (journal db parent, `--control-root`) — NOT to the
        // workspace and NOT to the run:
        //   - per-run would make two runs of one job never contend, which is
        //     precisely the exclusion `lock` exists to provide;
        //   - the workspace is NULL under the local-first Managed lease (no
        //     `--workspace`), so it cannot be the anchor of a capability that
        //     must work in the default mode;
        //   - the control root is stable across runs sharing one `--db` /
        //     `--control-root`, so two runs on one host contend through the OS
        //     lock while a different install (different control dir) does not.
        //
        // Conditional exposure mirrors DELETE_DIR_OPERATIONS_CAPABILITY: the
        // capability is registered ONLY when controlDirRoot != null. Absent
        // otherwise, so capability admission fails closed for core.lock without
        // affecting the rest of the registry. The hold registry inside
        // FileLockCoordinator is process-scoped, so a fresh instance per context
        // still observes every hold this JVM has taken.
        context.controlDirRoot?.let { root ->
            builder[LOCK_COORDINATION_CAPABILITY] = FileLockCoordinator(
                root.resolve("locks"),
            )
        }
        // RP6-B / WU-092 G4: the filesystem answer channel for core.input.
        //
        // Anchored to the CONTROL ROOT for the same reason the lock is, and with the
        // same conditional exposure: a question is a fact about the ENGINE's durable
        // territory (who may answer a run that lives in `--db`), not about a
        // workspace that is NULL under the Managed lease and not about a run that
        // ends before its question is answered.
        //
        // Conditional on controlDirRoot for an additional reason specific to input:
        // without an anchor there is nowhere to publish the request, and inventing a
        // temp directory would silently relocate the channel from what the operator
        // can inspect and answer. Absent capability therefore means fail-closed at
        // admission — core.input refuses the run instead of asking a question into a
        // void that nobody will ever read.
        context.controlDirRoot?.let { root ->
            builder[INPUT_DECISIONS_CAPABILITY] = FileInputDecisions(
                root.resolve("inputs"),
            )
        }
    }

    /**
     * Where things ARE, as opposed to what they may do: the platform, the
     * workspace, the active `dir` scope, and the temporary resource path derived
     * from all three. Every value here is derived from the runtime context; none
     * of them is optional, and none of them opens a capability a handler could
     * use to mutate anything.
     */
    private fun bindWorkspaceObservationCapabilities(
        context: CanonicalRuntimeContext,
        builder: MutableMap<StepCapability, Any>,
    ) {
        // S2-A5 / G1: raw environmental observation for platform-classification handlers
        // (core.isUnix). The single remaining System.getProperty("os.name") read lives HERE,
        // in the bridge adapter — never inside a handler.
        builder[PLATFORM_IDENTITY_CAPABILITY] = PlatformIdentity(
            osName = System.getProperty("os.name", ""),
        )
        // S2-A6 / G1: canonical workspace observation for workspace-projection
        // handlers (core.pwd and official plugins). A nested `dir` scope carries
        // its current working directory in ShOptions.workingDirectory, so the
        // typed identity must follow that immutable scope projection rather than
        // reverting to the pipeline root. This keeps relative plugin inputs such
        // as `junitResults("build/test-results/test.xml", ".")` inside the
        // active `dir` body.
        //
        // NOTE: `WorkspaceIdentity` is a low-level observation capability. The
        // `core.pwd.tmp` Step does NOT use it directly — it goes through the
        // dedicated `TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY` port below, which
        // composes the effective workspace + canonical OpId into the deterministic
        // `tmp-pwd-<sha256(opId)>` resource path (D5–D12, S2-A6 / G3T).
        val effectiveWorkspaceRoot = context.shOptions.workingDirectory ?: context.shOptions.workspaceRoot
        builder[WORKSPACE_IDENTITY_CAPABILITY] = WorkspaceIdentity(
            workspaceRoot = effectiveWorkspaceRoot,
        )
        // RP034-C (ADR-0100): the typed execution location, registered alongside
        // the legacy WorkspaceIdentity above so consumers migrate per vertical
        // instead of all at once.
        //
        // Unlike the single-value WorkspaceIdentity, this carries BOTH
        // authorities separately: the resolved workspace for this stage (the
        // lease root) and the active `dir` scope projection (the cwd). The
        // legacy value above cannot express that difference — it collapses the
        // two into one Path, which is why a handler asking for the security
        // boundary could receive the current directory.
        //
        // This is a pure binding: it introduces no new behaviour and changes no
        // value the legacy consumers already observe.
        builder[EXECUTION_LOCATION_CAPABILITY] = executionLocationFor(context)
        // S2-A6 / G3T (post-correction): the ONLY capability consumed by
        // CorePwdTmpStep.handler. The adapter binds the runtime's
        // [runIdString], [OpId], [ShOptions] (workspaceRoot), [EventSink] —
        // exactly the inputs needed to derive the deterministic tmp path and
        // emit the canonical `PwdResolved` event. The handler does NOT see
        // these inputs directly; it reaches the typed seam, which mirrors the
        // `core.sh → ShellOperations` pattern.
        val tmpOps: TemporaryWorkspaceOperations = TemporaryWorkspaceOperationsAdapter(
            runIdString = context.runId,
            opId = context.opId,
            workspaceRoot = effectiveWorkspaceRoot,
            eventSink = context.eventSink,
        )
        builder[TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY] = tmpOps
    }

    /**
     * What the run may DESTROY: the stage workspace itself (`deleteDir`) and its
     * contents (`cleanWs`). They share an anchor, a conditional exposure, and a
     * security story, so they live together: both are gated by the typed lease
     * through [executionLocationFor] rather than by the VCS-marker heuristic
     * that RP034-I retires, because a user-owned root may not be destroyed on the
     * strength of a dotfile.
     */
    private fun bindWorkspaceDestructionCapabilities(
        context: CanonicalRuntimeContext,
        builder: MutableMap<StepCapability, Any>,
    ) {
        // S2-A7 / G3-fix: deleteDir operations for core.deleteDir.
        // The adapter binds the runtime's [runIdString], [StageIdentity], [stepIndex],
        // [controlDirRoot], and [EventSink] — exactly the inputs needed to resolve the
        // workspace, execute deletion, and emit the canonical `DirDeleted` event.
        //
        // Conditional exposure: the capability is registered ONLY when controlDirRoot != null.
        // If absent, capability admission fails closed for core.deleteDir and the rest of
        // the registry is unaffected.
        context.controlDirRoot?.let { root ->
            val deleteOps: DeleteDirOperations = DeleteDirOperationsAdapter(
                runIdString = context.runId,
                stageIdentity = StageIdentity(
                    name = context.stageName,
                    index = context.stageIndex,
                ),
                stepIndex = context.stepIndex,
                controlDirRoot = root,
                eventSink = context.eventSink,
                workspaceBase = context.workspaceBase,
                // RP034-G (ADR-0102): the typed lease decides whether a
                // user-owned root may be destroyed, replacing the VCS-marker
                // heuristic that RP034-I retires.
                executionLocation = executionLocationFor(context),
            )
            builder[DELETE_DIR_OPERATIONS_CAPABILITY] = deleteOps
        }
        // LFC-2E1-S2-A10 / G1: cleanWs operations for core.cleanWs (registry candidate).
        // The adapter binds the runtime's [runIdString], [StageIdentity], [stepIndex],
        // [controlDirRoot], and [EventSink] — exactly the inputs needed to resolve the
        // workspace, execute the cleanup via the existing CleanWsExecutor SDK substrate,
        // and emit the canonical `WsCleaned` event (single emission authority).
        //
        // Conditional exposure: the capability is registered ONLY when controlDirRoot != null.
        // If absent, capability admission fails closed for core.cleanWs and the rest of
        // the registry is unaffected.
        context.controlDirRoot?.let { root ->
            val cleanWsOps: CleanWsOperations = CleanWsOperationsAdapter(
                runIdString = context.runId,
                stageIdentity = StageIdentity(
                    name = context.stageName,
                    index = context.stageIndex,
                ),
                stepIndex = context.stepIndex,
                controlDirRoot = root,
                eventSink = context.eventSink,
                workspaceBase = context.workspaceBase,
                // RP034-G (ADR-0102): the typed lease decides whether a
                // user-owned root may be destroyed, replacing the VCS-marker
                // heuristic that RP034-I retires.
                executionLocation = executionLocationFor(context),
            )
            builder[CLEAN_WS_OPERATIONS_CAPABILITY] = cleanWsOps
        }
    }

    /**
     * What the run may move rather than destroy: the stage archive, the
     * stash/unstash seam, and the published HTML report.
     *
     * Same anchor and same conditional exposure as the destructive pair, for the
     * same reason — they resolve a source and a destination against the shared
     * [executionLocationFor], so a `dir(...)` scope narrows what a pattern can
     * match instead of being bypassed by the adapter.
     */
    private fun bindWorkspaceContentCapabilities(
        context: CanonicalRuntimeContext,
        builder: MutableMap<StepCapability, Any>,
    ) {
        // LFC-2E1 S2-B10 / G1: archiveArtifacts operations for core.archiveArtifacts.
        // The adapter binds runId, StageIdentity, controlDirRoot and the EventSink —
        // exactly the inputs needed to glob the stage workspace via the certified
        // AntStyleGlob substrate, copy matched files into the artefacts retention
        // directory, and emit ArtifactArchived / ArtifactArchiveFailed.
        //
        // Conditional exposure mirrors DELETE_DIR_OPERATIONS_CAPABILITY: registered
        // ONLY when controlDirRoot != null; absent otherwise so capability admission
        // fails closed for core.archiveArtifacts without affecting the rest of the
        // registry.
        context.controlDirRoot?.let { root ->
            val archiveOps: ArchiveArtifactsOperations = ArchiveArtifactsOperationsAdapter(
                runIdString = context.runId,
                stageIdentity = StageIdentity(
                    name = context.stageName,
                    index = context.stageIndex,
                ),
                controlDirRoot = root,
                eventSink = context.eventSink,
                workspaceBase = context.workspaceBase,
                // RP034-F: the archive source follows the shared location, so a
                // `dir(...)` scope narrows what the pattern can match.
                executionLocation = executionLocationFor(context),
            )
            builder[ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY] = archiveOps
        }
        // WU-LPR-089: stash/unstash capability. Same conditional exposure as
        // ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY — bound ONLY when controlDirRoot
        // is supplied (production path). Absent otherwise so the registry boundary
        // fails closed at admission. Both CoreStashStep and CoreUnstashStep share
        // the same STASH_OPERATIONS_CAPABILITY seam.
        context.controlDirRoot?.let { root ->
            val stashOps: StashOperations = StashOperationsAdapter(
                runIdString = context.runId,
                stageIdentity = StageIdentity(
                    name = context.stageName,
                    index = context.stageIndex,
                ),
                controlDirRoot = root,
                eventSink = context.eventSink,
                workspaceBase = context.workspaceBase,
                // RP034-E: stash source and unstash target follow the same
                // location every other workspace-aware Step reads.
                executionLocation = executionLocationFor(context),
            )
            builder[STASH_OPERATIONS_CAPABILITY] = stashOps
        }
        // WU-LPR-090: core.publishHTML capability. Same conditional exposure as
        // STASH_OPERATIONS_CAPABILITY — bound ONLY when controlDirRoot is supplied
        // (production path). Absent otherwise so the registry boundary fails
        // closed at admission. CorePublishHtmlStep shares the same
        // PUBLISH_HTML_OPERATIONS_CAPABILITY seam.
        context.controlDirRoot?.let { root ->
            val publishHtmlOps: PublishHtmlOperations = PublishHtmlOperationsAdapter(
                runIdString = context.runId,
                stageIdentity = StageIdentity(
                    name = context.stageName,
                    index = context.stageIndex,
                ),
                controlDirRoot = root,
                eventSink = context.eventSink,
                workspaceBase = context.workspaceBase,
                // RP034-F: reportDir follows the shared location too.
                executionLocation = executionLocationFor(context),
            )
            builder[PUBLISH_HTML_OPERATIONS_CAPABILITY] = publishHtmlOps
        }
    }

    /**
     * The capabilities that are INJECTED rather than derived: the milestone
     * store, the artifact index, and the two body seams.
     *
     * They share one property that the rest of the table does not have — their
     * value comes from a collaborator chosen at composition, not from the
     * runtime context — and one consequence: each is exposed only when the
     * collaborator is present, so an absent seam fails closed at capability
     * admission rather than surfacing as a null a handler has to interpret.
     */
    private fun bindOptionalSeamCapabilities(
        context: CanonicalRuntimeContext,
        builder: MutableMap<StepCapability, Any>,
    ) {
        // S2-A9 spike: milestone state operations (core.milestone). The store is optional
        // so existing call-sites that don't bind it see no change; when bound, the
        // MILESTONE_OPERATIONS_CAPABILITY is populated with a MilestoneOperationsAdapter
        // backed by the run-scoped store. The store lifetime is the coordinator lifetime,
        // NOT per handler invocation — mirroring the legacy dispatcher's per-run semantics.
        milestoneStateStore?.let { store ->
            val milestoneOps: MilestoneOperations = MilestoneOperationsAdapter(store)
            builder[MILESTONE_OPERATIONS_CAPABILITY] = milestoneOps
        }
        // E1.ecosystem-local-first / T1: per-run artifact index for the
        // core.archiveArtifacts → core.artifact.query bridge. Bound at
        // composition root; the same instance is shared between the
        // producer and the consumer so a successful archive is visible
        // to the query Step within the same run.
        //
        // When null, the capability stays unexposed: archive with
        // name=...  returns a typed SCRIPT failure, and artifactQuery
        // returns typed USER failure (NotFound). Both fail-closed per
        // the registry boundary's contract.
        artifactIndex?.let { idx ->
            builder[ARTIFACT_INDEX_CAPABILITY] = idx
        }
        // B11 / W2: body-reentry seam (ADR-0073 / ADR-0081 D1).
        // BODY_INVOKER_CAPABILITY is exposed ONLY when the canonical runtime context carries
        // a `bodyInvoker` adapter. The adapter is the engine-side implementation of the
        // `BodyInvoker` port and is the single reentry point for any future block-step
        // handler that declares the capability in its StepContract. A handler that asks for
        // it without the adapter present fails closed at capability admission, exactly like
        // every other capability in this bridge.
        context.bodyInvoker?.let { adapter ->
            builder[BODY_INVOKER_CAPABILITY] = adapter
        }
        // WU-RP-035: the public body-reentry seam for a Step that declared
        // BodyExecutionOwner.HANDLER_CONTINUATION (ADR-0081 as amended). Bound ONLY when the
        // canonical runtime context carries a body-already-bound continuation, and never as a
        // pair with BODY_INVOKER_CAPABILITY: one value, already bound, so a handler cannot be
        // handed an invoker without a body identity or an identity without an invoker.
        //
        // A handler that declares the capability for a Step whose owner is not
        // HANDLER_CONTINUATION never sees it here, and is rejected earlier by the owner /
        // capability coherence check; a HANDLER_CONTINUATION handler that runs without this
        // binding never runs at all.
        context.bodyContinuation?.let { continuation ->
            builder[BODY_CONTINUATION_CAPABILITY] = continuation
        }

        // P3-C / S6.4: the plugin event seam, bound ONLY when the composition actually produced a
        // registry. Null is the honest signal that this run has no plugin-contributed event kinds
        // at all, and a Step declaring the capability is then refused at admission by the ordinary
        // fail-closed path — never handed a seam that silently drops its observations.
        context.pluginEventEmitter?.let { emitter ->
            builder[PLUGIN_EVENT_EMISSION_CAPABILITY] =
                PluginEventEmissionAdapter(emitter, context.runId)
        }
    }

    /**
     * Derives the typed [ExecutionLocation] for this invocation
     * (RP034-C / ADR-0100).
     *
     * Kept out of [buildProvided] so the bridge stays readable and so the
     * derivation has one name a test can reason about. It is a pure function of
     * the context: the same context always yields the same location, and it
     * reads no ambient process state.
     */
    private fun executionLocationFor(
        context: CanonicalRuntimeContext,
    ): ExecutionLocation = ShOptionsExecutionLocationAdapter.from(
        workspaceRoot = context.shOptions.workspaceRoot,
        scopedWorkingDirectory = context.shOptions.workingDirectory,
        fallbackRoot = context.shOptions.workspaceRoot
            ?: Path.of("").toAbsolutePath().normalize(),
        // RP034-Id: ownership decided at the CLI boundary (ADR-0101) and carried
        // on the transport. Without this the bridge re-derived `Managed` for every
        // run and the ADR-0102 guard on a user-owned root never fired.
        ownership = context.shOptions.workspaceOwnership,
    )

    /**
     * Binds the file-Step vertical to this stage and to the shared execution
     * location (RP034-D / ADR-0100).
     *
     * [executionLocationFor] is passed explicitly rather than recomputed inside
     * the adapter, so the file vertical and the capability projection are
     * guaranteed to describe the same location for a given step.
     */
    private fun workspaceOperationsFor(
        context: CanonicalRuntimeContext,
    ): WorkspaceOperations = WorkspaceOperationsAdapter(
        stageName = context.stageName,
        stageIndex = context.stageIndex,
        controlDirRoot = context.controlDirRoot,
        eventSink = context.eventSink,
        runId = context.runId,
        workspaceBase = context.workspaceBase,
        executionLocation = executionLocationFor(context),
    )
}
