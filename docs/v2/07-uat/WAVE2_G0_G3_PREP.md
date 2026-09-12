# WAVE-2 G0–G3 PREP — `core.cleanWs`, `core.load`, `core.archiveArtifacts`

Status: PREPARATION ONLY (read-only inventory + proposals). No production change,
no `LEGACY_PLUGIN_IDS` mutation, no counter change. Prepared on worktree
`pipeline-wave2-prep` @ `78a26cc4` (branch `cycle/lfc2-e1-wave2-prep`), 2026-09-12.

Reference patterns (CERTIFIED): atomic → `CoreEchoStep.kt`, effectful/recoverable →
`CoreShellStep.kt` (both in `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/`).

Shared burn-down protocol: G0..G8 per AGENTS.md; receipts live in this directory
(`docs/v2/07-uat/`, see `LB02_G0_BASELINE_EVIDENCE.md`, `S2_A1_CORE_ERROR_G0_AUDIT.md`
as templates).

---

## 1. `core.cleanWs`

### Legacy surface inventory

| Artifact | Location |
| --- | --- |
| `LEGACY_PLUGIN_IDS` membership | `CanonicalCoreStepDecoder.kt:109` (set literal spans 59–113) |
| Legacy command subtype | `CanonicalCoreStepCommand.CleanWs` — `CanonicalCoreStepDecoder.kt:153-158` |
| Legacy decoder branch | `CanonicalCoreStepDecoder.decode`, `CLEAN_WS_PLUGIN_ID` case — `CanonicalCoreStepDecoder.kt:213` (constant) and `:243-258` (branch) |
| Legacy metadata row | `CanonicalCoreStepMetadata.kt:24` — `StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED)` |
| Legacy dispatcher | `durable/CanonicalCleanWsNodeDispatcher.kt` (whole file; dispatch context lines 11-22) |
| `CanonicalNodeDispatcher` wiring | `durable/CanonicalNodeDispatcher.kt:28` (field), `:42` (when branch), `:61-69` (`cleanWsContext()`) |
| Runtime substrate | `pipeline-step-sdk/files/.../CleanWsExecutor.kt` (SDK executor, reusable via capability seam; tested by `pipeline-step-sdk/files/src/test/.../WorkspaceCleanupTest.kt`) |
| Compiler payload | UNCERTAIN: no explicit `is StepSpec.CleanWs` case found in `DslCompiledPipelineCompiler.encodePayload` (lines 629-670). `StepSpec.CleanWs` is declared at `pipeline-scripting-api/.../PipelineDsl.kt:499-504`; the lowering path that produces the `dsl-v1` `{"kind":"cleanWs",...}` payload needs tracing during G0 (likely the `else -> declarativeValue` fallback at `DslCompiledPipelineCompiler.kt:668` or another projection path — MUST be confirmed before G4, since the input codec must emit the byte-identical payload). |

### Classification

**Effectful (workspace-mutating), non-recoverable.** Reference pattern: closest to
`CoreShellStep`'s capability-routed discipline but with `RecoveryPolicy.None` (no
subprocess). Uses the existing `CleanWsExecutor` SDK authority — the handler must
delegate through a narrow capability, not import the executor adapter directly into
domain code.

### StepContract sketch (PROPOSAL, not implementation)

```text
Key:                core.cleanWs (PluginStepId)
Input:              CoreCleanWsInput(deleteDirs: Boolean = true, patterns: List<String> = emptyList())
Output:             CoreCleanWsOutput(deletedFiles: Int, deletedDirs: Int)  // counts already surfaced in WsCleaned events today (UatLocal011 SC-011-05)
Descriptor:         effects = [WRITES_WORKSPACE]; replayPolicy = MEMOIZED (matches legacy row, CanonicalCoreStepMetadata.kt:24);
                    recoveryPolicy = None
Required caps:      WORKSPACE_OPERATIONS_CAPABILITY ("workspaceOperations", Capabilities.kt:28) —
                    extend the WorkspaceOperations seam (or add a narrow WorkspaceCleanupOperations seam)
                    to expose CleanWsExecutor's clean operation
                    EVENT_SINK_CAPABILITY only if the handler itself emits (preferred: substrate emits
                    WsCleaned, following core.sh's "substrate is the single authority" rule)
Input codec:        dsl-v1 {"kind":"cleanWs","deleteDirs":bool,"patterns":[...]} — byte-identical to
                    today's decoder expectations (CanonicalCoreStepDecoder.kt:244-257)
```

### Existing test baseline / G0 capture needs

- `UatLocal011WorkflowControlTest.kt:284` `SC-011-05 cleanWs emits WsCleaned with correct counts` and `:579` (bare `cleanWs()`).
- `CompatibilityCorpusTest.kt:132` fixture `18-cleanWs.pipeline.kts` (corpus fixture `compatibility/18-cleanWs.pipeline.kts`).
- `CanonicalCoreStepCommandRegistryTest.kt:58,89` (legacy set membership + pluginId).
- G0 must capture: fresh run of `UatLocal011*` + `just corpus 18` on base SHA with SHA-256 logs persisted under `docs/v2/07-uat/evidence/` (rule 16 base-SHA evidence).

### Risks / blockers

1. Compiler lowering path for `cleanWs` not yet traced (see table) — the registry input codec must reproduce the exact durable payload or fingerprints diverge.
2. `CanonicalCleanWsNodeDispatcher` resolves the workspace via `WorkspaceResolver(controlDirRoot)`; the capability seam must carry equivalent workspace-resolution inputs (stage name/index) — likely via `STAGE_IDENTITY_CAPABILITY` (Capabilities.kt:44) plus a control-root-bearing seam.
3. WS_CLEANED event emission currently lives in dispatcher/executor — decide the single authority before G1 to avoid duplicate events (console/transcript rule analogue).

---

## 2. `core.load`

### Legacy surface inventory

| Artifact | Location |
| --- | --- |
| `LEGACY_PLUGIN_IDS` membership | `CanonicalCoreStepDecoder.kt:110` |
| Legacy command subtype | `CanonicalCoreStepCommand.Load` — `CanonicalCoreStepDecoder.kt:164-168` |
| Legacy decoder branch | `LOAD_PLUGIN_ID` — `CanonicalCoreStepDecoder.kt:214` (constant), `:259-266` (branch) |
| Legacy metadata row | `CanonicalCoreStepMetadata.kt:25` — `StepMetadata(setOf(Effect.EXECUTES_SUBPROCESS), ReplayPolicy.MEMOIZED)` (note: EXECUTES_SUBPROCESS despite no actual subprocess — metadata accuracy is itself a G0 finding) |
| Legacy dispatcher | `durable/CanonicalLoadNodeDispatcher.kt` (whole file; context lines 21-35; dispatch logic 42+ including workspace-escape check lines 54-68, SHA-256 + re-entrancy fingerprint cache) |
| `CanonicalNodeDispatcher` wiring | `durable/CanonicalNodeDispatcher.kt:29` (field), load branch + `loadContext()` (`:70-80`; `loadedFingerprints = mutableSetOf()` is created PER DISPATCH — see risks) |
| Compiler payload | UNCERTAIN: same gap as cleanWs — `StepSpec.Load` (`PipelineDsl.kt:629-633`) has no explicit case in `DslCompiledPipelineCompiler.encodePayload` (629-670). Must be traced in G0. |
| Coordinator coupling | `Main.kt:136` names `core.load` among steps with special handling — the dispatcher comment (`CanonicalLoadNodeDispatcher.kt:36-40`) states compilation/child-step execution is DEFERRED to coordinator level. This is the hardest migration of the three: `load` is not a closed atomic step, it injects child steps into the run. |

### Classification

**Effectful + re-entrant structural step (loads and evaluates a script).** Neither
reference pattern fits cleanly: it is closer to a Block/structural concern
(ADR-0073 body machinery) than to `core.echo`/`core.sh`. PROPOSAL: split the migration
into (a) the atomic read+fingerprint part (registry-routable, following CoreShellStep)
and (b) the script-evaluation part which re-enters the engine. The coordinator
coupling must be inventoried in G0 before any G1 work; do NOT assume the standard
golden path suffices.

### StepContract sketch (PROPOSAL — high uncertainty)

```text
Key:                core.load
Input:              CoreLoadInput(path: String)
Output:             CoreLoadOutput(path: String, sha256: String, reentrant: Boolean, loadedStepCount: Int?)
Descriptor:         effects = [EXECUTES_SUBPROCESS or a new READS_AND_EVALUATES_SCRIPT effect — resolve
                    with ADR owner]; replayPolicy = MEMOIZED (legacy row);
                    recoveryPolicy = None
Required caps:      workspace/script-read capability (file read + sha256 within workspace root — new narrow
                    seam; the workspace-escape path check at CanonicalLoadNodeDispatcher.kt:54-58 is
                    policy and MUST move into the Step, not the capability)
                    EVENT_SINK_CAPABILITY (WorkflowLoaded event)
Fingerprint cache:  loadedFingerprints must become run-scoped DURABLE state (control row discipline,
                    RETRY-D analogue) or a capability-provided run identity — the current
                    per-dispatch `mutableSetOf()` (CanonicalNodeDispatcher.kt:~77) makes the
                    re-entrancy cache effectively per-step-call, which is likely a latent bug to
                    characterize in G0. UNCERTAIN whether upstream code mutates this set elsewhere.
Input codec:        dsl-v1 {"kind":"load","path":"..."} (CanonicalCoreStepDecoder.kt:260-265)
```

### Existing test baseline / G0 capture needs

- `UatLocal011WorkflowControlTest.kt:502` (`load("${loadedScriptPath}")` in workflow-control scenario).
- Compatibility corpus: `just corpus <fixture>` — no dedicated load fixture found in `compatibility/*.pipeline.kts` (UNCERTAIN; grep for `load(` found only UatLocal011). G0 should record this coverage gap.
- `CanonicalCoreStepCommandRegistryTest.kt` (set membership).

### Risks / blockers

1. **Hardest of the three.** Coordinator-level child-step injection (dispatcher comment lines 36-40) has no golden-path precedent; may require ADR-0073 Body machinery or an explicit design decision before G1.
2. Re-entrancy cache lifetime bug candidate (per-dispatch mutable set) — characterize in G0; fixing it is spine-level, NOT part of the Step migration (AGENTS.md MUST-NOT: "modify journal/replay semantics as part of a normal Step migration").
3. Metadata row claims EXECUTES_SUBPROCESS but the legacy dispatcher performs no subprocess — descriptor design must reconcile this with the effects ADT honestly.
4. Script evaluation implies scripting-engine capability — confirm whether `load` in the canonical path actually compiles a script or only records it (UNCERTAIN; the dispatcher only reads + fingerprints).

---

## 3. `core.archiveArtifacts`

### Legacy surface inventory

| Artifact | Location |
| --- | --- |
| `LEGACY_PLUGIN_IDS` membership | `CanonicalCoreStepDecoder.kt:112` |
| Legacy command subtype | `CanonicalCoreStepCommand.ArchiveArtifacts` — `CanonicalCoreStepDecoder.kt:198-205` |
| Legacy decoder branch | `ARCHIVE_ARTIFACTS_PLUGIN_ID` — `CanonicalCoreStepDecoder.kt:218` (constant), `:285-295` (branch) |
| Legacy metadata row | `CanonicalCoreStepMetadata.kt:29` — `StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)` (READ_ONLY is arguably WRONG: it copies files to the artifacts retention dir — G0 finding, descriptor proposal below) |
| Legacy dispatcher | `durable/CanonicalArchiveArtifactsNodeDispatcher.kt` (whole file; glob 40-62, empty-archive policy 64-79, copy+sha256+ArtifactArchived events 85-120+) |
| `CanonicalNodeDispatcher` wiring | `durable/CanonicalNodeDispatcher.kt:35` (field), `:49` (when branch), `:90-99` (`archiveArtifactsContext()`, uses `shOptions.workspaceRoot`) |
| Compiler payload | `DslCompiledPipelineCompiler.kt:664-670` — explicit `is StepSpec.ArchiveArtifacts` case producing `{"kind":"archiveArtifacts",...}` (the only one of the three with a traced lowering) |

### Classification

**Effectful (writes artifacts retention tree), non-recoverable, atomic.** Reference
pattern: `CoreShellStep`'s capability-routed handler over the existing file-ops
substrate. No subprocess. The handler delegates through a narrow artifacts/file
capability; events (`ArtifactArchived` / `ArtifactArchiveFailed`) are emitted by the
single substrate authority, not by the handler.

### StepContract sketch (PROPOSAL)

```text
Key:                core.archiveArtifacts
Input:              CoreArchiveArtifactsInput(artifacts: String, allowEmptyArchive: Boolean = false,
                                              excludes: String = "", fingerprint: Boolean = false)
Output:             CoreArchiveArtifactsOutput(entries: List<ArchivedEntry(relPath, sha256, size)>)
Descriptor:         effects = [WRITES_ARTIFACTS or WRITES_WORKSPACE-equivalent — reconcile with effects ADT;
                    legacy READ_ONLY row is a candidate mis-classification to record in G0];
                    replayPolicy = MEMOIZED; recoveryPolicy = None
Required caps:      WORKSPACE_OPERATIONS_CAPABILITY (glob + copy within workspace) — or a narrow
                    ARTIFACT_ARCHIVE_OPERATIONS seam; plus EVENT_SINK_CAPABILITY if handler emits
Input codec:        byte-identical to compiler payload at DslCompiledPipelineCompiler.kt:664-670 —
                    NOTE codec must handle nullable-tolerant defaults exactly as the compiler does
                    (allowEmptyArchive/fingerprint `?: false`)
```

### Existing test baseline / G0 capture needs

**Known pre-existing red baseline (confirmed):**
- `UatLocal009TopStepsTest.kt` — 3 pre-existing failures documented in `LB02_G0_BASELINE_EVIDENCE.md:26-28` and `S2_5_7_GATE_EVIDENCE.md:81` and `E_EM_11_CLOSURE_RECEIPT.md:103-104`:
  - `CR-U9-008 archiveArtifacts sha256 and size in event` (`UatLocal009TopStepsTest.kt:320`, "archiveArtifacts shape" gap)
  - `CR-U9-011 archiveArtifacts AntStyleGlob pattern matches files` (`:401`)
  - `CR-U9-012 cross-step writeFile then archiveArtifacts picks up file` (`:433`)
  - (also green-family: `CR-U9-009` `:354`, `CR-U9-010` `:377`)
- `UatLocal008CredentialsTest.kt` — 2 pre-existing failures (`LFC2E0_PRE_S1_EVIDENCE_AUDIT.md:165`: tests=27 failures=2; `S2_5_7_GATE_EVIDENCE.md:79-80`: `CR-BD-027 CredentialUsed per use` + corpus byte-identity). **These are credentials-related, NOT archiveArtifacts-related** — AGENTS.md groups both classes as out-of-RETRY-D-scope pre-existing reds; for wave-2 only the UatLocal009 rows are relevant.
- Corpus: `compatibility/10-smoke-e2e.pipeline.kts:13` (archiveArtifacts), `UatCompat001CorpusSmokeRunTest.kt:26` (documents intentional exit-1 of fixture 10), `CompatibilityCorpusTest.kt:50`.
- G0 must re-capture the 3 red UatLocal009 tests + green ones on base SHA (rule 16: fresh base-vs-head evidence; prior captures at `c3aaab8a` / `815a1237` are per-cycle base references to be re-derived for the wave-2 cycle).

### Risks / blockers

1. The 3 pre-existing UatLocal009 reds describe the CURRENT legacy behavior as failing — G3 parity for archiveArtifacts must reproduce the same reds (parity with broken legacy, not with Jenkins ideal), then the gaps are fixed separately or accepted as documented divergence. Decide the policy BEFORE G2 corpus migration.
2. Metadata effects mis-classification (READ_ONLY for a file-writing step): flipping the descriptor honestly may change durable metadata fingerprints; treat as a characterization decision, and check whether any fitness test asserts the legacy row verbatim (e.g. parity tests in `CoreErrorRegistryPrimaryFitnessTest.kt:109` list the key).
3. `archiveArtifactsContext()` depends on `shOptions.workspaceRoot` (`CanonicalNodeDispatcher.kt:99`) — the capability bridge must supply workspace root without dragging ShellOptions into the registry path.
4. `excludes` param: legacy dispatcher shown here globs with `globToRegex(glob)` only — UNCERTAIN whether `excludes` is honored at all (not visible in the read portion); verify in G0. Also `fingerprint` flag vs unconditional sha256 computation (`computeSha256(target)` runs regardless) — behavior parity questions for the contract suite.

---

## Cross-cutting G0 checklist (all three steps)

1. Resolve the compiler-lowering uncertainty for `cleanWs` and `load` payloads (byte-exact dsl-v1 envelopes) — hard prerequisite for codec design.
2. Fresh base-SHA evidence: `UatLocal009*` (3 red + 2 relevant green), `UatLocal011*`, corpus fixtures 18 (and the fixture-10 e2e path for archiveArtifacts), with SHA-256 log digests under `docs/v2/07-uat/evidence/`.
3. Confirm the re-entrancy cache lifetime behavior of `core.load` (characterization only; any fix is out of wave-2 scope).
4. Record legacy metadata-row effects classification findings (load EXECUTES_SUBPROCESS?, archiveArtifacts READ_ONLY?) — descriptor proposals must reconcile.
5. No `LEGACY_PLUGIN_IDS`, counters, or production source changes in G0–G3 prep; G1 starts per-step after this doc is reviewed.
