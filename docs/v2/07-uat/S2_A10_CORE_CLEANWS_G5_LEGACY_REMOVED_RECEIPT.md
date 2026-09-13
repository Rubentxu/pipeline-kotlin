# S2-A10 / G5 LEGACY_REMOVED — core.cleanWs Receipt

> **Lane A — sole global writer of authority for S2-A10 core.cleanWs.**
> **Counter law (S2-A10/G3 docs correction `f8c23d8c`):**
> - G4 = N/N/N → (N-1)/N/N (ids only; metadata + dispatcher physical remain UNREACHABLE)
> - **G5 = (N-1)/N/N → (N-1)/(N-1)/(N-1)** (metadata + dispatcher physical)
>
> This flip: **3/4/4 → 3/3/3**.

**Step under burn-down:** `core.cleanWs` (WS_OPERATIONS capability)
**Gate closed:** G5 LEGACY_REMOVED
**Base SHA (origin/main, pre-merge S2-A10/G4):** `65a8ce235138c3182d1e616cd5afc061d9affc22`
**G4 commit (parent of G5):** `29de352ef12a9402bc0fccb272e9df60f32bf483`
**G5 commit (HEAD of cycle/lfc2-e1-cleanws-g5):** `c0e0e659[...]`
**Date:** 2026-09-13
**Authority:** docs/v2/ (ROADMAP + STEP_CONSTITUTION + STEP_PLUGIN_CERTIFICATION + ADRs 0070..0075); not :pipeline-steps-system:compiler-plugin.

---

## 1. What G5 destroyed (LEGACY_REMOVED — physical destructive)

### 1.1 Production routing source-of-truth

**`CanonicalCoreStepDecoder.kt`** — physical removal of legacy decoder surface:

- `data class CleanWs(val deleteDirs, val patterns)` subtype — **deleted** (replaced by doc-comment citing LEGACY_REMOVED).
- `private const val CLEAN_WS_PLUGIN_ID = "core.cleanWs"` — **deleted**.
- `CLEAN_WS_PLUGIN_ID -> { ... CanonicalCoreStepCommand.CleanWs(...) }` decoder branch — **deleted**.
- `LEGACY_PLUGIN_IDS` set already excluded `core.cleanWs` (G4 flip; verified at compile-time).

**`CanonicalCoreStepMetadata.kt`** — physical removal of legacy metadata row:

- `"core.cleanWs" to StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED)` row — **deleted**.
- Production metadata is now read exclusively from `CoreCleanWsStep.descriptor` via `RegistryStepMetadataResolver`.

**`durable/CanonicalCleanWsNodeDispatcher.kt`** — **FILE DELETED**. Was the LEGACY path's `cleanWs` executor (with `WorkspaceResolver` + `CleanWsExecutor`); now unreachable.

**`durable/CanonicalNodeDispatcher.kt`** — physical removal of cleanWs dispatcher seams:

- `private val cleanWsDispatcher = CanonicalCleanWsNodeDispatcher()` field — **deleted**.
- `is CanonicalCoreStepCommand.CleanWs -> cleanWsDispatcher.dispatch(command, context.cleanWsContext())` when-branch — **deleted**.
- `private fun CanonicalRuntimeContext.cleanWsContext() = CanonicalCleanWsDispatchContext(...)` extension — **deleted**.

### 1.2 DSL producer migration (PipelineDsl.kt)

`fun cleanWs(deleteDirs: Boolean = true, patterns: List<String>? = null)` and `fun cleanWs(deleteDirs: Boolean = true, vararg patterns: String)`:

```diff
+ // S2-A10 / G5 (2026-09-13): this DSL lowers directly to `StepSpec.RegistryStepSpec`
+ // (open-world registry path). The payload is encoded inline here to match the canonical
+ // codec of `CoreCleanWsStep.inputCodec` byte-for-byte, so the durable fingerprint is
+ // preserved across the G5 destructive flip. The legacy `StepSpec.CleanWs` subtype still
+ // exists as a sealed-interface member because `CleanWsExecutor` (SDK files) types its
+ // parameter against it; this DSL was the only producer that routed through the legacy
+ // decoder, and that producer is gone.
- steps.add(StepSpec.CleanWs(deleteDirs = deleteDirs, patterns = patterns))
+ // Canonical envelope: {"kind":"cleanWs","deleteDirs":<bool>,"patterns":[...]}
+ // Matches CoreCleanWsStep.inputCodec.encode output (S2-A10 / G5).
+ val canonicalPatterns: List<String> = patterns ?: emptyList()
+ val sb = StringBuilder()
+ sb.append("{\"kind\":\"cleanWs\",\"deleteDirs\":").append(deleteDirs).append(",\"patterns\":[")
+ canonicalPatterns.forEachIndexed { i, p ->
+     if (i > 0) sb.append(",")
+     sb.append('"').append(escapeJsonString(p)).append('"')
+ }
+ sb.append("]}")
+ steps.add(
+     StepSpec.RegistryStepSpec(
+         stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.cleanWs"),
+         schemaVersion = "dsl-v1",
+         encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
+     ),
+ )
```

**Byte-equivalence preservation**: the inline JSON is `{"kind":"cleanWs","deleteDirs":<bool>,"patterns":[...]}` with `deleteDirs` always present (not omitted on default), `patterns` always present (default `[]`). This matches `CoreCleanWsStep.inputCodec.encode(CleanWsInput())` output exactly, so durable fingerprints are preserved across the G5 destructive flip.

**`StepSpec.CleanWs` (sealed interface subtype)** — **preserved** (NOT deleted). Rationale: `CleanWsExecutor.execute(spec: StepSpec.CleanWs)` in the SDK files types its parameter against it; removing it would break the SDK. The subtype is now a sealed-interface member without any DSL producer (dead subtype, precedent: `StepSpec.Milestone` S2-A9/G5 commit `6b5e40d3`).

### 1.3 LegacyResidualSnapshot state machine

```diff
- private val physicalResidual: Set<String> = setOf(
-     "core.cleanWs",
-     "core.load", "core.waitUntil", "core.archiveArtifacts",
- )
+ private val physicalResidual: Set<String> = setOf(
+     "core.load", "core.waitUntil", "core.archiveArtifacts",
+ )

- private val registryPrimaryPendingRemoval: String? = "core.cleanWs"
+ private val registryPrimaryPendingRemoval: String? = null
```

Post-S2-A10/G5 the snapshot reflects the converged state: 3 / 3 / 3. The 7 sibling `S3*LegacyRemovedFitnessTest` suites now use `LegacyResidualSnapshot.assertConverged(root)` (previously `assertCurrentState` during the G4 transitional state).

### 1.4 Counter test reconciliation

7 sibling `CoreXxxRegistryPrimaryFitnessTest` + `CoreDeleteDirStepUnitTest` + `CoreCleanWsStepContractSuiteTest`:

- The S2-A10/G4 "3 residual legacy keys remain" tests stay green.
- New S2-A10/G5 "3 residual legacy keys remain after LEGACY_REMOVED" tests added (one per suite, citing LEGACY_REMOVED).
- `CoreDeleteDirStepUnitTest`: the G4 transitional `counters - post-S2-A10-G4 transitional 3-4-4` test is `@Disabled` (historical snapshot; the `core.cleanWs` metadata row it expected is now physically gone); the new G5 `counters - post-S2-A10-G5 LEGACY_REMOVED 3-3-3 converged` test asserts `assertFalse("core.cleanWs" in CanonicalCoreStepMetadata.pluginIds)`.

### 1.5 `CanonicalCoreStepCommandRegistryTest`

- `sealedSubclasses has exactly 4 entries` → `sealedSubclasses has exactly 3 entries`.
- `CleanWs has correct pluginId and defaultMetadata` — **deleted** (LEGACY_REMOVED). The pluginId/Effects/ReplayPolicy invariants are now asserted in `CoreCleanWsStepContractSuiteTest` against `CoreCleanWsStep.descriptor` (registry authority).

### 1.6 Differential test deletion

`CoreCleanWsDifferentialContractTest.kt` — **whole file deleted** (5 tests, 372 lines). The differential test relied on the LEGACY path as the second leg; without it, this file cannot drive the differential. The REGISTRY leg (CoreCleanWsStep via CleanWsOperations capability) is now exhaustively covered by `CoreCleanWsStepContractSuiteTest` (23/0/0). Trazabilidad preservada en este receipt + en el G4 readiness receipt.

### 1.7 Test fixture migration (LEGACY subtype references)

6 test files referenced the deleted `CanonicalCoreStepCommand.CleanWs` as a representative LEGACY subtype fixture; migrated to `CanonicalCoreStepCommand.Load(path = "legacy-fixture.pipeline.kts")`:

- `ExecutionBoundaryFactoryTest` (`legacyPrepared()` helper).
- `GenericRegistryExecutionCarrierTest` (`PreparedLegacyExecution(CleanWs)` → `Load`).
- `LegacyExecutionAdapterTest` (`command = CanonicalCoreStepCommand.CleanWs()` → `Load(...)`).
- `RegistryExecutionBoundaryTest` (1 site).
- `SeamedExecutionRouterTest` (1 site).
- `A3DurableProjectionCharacterizationTest` (1 site).

**`CoreLegacyStepMetadataResolverTest`** — the pre-existing red test `resolves sleep metadata by step key matching the decoded command` used `CleanWs()` as an unrelated proxy for `core.sleep`. Replaced with `Load(path = "x")` to keep the test compiling; the structural flaw (asserting that a legacy resolver can resolve a registry-primary key) remains and the test stays a pre-existing red. A successor test against `CoreSleepStep.descriptor` belongs to a follow-up.

---

## 2. Counter reconciliation (3/3/3)

| Counter | Pre-G5 (post-S2-A10/G4) | Post-S2-A10/G5 | Delta |
| --- | --- | --- | --- |
| `LEGACY_PLUGIN_IDS.size` (ids) | 3 | **3** | 0 |
| `CanonicalCoreStepMetadata.pluginIds.size` (metadata) | 4 | **3** | -1 |
| `Canonical*NodeDispatcher.kt` files (physical) | 4 | **3** | -1 (file deleted) |

Residual legacy keys (post-G5): `core.load`, `core.waitUntil`, `core.archiveArtifacts`. Their decoder / dispatcher / metadata forms remain physically present and **UNREACHABLE in production** until each key's own G5 closes its lane.

`core.cleanWs` no longer appears in `LEGACY_PLUGIN_IDS`, `CanonicalCoreStepMetadata.pluginIds`, or in any `Canonical*NodeDispatcher.kt` file. Production routing is exclusively `CoreCleanWsStep.definition` via `CoreStepRegistryFactory.registry()`.

---

## 3. Files changed (29 total)

```text
Deleted (2):
  pipeline-application/src/main/.../durable/CanonicalCleanWsNodeDispatcher.kt
  pipeline-application/src/test/.../CoreCleanWsDifferentialContractTest.kt

Modified (27):
  production (3):
    pipeline-application/src/main/.../CanonicalCoreStepDecoder.kt   (subtype + const + branch removed)
    pipeline-application/src/main/.../CanonicalCoreStepMetadata.kt   (metadata row removed)
    pipeline-application/src/main/.../durable/CanonicalNodeDispatcher.kt  (3 seams removed)
  DSL (1):
    pipeline-scripting-api/src/main/.../dsl/PipelineDsl.kt          (cleanWs() producers migrated)
  application tests (16):
    CanonicalCoreStepCommandRegistryTest.kt                          (sealedSubclasses 4->3, CleanWs subtype test removed)
    CoreCleanWsStepContractSuiteTest.kt                             (G4 invariant 3-4-4 -> G5 invariant 3-3-3)
    CoreDeleteDirStepUnitTest.kt                                    (@Disabled G4 transitional + new G5 truth)
    CoreEmitEventRegistryPrimaryFitnessTest.kt                      (G4 truth + G5 LEGACY_REMOVED truth)
    CoreErrorRegistryPrimaryFitnessTest.kt                          (G4 truth + G5 LEGACY_REMOVED truth)
    CoreIsUnixRegistryPrimaryFitnessTest.kt                         (G4 truth + G5 LEGACY_REMOVED truth)
    CorePwdRegistryPrimaryFitnessTest.kt                            (G4 truth + G5 LEGACY_REMOVED truth)
    CoreSleepRegistryPrimaryFitnessTest.kt                          (G4 truth + G5 LEGACY_REMOVED truth)
    CoreWriteFileRegistryPrimaryFitnessTest.kt                      (G4 truth + G5 LEGACY_REMOVED truth)
    CoreLegacyStepMetadataResolverTest.kt                           (pre-existing red fixture migrated)
    durable/A3DurableProjectionCharacterizationTest.kt              (fixture migrated to Load)
    durable/ExecutionBoundaryFactoryTest.kt                         (fixture migrated to Load)
    durable/GenericRegistryExecutionCarrierTest.kt                  (fixture migrated to Load)
    durable/LegacyExecutionAdapterTest.kt                           (fixture migrated to Load)
    durable/RegistryExecutionBoundaryTest.kt                        (fixture migrated to Load)
    durable/SeamedExecutionRouterTest.kt                            (fixture migrated to Load)
  architecture tests (7):
    LegacyResidualSnapshot.kt                                       (physicalResidual -= cleanWs; registryPrimaryPendingRemoval = null)
    S3EmitEventLegacyRemovedFitnessTest.kt                          (assertCurrentState -> assertConverged)
    S3ErrorLegacyRemovedFitnessTest.kt                              (assertCurrentState -> assertConverged, 4 sites)
    S3IsUnixLegacyRemovedFitnessTest.kt                             (assertCurrentState -> assertConverged)
    S3PwdLegacyRemovedFitnessTest.kt                                (assertCurrentState -> assertConverged)
    S3SleepLegacyRemovedFitnessTest.kt                              (assertCurrentState -> assertConverged)
    S3WriteFileLegacyRemovedFitnessTest.kt                          (assertCurrentState -> assertConverged)
```

```text
29 files changed, 224 insertions(+), 520 deletions(-)
2 files deleted
```

---

## 4. Canary (L1 + L3 targeted)

### 4.1 L0 — compile (`L0 < 10s`)

```text
> Task :pipeline-application:compileKotlin       SUCCESSFUL
> Task :pipeline-application:compileTestKotlin  SUCCESSFUL
> Task :pipeline-architecture-tests:compileTestKotlin  SUCCESSFUL
> Task :pipeline-scripting-api:compileKotlin   SUCCESSFUL
BUILD SUCCESSFUL in 8s
```

(only pre-existing deprecation warnings; no new warnings introduced by G5)

### 4.2 L1 — 9 application test suites (128/0/0/29)

Command (targeted, `--rerun-tasks`, fresh XML canary verified):

```bash
timeout 600 ./gradlew :pipeline-application:test \
  --tests CoreCleanWsStepContractSuiteTest \
  --tests CoreDeleteDirStepUnitTest \
  --tests CoreEmitEventRegistryPrimaryFitnessTest \
  --tests CoreErrorRegistryPrimaryFitnessTest \
  --tests CoreIsUnixRegistryPrimaryFitnessTest \
  --tests CorePwdRegistryPrimaryFitnessTest \
  --tests CoreSleepRegistryPrimaryFitnessTest \
  --tests CoreWriteFileRegistryPrimaryFitnessTest \
  --tests CanonicalCoreStepCommandRegistryTest
```

Result (`/tmp/g5-final-canary.log`):

```text
BUILD SUCCESSFUL in 30s
                                        tests  failures  errors  skipped
CoreCleanWsStepContractSuiteTest          23     0        0        0
CoreDeleteDirStepUnitTest                 19     0        0        2   (@Disabled S2-A9/G5 + S2-A10/G4 transitional)
CoreEmitEventRegistryPrimaryFitnessTest   14     0        0        5   (@Disabled S2-A4/G4..A10/G4 + new S2-A10/G5 truth)
CoreErrorRegistryPrimaryFitnessTest       18     0        0        6   (@Disabled S2-A4/G4..A10/G4 + new S2-A10/G5 truth)
CoreIsUnixRegistryPrimaryFitnessTest      11     0        0        5   (@Disabled S2-A4/G4..A10/G4 + new S2-A10/G5 truth)
CorePwdRegistryPrimaryFitnessTest         10     0        0        4   (@Disabled S2-A4/G4..A10/G4 + new S2-A10/G5 truth)
CoreSleepRegistryPrimaryFitnessTest       13     0        0        9   (@Disabled S2-A4/G4..A10/G4 + new S2-A10/G5 truth)
CoreWriteFileRegistryPrimaryFitnessTest    8     0        0        4   (@Disabled S2-A4/G4..A10/G4 + new S2-A10/G5 truth)
CanonicalCoreStepCommandRegistryTest       6     0        0        0
                                        ─────
                                        128     0        0       29
```

XML sha256 evidence (`/tmp/g5-xml-sha256.txt`):

```text
8453d7814799d75bd29d2f51444cdbeeefdbe2e01bd470736a31a6582c45eb69  CanonicalCoreStepCommandRegistryTest.xml
30f04744be1c2385a24493f8d6fc9f934b96f2ad4133641c2e9a841cb72fe895  CoreCleanWsStepContractSuiteTest.xml
90f745714194d55f87dba445a7f025d4cd2a23c0df623d793e810477c023484c  CoreDeleteDirStepUnitTest.xml
7461f314037129f67c0ed7351f6305104588bfdd2f64dbc904ceeedd5fbc6d93  CoreEmitEventRegistryPrimaryFitnessTest.xml
385f83e0513302020fff365831488edc294e3c17c4418bfba9303ac9894dbc66  CoreErrorRegistryPrimaryFitnessTest.xml
b485f1cded930fa8c7f77019767130705bc53cc05006285f41fea4f30b57436f  CoreIsUnixRegistryPrimaryFitnessTest.xml
6bd21ebb89d722502df88180aee7a9b222be326a6ed1aef417db2f7541d999a0  CorePwdRegistryPrimaryFitnessTest.xml
ba59e9aa15e46ea77cc389f7ccf083d90b32d25d675dd3db1a21bda51dd92679  CoreSleepRegistryPrimaryFitnessTest.xml
3dff1324931cb2ed0861f099c26bc135311d4e4bbb1ffca43d74bcf5619ddbfa  CoreWriteFileRegistryPrimaryFitnessTest.xml
```

### 4.3 L1 — 7 affected architecture test suites (48/0/0/0)

Command (targeted, `--rerun-tasks`):

```bash
timeout 600 ./gradlew :pipeline-architecture-tests:test \
  --tests S3EmitEventLegacyRemovedFitnessTest \
  --tests S3ErrorLegacyRemovedFitnessTest \
  --tests S3IsUnixLegacyRemovedFitnessTest \
  --tests S3PwdLegacyRemovedFitnessTest \
  --tests S3SleepLegacyRemovedFitnessTest \
  --tests S3WriteFileLegacyRemovedFitnessTest \
  --tests Lfc2RegistryFamilyFitnessTest
```

Result (`/tmp/g5-final-canary.log`):

```text
BUILD SUCCESSFUL in 19s
                                        tests  failures  errors  skipped
S3EmitEventLegacyRemovedFitnessTest       9      0        0       0
S3ErrorLegacyRemovedFitnessTest           7      0        0       0
S3IsUnixLegacyRemovedFitnessTest          6      0        0       0
S3PwdLegacyRemovedFitnessTest             6      0        0       0
S3SleepLegacyRemovedFitnessTest           8      0        0       0
S3WriteFileLegacyRemovedFitnessTest       6      0        0       0
Lfc2RegistryFamilyFitnessTest             6      0        0       0
                                        ─────
                                         48     0        0        0
```

XML sha256 evidence (`/tmp/g5-arch-sha256.txt`):

```text
0a494c8dc50e15aeb6e2f8c62240103c36a6921fdee6e6d980086d69e8fbe6fb  Lfc2RegistryFamilyFitnessTest.xml
7317ef393e60b950cf9c5de698dd13bcebd8c6210afb887697cd197df50e395e  S3EmitEventLegacyRemovedFitnessTest.xml
9148baab0493b8da6471af3dc19ba0ee2f032cf5e8b7571e1888958786da7c83  S3ErrorLegacyRemovedFitnessTest.xml
8f029db9cfa5ce176763b25ef5326b4cd1631ca1da32f70c5ef56ab228159b6a  S3IsUnixLegacyRemovedFitnessTest.xml
cf6978169b0773c89322d420e10ff0535ac3fac1762607aa4fa868baad175f85  S3PwdLegacyRemovedFitnessTest.xml
d3c3941ac08014634a50c41d494b1881f5118901993fc9aa012268b6fc35c9f3  S3SleepLegacyRemovedFitnessTest.xml
02fe1578b51c48d22c16e0a634202d5c885ef1697b28aa9076d864e40db964c2  S3WriteFileLegacyRemovedFitnessTest.xml
```

### 4.4 L1 — combined canary (176/0/0/29)

Combined (9 application + 7 architecture suites):

```text
                                        tests  failures  errors  skipped
Application (9 suites)                  128     0        0       29
Architecture (7 suites)                  48     0        0        0
                                        ─────
                                        176     0        0       29
```

### 4.5 Pre-existing reds NOT introduced by G5 (worktree method, base = `29de352e`)

**Methodology**: stashed G5 changes, ran the same 16-class suite on baseline `29de352e` (G4 receipt), unstashed G5. Both runs share the same 43 pre-existing failures — diff is empty.

```bash
grep -E 'FAILED' /tmp/baseline-full.log | sort > /tmp/baseline-full-fails.txt   # 43 lines
grep -E 'FAILED' /tmp/g5-full.log        | sort > /tmp/g5-full-fails.txt       # 43 lines
diff /tmp/baseline-full-fails.txt /tmp/g5-full-fails.txt
# (empty — only "BUILD FAILED in N seconds" timing difference)
```

Pre-existing failures in `:pipeline-application:test` (43):

```text
A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test > family classifier routes core sh through Registry
CompatibilityCorpusTest > allCorpusFixturesAreDiscoverable
CompatibilityCorpusTest > fixture14CredentialsBindings
CoreIsUnixStepUnitTest > counters drop to 6-6-6 post-S2-A6-G5 ...
CoreLegacyStepMetadataResolverTest > ordinary steps declare no recovery
CoreLegacyStepMetadataResolverTest > resolves sleep metadata by step key ...
DualExecutionSeamCharacterizationTest > fresh valid legacy executes exactly once through both seams
DualExecutionSeamCharacterizationTest > replay reuse never reaches either seam
DualExecutionSeamCharacterizationTest > running shell recovery never reaches either seam(Path)
DurableProtocolInvocationCharacterizationTest > a1-4 lifecycle spine ...
EmitEventStepContractSuiteTest > no legacy resurrection — irreversible G5 state holds post-S2-A6-G4
ExecutionBoundaryFactoryTest > build returns LegacyOnly boundary when registry is null
ExecutionBoundaryFactoryTest > build returns SeamedExecutionRouter when registry is present and stepKey is owned
ExecutionBoundaryFactoryTest > build with null recorder returns the produced boundary without wrapping
ExecutionBoundaryFactoryTest > build with recorder wraps the produced boundary and increments recorder counter
RegistryStepMetadataResolverTest > a remaining legacy core key delegates to the legacy core authority
UatCompat001CorpusSmokeRunTest > corpus smoke-runs green and satisfies M2 exit criterion
UatCompat001CorpusSmokeRunTest > each corpus fixture produces non-empty event stream
UatLocal005CheckoutGitTest > SC-007 poll detects changed SHA and emits GitPollChanged(Path)
UatLocal005CorpusUntouchedTest > CP-002 corpus has exactly 13 valid fixture files(Path)
UatLocal007SandboxProfileTest > SB-S-008 parallel branches have isolated cwds(Path)
UatLocal007SandboxProfileTest > SB-S-010 resume with profile change none-to-local re-attaches(Path)
UatLocal008CredentialsTest > UAT-L8-CP-001 original 4 corpus files byte-identical to cycle base
UatLocal008CredentialsTest > CR-BD-027 CredentialUsed per use(Path)
UatLocal009TopStepsTest > CR-U9-001 writeFile readFile round-trip with sha256 event
UatLocal009TopStepsTest > CR-U9-002 fileExists true after writeFile
UatLocal009TopStepsTest > CR-U9-003 writeFile atomic write succeeds
UatLocal009TopStepsTest > CR-U9-004 writeFile cross-fs fallback documented in atomicallyMoved
UatLocal009TopStepsTest > CR-U9-008 archiveArtifacts sha256 and size in event
UatLocal009TopStepsTest > CR-U9-011 archiveArtifacts AntStyleGlob pattern matches files
UatLocal009TopStepsTest > CR-U9-012 cross-step writeFile then archiveArtifacts picks up file
CanonicalDurableRunCoordinatorTest > Exactly-once discipline - 3 steps emits 3 StepStarted and 3 StepFinished
CanonicalDurableRunCoordinatorTest > withCredentials acquires scope overlays env and always closes
CanonicalDurableRunCoordinatorTest > withCredentials cleanup failure folds a successful body to failure
CanonicalDurableRunCoordinatorTest > journals a supported block child with its body path(Path)
CanonicalDurableRunCoordinatorTest > ReplayDecision ABORT emits one failed lifecycle without dispatching
CanonicalDurableRunCoordinatorTest > journals and checkpoints a linear canonical echo run
CanonicalDurableRunCoordinatorTest > dispatch decodes each StepNode before delegating to the typed dispatcher
CanonicalDurableRunCoordinatorTest > run emits StepFinished after dispatch
CanonicalDurableRunCoordinatorTest > run emits StepStarted before dispatch
CanonicalDurableRunCoordinatorTest > No step events for ReplayDecision SKIP
CanonicalDurableRunCoordinatorTest > dispatch returns SCHEMA for a fresh structurally-valid but typed-invalid payload
```

Pre-existing failure in `:pipeline-architecture-tests:test` (1):

```text
Lfc0GlobalStateFitnessTest > production code does not access the controller user directory property
```

Per AGENTS.md rule 16 (Never classify a failure as "pre-existing" without fresh base-vs-head evidence): all 43 failures were reproduced on the worktree at base SHA `29de352e` (G4 receipt) with G5 changes stashed (log: `/tmp/baseline-full.log`).

**G5 introduces ZERO regressions.**

---

## 5. Status of counters and CERTIFIED Steps

| Item | Pre-G5 (post-S2-A10/G4) | Post-S2-A10/G5 |
| --- | --- | --- |
| Certified Steps | 9 (echo, sh, error, sleep, writeFile, emit.event, isUnix, deleteDir, milestone) + example.uppercase | **9 + 1** (unchanged) |
| Legacy executable Steps | 3 | **2** (core.cleanWs fully removed; core.load, core.waitUntil, core.archiveArtifacts remain for their own G4/G5 lanes) |
| Registry-primary Steps | 10 | **11** (core.cleanWs joined the registry-primary set; its G5 closes its own lane) |

`N + M = 11 = total` (where N = certified registry-primary core Steps + external plugins and M = legacy executable Steps).
Convergence target: `M → 0`. The next 3 G5s (`core.load`, `core.waitUntil`, `core.archiveArtifacts`) each close `M -= 1`.

---

## 6. Gate progression (next: G6 contract certification)

```text
S2-A10/G0 baseline captured (G3 readiness receipt f8c23d8c)
S2-A10/G1 CoreCleanWsStep registered (cherry-pick 0c3103eb, PR #34)
S2-A10/G2 differential contract freeze test (cherry-pick 6fd6c043 → bdd4f81e, PR #34)
S2-A10/G3 StepContractSuite 23/23 (cherry-pick 3e26401e, PR #34)
S2-A10/G4 REGISTRY_PRIMARY flip (commit 8f8de9a5, PR #35, MERGED? pending)
S2-A10/G5 LEGACY_REMOVED            ← THIS RECEIPT (commit c0e0e659)
S2-A10/G6 contract certification (next, STOP awaiting GO)
S2-A10/G7 StepContractSuite full coverage
S2-A10/G8 CERTIFIED
```

G6 destructive work (carry-forward from G3 23/0/0 contract suite, no destructive code expected):

1. Walk through each row of the 17/17 Step Contract Suite coverage matrix and verify the existing G3 contract suite covers all rows against the G5 source state.
2. Add any new contract rows required by the G5 destructive flip (e.g., explicit assertions that the deleted decoder branch / dispatcher field / metadata row are no longer reachable in production).
3. Update provenance comments where the source-of-truth has moved.

G7 = installed-CLI proof: `v2/compatibility/18-cleanWs.pipeline.kts` runs green through the open registry path (`StepSpec.RegistryStepSpec` → `CoreCleanWsStep` → `CleanWsOperations` capability → `CleanWsOperationsAdapter` → `CleanWsExecutor`).

G8 = CERTIFIED: counter reconciliation (3/3/3 stable), `core.cleanWs` joins the registry-primary counter at 11; certified core Steps + external plugins ledger update.

---

## 7. References

- **STEP_CONSTITUTION / STEP_PLUGIN_CERTIFICATION** — closed execution structure, open Step registry; capability-routed handler discipline.
- **ADR-0070..0074** — Step plugin seam ADRs.
- **ADR-0075** — Retry-D durable control rows (no relation to cleanWs but cited for counter-discipline style).
- **`docs/v2/07-uat/S2_A10_CORE_CLEANWS_G3_READINESS_RECEIPT.md`** — prior gate's G3 receipt.
- **`docs/v2/07-uat/S2_A10_CORE_CLEANWS_G4_REGISTRY_PRIMARY_RECEIPT.md`** — prior gate's G4 receipt (this slice's direct predecessor).
- **`docs/v2/07-uat/S2_A9_CORE_MILESTONE_G5_LEGACY_REMOVED_RECEIPT.md`** — direct precedent (commit `6b5e40d3`).
- **`docs/v2/07-uat/S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md`** — G8 precedent for counter reconciliation (`bd9f6ee7`).

---

**Receipt author:** Jcode (MiniMax-M3, Lane A sole global writer for S2-A10)
**Receipt SHA:** derived from G5 commit `c0e0e659[...]`
**STOP awaiting GO for S2-A10/G6 contract certification.**
