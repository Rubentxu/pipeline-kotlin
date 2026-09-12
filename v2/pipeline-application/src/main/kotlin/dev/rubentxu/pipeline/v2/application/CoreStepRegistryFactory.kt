package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry

/**
 * Single production authority for the core [StepDefinition]s registered into a [StepRegistry]
 * (B1.2c3-S2.2).
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

    /** A fresh [StepRegistry] seeded with every registered core [StepDefinition]. */
    fun registry(): InMemoryStepRegistry = InMemoryStepRegistry().apply {
        CoreEchoStep.registerInto(this)
        // LB-02 / A4 WU2: register CoreShellStep alongside CoreEchoStep.
        // After the structural flip (WU3, removal of "core.sh" from LEGACY_PLUGIN_IDS),
        // CoreShellStep is the production routing authority for `sh(...)` invocations.
        // The classifier `StructuralFamilyResolver.classify("core.sh", registry)` returns
        // `Registry` because the key is no longer in the legacy set; this composes the
        // typed input/output codecs and the SHELL_OPERATIONS_CAPABILITY declaration.
        // Single composition authority — no per-call-site wiring.
        CoreShellStep.registerInto(this)
        // LFC-2E1-S2-A1 / G2: register CoreErrorStep alongside CoreEchoStep and CoreShellStep.
        // IMPORTANT: this is REGISTRATION only, not a production routing flip.
        // While "core.error" remains in LEGACY_PLUGIN_IDS, StructuralFamilyResolver.classify
        // returns StructuralStepFamily.LegacyCore for this key (legacy membership wins per
        // the resolver contract). Production behavior is UNCHANGED at G2. The flip to
        // Registry family is G5, after G3 parity proof and G4 architecture fitness.
        // Counter invariant at G2: LEGACY_PLUGIN_IDS == 12, metadata rows == 12,
        // dispatcher classes == 12. The legacy decoder/dispatcher/metadata row are not
        // mutated by this edit — they remain the production authority until G6.
        CoreErrorStep.registerInto(this)
        // LFC-2E1-S2-A2 / G1: candidate registration only. `core.sleep` remains in
        // LEGACY_PLUGIN_IDS, so StructuralFamilyResolver's legacy-membership-wins rule
        // keeps LegacyCore as the canonical production authority. No legacy decoder,
        // metadata, dispatcher, or catalogue entry changes in this gate.
        CoreSleepStep.registerInto(this)
        // LFC-2E1-S2-A3 / G1: candidate registration only. `core.file.writeFile` remains in
        // LEGACY_PLUGIN_IDS, so StructuralFamilyResolver's legacy-membership-wins rule
        // keeps LegacyCore as the canonical production authority. No legacy decoder,
        // metadata, dispatcher, or catalogue entry changes in this gate.
        CoreWriteFileStep.registerInto(this)
        // LFC-2E1-S2-A4 / G1: candidate registration only. `core.emit.event` remains in
        // LEGACY_PLUGIN_IDS, so StructuralFamilyResolver's legacy-membership-wins rule
        // keeps LegacyCore as the canonical production authority. No legacy decoder,
        // metadata, dispatcher, or catalogue entry changes in this gate.
        CoreEmitEventStep.registerInto(this)
        // LFC-2E1-S2-A5 / G1: candidate registration only. `core.isUnix` remains in
        // LEGACY_PLUGIN_IDS, so StructuralFamilyResolver's legacy-membership-wins rule
        // keeps LegacyCore as the canonical production authority. No legacy decoder,
        // metadata, dispatcher, or catalogue entry changes in this gate. The candidate
        // exposes TYPED_RUNTIME_OUTPUT (CANDIDATE_ARCHITECTURAL_DELTA, NOT YET APPROVED)
        // and classifies with PATH_B verbatim; the canonical-policy decision is G2.
        CoreIsUnixStep.registerInto(this)
        // LFC-2E1-S2-A6 / G1: candidate registration only. `core.pwd` remains in
        // LEGACY_PLUGIN_IDS, so StructuralFamilyResolver's legacy-membership-wins rule
        // keeps LegacyCore as the canonical production authority. No legacy decoder,
        // metadata, dispatcher, or catalogue entry changes in this gate.
        //
        // The candidate is SCOPED to `pwd(tmp=false)` only (decision D3 frozen at G2).
        // The input codec rejects `tmp=true` at decode time (PWD_TMP_TRUE_DISPOSITION);
        // therefore the StepKey authority CANNOT be flipped for `core.pwd` until a
        // separate disposition lands for `tmp=true`. See S2_A6_CORE_PWD_G0.
        //
        // Capability: requires the new WORKSPACE_IDENTITY_CAPABILITY (S2-A6 / G1),
        // mirroring the PLATFORM_IDENTITY pattern from core.isUnix — narrow
        // observation of the canonical stage workspace path, derived by the bridge
        // from context.shOptions.workspaceRoot.
        CorePwdStep.registerInto(this)
        // LFC-2E1-S2-A6 / G3T: register CorePwdTmpStep as the new deterministic
        // tmp-workspace Step (`core.pwd.tmp`). This is the INTERNAL contract for
        // `pwd(tmp=true)`; the public DSL (`PipelineDsl.pwd(tmp=true)`) is NOT
        // rewired yet — that lower-binding is G3R (LFC-2R runtime-return consumer).
        //
        // Production routing:
        //   - `core.pwd`     stays in LEGACY_PLUGIN_IDS → LegacyCore (post-G1 invariant)
        //   - `core.pwd.tmp` is NOT in LEGACY_PLUGIN_IDS → Registry from the moment
        //                    of registration (see StructuralFamilyResolver.classify
        //                    rule: legacy membership wins OR registry resolves the
        //                    key, but `core.pwd.tmp` is never legacy-routed).
        //
        // The candidate uses the new DURABLE_OPERATION_IDENTITY_CAPABILITY to derive
        // the deterministic temp path from the canonical OpId.format string — NO
        // timestamp, NO random. Same OpId ⇒ same path; distinct OpId ⇒ distinct path.
        CorePwdTmpStep.registerInto(this)
        // LFC-2E1-S2-A8 / G1: candidate registration only. `core.waitUntil` remains in
        // LEGACY_PLUGIN_IDS, so StructuralFamilyResolver's legacy-membership-wins rule
        // keeps LegacyCore as the canonical production authority. No legacy decoder,
        // metadata, dispatcher, or catalogue entry changes in this gate.
        //
        // waitUntil is a Block Step with a condition body. The registry candidate
        // emits typed events (WaitUntilPolled / WaitUntilCompleted) but the actual
        // condition evaluation requires BodyInvoker (ADR-0073). This G1 candidate
        // follows the stub pattern from the legacy dispatcher.
        CoreWaitUntilStep.registerInto(this)
        // LFC-2E1-S2-A7 / G3-fix: candidate registration only. `core.deleteDir` remains in
        // LEGACY_PLUGIN_IDS, so StructuralFamilyResolver's legacy-membership-wins rule
        // keeps LegacyCore as the canonical production authority. No legacy decoder,
        // metadata, dispatcher, or catalogue entry changes in this gate.
        //
        // Capability: DELETE_DIR_OPERATIONS_CAPABILITY — conditionally exposed only when
        // controlDirRoot != null (fail-closed admission if absent).
        //
        // Effects: WRITES_WORKSPACE (matches legacy metadata)
        // ReplayPolicy: MEMOIZED (matches legacy metadata, idempotent via .deleted marker)
        CoreDeleteDirStep.registerInto(this)
        // LFC-2E1-S2-A9 / G1: candidate registration only. `core.milestone` remains in
        // LEGACY_PLUGIN_IDS, so StructuralFamilyResolver's legacy-membership-wins rule
        // keeps LegacyCore as the canonical production authority. No legacy decoder,
        // metadata, dispatcher, or catalogue entry changes in this gate.
        //
        // Capability: requires EVENT_SINK_CAPABILITY only — the handler reaches the event
        // sink ONLY through the declared capability (capability-routed handler discipline,
        // LB-02 / G3-A4.2).
        //
        // Semantics: strictly increasing ordinal -> MilestoneReached + Success;
        // non-increasing ordinal -> MilestoneAborted + Unstable (record-only, per ML-R9 T-09
        // and ADR-0046 §ML — local single-run model, no cross-build coordination).
        //
        // State: MilestoneStateStore is run-scoped (coordinator-owned); see
        // CanonicalDurableRunCoordinator wiring and S2_A9_MILESTONE_DURABILITY_SPIKE.md.
        CoreMilestoneStep.registerInto(this)
    }
}
