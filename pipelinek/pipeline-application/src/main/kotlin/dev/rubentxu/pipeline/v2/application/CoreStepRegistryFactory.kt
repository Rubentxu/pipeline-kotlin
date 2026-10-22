package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder

/**
 * Single production authority for the core [StepDefinition]s registered into a [StepRegistry].
 * The registry is the ONE composition point for Step semantics (closed structure, open Steps). Core
 * registers its public [StepDefinition]s through the SAME mechanism an external plugin uses
 * ([CoreEchoStep] and every other core Step). The coordinator
 * and dispatcher never hardcode which definitions are registered here and never mutate it.
 * Compose external plugin definitions by registering them onto the returned mutable registry at the
 * composition root:
 * ```
 * val registry = CoreStepRegistryFactory.registry
 * externalPlugin.registerInto(registry)
 * CanonicalDurableRunCoordinator(..., stepRegistry = registry)
 * ```
 * Deterministic and fresh per call; no global/singleton registry. The caller owns the returned
 * registry's lifecycle.
*/
object CoreStepRegistryFactory {

    /**
     * A fresh BUILDER seeded with every registered core Step.
     * The composition root composes and the runtime reads. Exposing a builder here rather
     * than a finished registry is what lets external plugins join the SAME composition before
     * anything is frozen — there is no post-hoc mutation path for them to take, because none
     * exists.
*/
    fun builder(): StepRegistryBuilder = StepRegistryBuilder().apply {
        // Every core Step below is registry-routed. The legacy set is empty, so
        // `StructuralFamilyResolver.classify` answers `Registry` for all of them
        // as soon as a registry is injected, and the registry owns production
        // routing and production metadata. The comments that follow therefore
        // describe what each Step DECLARES -- its capability, its effects and
        // its policies -- and not a migration that is already finished.
        CoreEchoStep.registerInto(this)
        // `sh(...)`: composes the typed input/output codecs and the
        // SHELL_OPERATIONS_CAPABILITY declaration.
        CoreShellStep.registerInto(this)
        CoreErrorStep.registerInto(this)
        CoreSleepStep.registerInto(this)
        CoreWriteFileStep.registerInto(this)
        CoreReadFileStep.registerInto(this)
        CoreFileExistsStep.registerInto(this)
        // `core.archiveArtifacts`: the registry is the canonical production
        // authority for the key.
        CoreArchiveArtifactsStep.registerInto(this)
        // `core.artifact.query` is the bridge Step that lets the pipeline ask
        // "where is the JAR named 'app'?" after `core.archiveArtifacts(name='app', ...)`.
        // It reads the ARTIFACT_INDEX_CAPABILITY (admitted via InMemoryArtifactIndex
        // at the composition root).
        CoreArtifactQueryStep.registerInto(this)
        CoreEmitEventStep.registerInto(this)
        // `core.isUnix`: exposes TYPED_RUNTIME_OUTPUT and classifies with PATH_B
        // verbatim.
        CoreIsUnixStep.registerInto(this)
        // `core.pwd` is scoped to `pwd(tmp=false)`: its input codec rejects
        // `tmp=true` at decode time (PWD_TMP_TRUE_DISPOSITION), so this StepKey
        // cannot cover `tmp=true` while the legacy path minted a
        // `tmp-pwd-<timestamp>` directory. The DSL routes `pwd(tmp=true)` to
        // `core.pwd.tmp` instead.
        // Capability: requires the new WORKSPACE_IDENTITY_CAPABILITY, mirroring the
        // PLATFORM_IDENTITY pattern from core.isUnix -- a narrow observation of the
        // canonical stage workspace path, derived by the bridge from
        // context.shOptions.workspaceRoot.
        CorePwdStep.registerInto(this)
        // `core.pwd.tmp` is the deterministic tmp-workspace Step, and the internal
        // contract for `pwd(tmp=true)`.
        // It uses the DURABLE_OPERATION_IDENTITY_CAPABILITY to derive the
        // deterministic temp path from the canonical OpId.format string -- no
        // timestamp, no random. Same OpId => same path; distinct OpId => distinct path.
        CorePwdTmpStep.registerInto(this)
        // `core.deleteDir`: capability DELETE_DIR_OPERATIONS_CAPABILITY, exposed only when
        // controlDirRoot != null (fail-closed admission if absent).
        // Effects: WRITES_WORKSPACE. ReplayPolicy: MEMOIZED (idempotent.deleted marker).
        CoreDeleteDirStep.registerInto(this)
        // `core.cleanWs`: capability CLEAN_WS_OPERATIONS_CAPABILITY, exposed only when
        // controlDirRoot != null (fail-closed admission if absent). The adapter is the
        // single WsCleaned emission authority over the existing CleanWsExecutor SDK
        // substrate.
        // Effects: WRITES_WORKSPACE. ReplayPolicy: MEMOIZED (idempotent.cleaned marker).
        // RecoveryPolicy: None (prep inventory).
        CoreCleanWsStep.registerInto(this)
        // `core.milestone`: requires EVENT_SINK_CAPABILITY only -- the handler reaches the
        // event sink ONLY through the declared capability.
        // Semantics: strictly increasing ordinal -> MilestoneReached + Success;
        // non-increasing ordinal -> MilestoneAborted + Unstable (record-only,
        // single-run model, no cross-build coordination).
        // State: MilestoneStateStore is run-scoped (coordinator-owned).
        CoreMilestoneStep.registerInto(this)
        // `core.waitUntil`: the descriptor declares
        // BodyExecutionPolicy.Retrying(waitUntil = WaitUntilShape), so the canonical body
        // engine dispatches the polling loop from the declaration, not from a per-StepKey
        // branch in the coordinator.
        CoreWaitUntilStep.registerInto(this)
        // `core.stash` / `core.unstash`: both reach the typed STASH_OPERATIONS_CAPABILITY
        // seam through the adapter wired at composition time; the registry seam is the only
        // path that produces a StashCreated/StashRestored/StashFailed event.
        CoreStashStep.registerInto(this)
        CoreUnstashStep.registerInto(this)
        // `core.publishHtml`: reaches the typed PUBLISH_HTML_OPERATIONS_CAPABILITY seam
        // through the adapter wired at composition time; the registry seam is the only path
        // that produces HtmlReportPublished/HtmlReportSkipped/HtmlReportFailed.
        CorePublishHtmlStep.registerInto(this)
        // `core.lock`: admission is fail-closed on three capabilities:
        // LOCK_COORDINATION_CAPABILITY (exposed only when the runtime carries a control-dir
        // anchor, see the capability bridge), BODY_CONTINUATION_CAPABILITY (only when a body
        // is bound) and EXECUTION_LANE_CAPABILITY (always bridge-derived). The wire payload is
        // produced by CoreLockWireCodec, the single wire authority shared with the DSL
        // compiler.
        CoreLockStep.registerInto(this)
        // `core.input`: admission is fail-closed on INPUT_DECISIONS_CAPABILITY (exposed only
        // when the runtime carries a control-dir anchor -- without one there is nowhere to
        // publish a question, and core.input must refuse rather than ask into a void) and
        // BODY_CONTINUATION_CAPABILITY (only when a body is bound; the body is the
        // conditional part, it runs ONLY on Proceed). The wire payload is produced by
        // CoreInputWireCodec, the single wire authority shared with the DSL compiler.
        CoreInputStep.registerInto(this)
    }

    /**
     * A finished, immutable registry carrying every core Step.
     * Prefer [builder] when external plugins still have to join the composition; this shortcut
     * exists for the call sites that genuinely have nothing left to add.
*/
    fun registry(): StepRegistry = builder().build()
}
