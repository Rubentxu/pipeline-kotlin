package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder

/**
 * Single production authority for the core [StepDefinition]s registered into a [StepRegistry].
 *
 * The registry is the ONE composition point for Step semantics (closed structure, open Steps). Core
 * registers its public [StepDefinition]s through the SAME mechanism an external plugin uses
 * ([CoreEchoStep] today, more core Steps as they migrate off their legacy dispatch). The coordinator
 * and dispatcher never hardcode which definitions are registered here and never mutate it.
 *
 * Compose external plugin definitions by registering them onto the returned mutable registry at the
 * composition root:
 * ```
 * val registry = CoreStepRegistryFactory.registry()
 * externalPlugin.registerInto(registry)
 * CanonicalDurableRunCoordinator(..., stepRegistry = registry)
 * ```
 *
 * Deterministic and fresh per call; no global/singleton registry. The caller owns the returned
 * registry's lifecycle.
 */
object CoreStepRegistryFactory {

    /**
     * A fresh BUILDER seeded with every registered core Step.
     *
     * S6/F: the composition root composes and the runtime reads. Exposing a builder here rather
     * than a finished registry is what lets external plugins join the SAME composition before
     * anything is frozen — there is no post-hoc mutation path for them to take, because none
     * exists.
     */
    fun builder(): StepRegistryBuilder = StepRegistryBuilder().apply {
        CoreEchoStep.registerInto(this)
        // Register CoreShellStep alongside CoreEchoStep. CoreShellStep is the production
        // routing authority for `sh(...)` invocations; the classifier
        // `StructuralFamilyResolver.classify("core.sh", registry)` returns `Registry` because
        // the key is no longer in the legacy set. This is the single composition site for the
        // typed input/output codecs and the SHELL_OPERATIONS_CAPABILITY declaration.
        CoreShellStep.registerInto(this)
        // Register CoreErrorStep alongside CoreEchoStep and CoreShellStep. The classifier
        // returns StructuralStepFamily.LegacyCore for this key while "core.error" remains in
        // LEGACY_PLUGIN_IDS (legacy membership wins per the resolver contract).
        CoreErrorStep.registerInto(this)
        // Register CoreSleepStep. Classifier continues to route it through LegacyCore while it
        // remains in LEGACY_PLUGIN_IDS.
        CoreSleepStep.registerInto(this)
        // Register CoreWriteFileStep. Classifier continues to route it through LegacyCore while
        // it remains in LEGACY_PLUGIN_IDS.
        CoreWriteFileStep.registerInto(this)
        // Register CoreReadFileStep + CoreFileExistsStep through the same open registry seam.
        CoreReadFileStep.registerInto(this)
        CoreFileExistsStep.registerInto(this)
        // Register CoreArchiveArtifactsStep; this is the production authority because
        // `core.archiveArtifacts` is no longer in LEGACY_PLUGIN_IDS, so the classifier routes
        // the key through Registry.
        CoreArchiveArtifactsStep.registerInto(this)
        // Register CoreArtifactQueryStep — the bridge step that lets a pipeline ask
        // "where is the JAR named 'app'?" after `core.archiveArtifacts(name='app', ...)`. It
        // reads the ARTIFACT_INDEX_CAPABILITY (admitted via InMemoryArtifactIndex at the
        // composition root). The key is not in LEGACY_PLUGIN_IDS, so the classifier returns
        // Registry from the moment of registration.
        CoreArtifactQueryStep.registerInto(this)
        // Register CoreEmitEventStep. Classifier routes it through LegacyCore while the key
        // remains in LEGACY_PLUGIN_IDS.
        CoreEmitEventStep.registerInto(this)
        // Register CoreIsUnixStep. Classifier routes it through LegacyCore while the key
        // remains in LEGACY_PLUGIN_IDS. The candidate exposes TYPED_RUNTIME_OUTPUT and the
        // PATH_B platform signal verbatim.
        CoreIsUnixStep.registerInto(this)
        // Register CorePwdStep. The candidate is scoped to `pwd(tmp=false)` only; the input
        // codec rejects `tmp=true` at decode time, so the StepKey authority cannot be flipped
        // for `core.pwd` until a separate disposition lands for `tmp=true`. It requires
        // WORKSPACE_IDENTITY_CAPABILITY, mirroring the PLATFORM_IDENTITY pattern from
        // core.isUnix — a narrow observation of the canonical stage workspace path, derived by
        // the bridge from context.shOptions.workspaceRoot.
        CorePwdStep.registerInto(this)
        // Register CorePwdTmpStep as the deterministic tmp-workspace step (`core.pwd.tmp`).
        // The key is not in LEGACY_PLUGIN_IDS, so the classifier returns Registry from the
        // moment of registration; the legacy path never sees this key. The step derives the
        // path from the canonical OpId.format via the deterministic identity capability — no
        // timestamp, no random. Same OpId lands on the same path; distinct OpId lands on a
        // distinct path.
        CorePwdTmpStep.registerInto(this)
        // Register CoreDeleteDirStep. Requires DELETE_DIR_OPERATIONS_CAPABILITY, conditionally
        // exposed only when controlDirRoot != null (fail-closed admission if absent).
        // Effects: WRITES_WORKSPACE. ReplayPolicy: MEMOIZED (idempotent via the .deleted
        // marker).
        CoreDeleteDirStep.registerInto(this)
        // Register CoreCleanWsStep. Requires CLEAN_WS_OPERATIONS_CAPABILITY, conditionally
        // exposed only when controlDirRoot != null (fail-closed admission if absent). The
        // adapter is the single WsCleaned emission authority over the existing CleanWsExecutor
        // SDK substrate. Effects: WRITES_WORKSPACE. ReplayPolicy: MEMOIZED (idempotent via the
        // .cleaned marker). RecoveryPolicy: None.
        CoreCleanWsStep.registerInto(this)
        // Register CoreMilestoneStep. Requires EVENT_SINK_CAPABILITY only (handler reaches the
        // event sink through the declared capability). Semantics: a strictly increasing ordinal
        // emits MilestoneReached + Success; a non-increasing ordinal emits
        // MilestoneAborted + Unstable (record-only — local single-run model, no cross-build
        // coordination). MilestoneStateStore is run-scoped (coordinator-owned); see
        // CanonicalDurableRunCoordinator wiring.
        CoreMilestoneStep.registerInto(this)
        // Register CoreWaitUntilStep. The descriptor declares
        // BodyExecutionPolicy.Retrying(waitUntil = WaitUntilShape()), so the canonical body
        // engine dispatches the polling loop from the declaration, not from a per-StepKey
        // branch in the coordinator.
        CoreWaitUntilStep.registerInto(this)
        // Register CoreStashStep and CoreUnstashStep. Both reach the typed
        // STASH_OPERATIONS_CAPABILITY seam through the adapter wired at composition time; the
        // registry seam is the only path that produces a StashCreated/StashRestored/StashFailed
        // event.
        CoreStashStep.registerInto(this)
        CoreUnstashStep.registerInto(this)
        // Register CorePublishHtmlStep. Reaches the typed PUBLISH_HTML_OPERATIONS_CAPABILITY
        // seam through the adapter wired at composition time; the registry seam is the only
        // path that produces HtmlReportPublished/HtmlReportSkipped/HtmlReportFailed events.
        CorePublishHtmlStep.registerInto(this)
        // Register CoreLockStep. The key is not in LEGACY_PLUGIN_IDS (this step was born
        // behind the registry), so the classifier returns Registry from the moment of
        // registration — there is no legacy decoder/dispatcher row to retire. Admission is
        // fail-closed on three capabilities: LOCK_COORDINATION_CAPABILITY (exposed only when
        // the runtime carries a control-dir anchor, see the capability bridge),
        // BODY_CONTINUATION_CAPABILITY (only when a body is bound) and
        // EXECUTION_LANE_CAPABILITY (always bridge-derived). The wire payload is produced by
        // CoreLockWireCodec, the single wire authority shared with the DSL compiler.
        CoreLockStep.registerInto(this)
        // Register CoreInputStep. The key is not in LEGACY_PLUGIN_IDS, so the classifier
        // returns Registry from the moment of registration. Admission is fail-closed on
        // INPUT_DECISIONS_CAPABILITY (exposed only when the runtime carries a control-dir
        // anchor — without one there is nowhere to publish a question, so core.input must
        // refuse rather than ask into a void) and BODY_CONTINUATION_CAPABILITY (only when a
        // body is bound; the body is the conditional part and runs ONLY on Proceed). The wire
        // payload is produced by CoreInputWireCodec, the single wire authority shared with the
        // DSL compiler.
        CoreInputStep.registerInto(this)
    }

    /**
     * A finished, immutable registry carrying every core Step.
     *
     * Prefer [builder] when external plugins still have to join the composition; this shortcut
     * exists for the call sites that genuinely have nothing left to add.
     */
    fun registry(): StepRegistry = builder().build()
}
