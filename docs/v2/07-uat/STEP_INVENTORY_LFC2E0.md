# LFC-2E0 — Step Inventory (machine-derived, 2026-09-11)

Status: **MERGED on main @ 5efac6c0 (PR #23) — sources cited per row; no production code change.**

Receipt: `docs/v2/07-uat/LFC2E0_CLOSURE_RECEIPT.md`

This file is the source of truth for the LFC-2E program. `STEP_ECOSYSTEM_MATRIX.md`
is the planning hypothesis and is corrected against this table.

## Sources (authoritative)

```text
LEGACY_PLUGIN_IDS                              v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt
CoreStepRegistryFactory                        v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt
CoreEchoStep                                   v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEchoStep.kt
CoreShellStep                                  v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt
Canonical*NodeDispatcher (legacy execution)    v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/Canonical*NodeDispatcher.kt
PipelineDsl.kt (DSL façades)                   v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt
StepDefinitionContributor (ServiceLoader)      examples/example-uppercase-plugin/src/main/resources/META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
Real examples                                  examples/*.pipeline.kts (10 files: 01..10)
Event Harness contracts                        examples/contracts/*.events.yaml
Certification receipts                         docs/v2/07-uat/S3_ECHO_BURNDOWN_CERTIFICATION.md
                                               docs/v2/07-uat/LB02_S6_BURN_DOWN_AND_CERTIFICATION.md
                                               docs/v2/07-uat/LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md
```

## Counts (machine-derived)

```text
Production Step keys total: 15
  Registry (open-world Step seam): 3   (core.echo, core.sh, core.error)
  Legacy (Canonical*NodeDispatcher): 11
  External plugin (ServiceLoader):  1   (example.uppercase)
DSL extension functions declared: ~67 (PipelineDsl.kt L990-1900)
Real .pipeline.kts examples: 10 (01..10)
Event Harness contracts: 4 (07, 08, 09, 10)
CERTIFIED Steps: 4 (core.echo, core.sh, example.uppercase, core.error)
```

## Inventory table

Columns:
- **Delivery** = CORE / OFFICIAL_PLUGIN / EXTERNAL_REFERENCE / DEFERRED_REMOTE / REJECTED_JENKINS_INTERNAL
- **Path** = `registry` (open seam) / `legacy` (Canonical*NodeDispatcher) / `external` (ServiceLoader)
- **DSL?** = typed façade declared in `PipelineDsl.kt` (Y/N)
- **Def?** = `StepDefinition` exists (Y/N)
- **Can?** = canonical execution path through `CanonicalDurableRunCoordinator` (Y/N)
- **Legacy?** = legacy executable path still present (Y/N — per ADR-0074 MUST be N for CERTIFIED)
- **TI/TO** = typed input / typed output (Y/N)
- **Cap?** = capability declared in `StepContract.requiredCapabilities` (Y/N)
- **Replay?** = `ReplayPolicy` set in `StepDescriptor` (Y/N)
- **Ex?** = real `.pipeline.kts` example (name; `—` if none)
- **EH?** = Event Harness YAML contract (name; `—` if none)
- **State** = `CERTIFIED` / `IMPLEMENTED_UNCERTIFIED` / `DESIGNED` / `NOT_STARTED` / `DEFERRED` / `REJECTED`

| Step key | Delivery | Path | DSL? | Def? | Can? | Legacy? | TI/TO | Cap? | Replay? | Ex? | EH? | State |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `core.echo` | CORE | registry | Y (L1002, L1817, L1855) | Y (`CoreEchoStep`) | Y | N (LB-02 burn-down) | Y/Y | N (atomic) | Y (`EffectReplayPolicy`) | 01-06, 07, 08 | 07..10 (via echo path) | **CERTIFIED** (S3 burn-down) |
| `core.sh` | CORE | registry | Y (L1006/1017, L1821, L1859, ScriptedExecutionApi.kt L157) | Y (`CoreShellStep`) | Y | N (S6 burn-down) | Y/Y | Y (`SHELL_OPERATIONS_CAPABILITY`) | Y (`EffectReplayPolicy`) | 03, 05, 06, 07, 08, 09, 10 | 07, 09, 10 | **CERTIFIED** (S6 burn-down) |
| `core.error` | CORE | registry | Y (L1034) | Y (`CoreErrorStep`) | Y | N (S2-A1 burn-down) | Y/Y | N (atomic) | Y (`ReplayPolicy.NEVER`) | 05, 15 | — | **CERTIFIED** (S2-A1 burn-down) |
| `core.sleep` | CORE | legacy | Y (L1041) | N | Y (CanonicalSleepNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.file.writeFile` | CORE | legacy | Y (L1302) | N | Y (CanonicalWriteFileNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.emit.event` | CORE | legacy | Y (emits canonical DomainEvent kinds) | N | Y (CanonicalEmitEventNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.milestone` | OFFICIAL_PLUGIN candidate | legacy | Y (L1704) | N | Y (CanonicalMilestoneNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.deleteDir` | CORE candidate | legacy | Y (L1456) | N | Y (CanonicalDeleteDirNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.cleanWs` | OFFICIAL_PLUGIN candidate | legacy | Y (L1469, L1479) | N | Y (CanonicalCleanWsNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.load` | CORE | legacy | Y (L1614) | N | Y (CanonicalLoadNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.pwd` | CORE candidate | legacy | Y (L1570) | N | Y (CanonicalPwdNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.isUnix` | CORE candidate | legacy | Y (L1590) | N | Y (CanonicalIsUnixNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.waitUntil` | CORE candidate | legacy | Y (L1629) | N | Y (CanonicalWaitUntilNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `core.archiveArtifacts` | CORE candidate | legacy | Y (L1408) | N | Y (CanonicalArchiveArtifactsNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED |
| `example.uppercase` | EXTERNAL_REFERENCE | external | Y (`UppercaseDsl.kt`) | Y (`UppercaseStepDefinition`) | Y (via ServiceLoader + registry) | N (no legacy path) | Y/Y | N | — (or implicit?) | none in 01..10 | — | **CERTIFIED** (EP burn-down) |

## Row citations

### `core.echo` — CERTIFIED + LEGACY_REMOVED (production registry, S1 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEchoStep.kt:34` `val KEY: PluginStepId = PluginStepId("core.echo")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt:31` `CoreEchoStep.registerInto(this)`
- **DSL:** `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:1002` `fun echo(text: String)`, `:1817`, `:1855`
- **Receipt (burn-down):** `docs/v2/07-uat/S3_ECHO_BURNDOWN_CERTIFICATION.md` — G0..G8 burn-down complete; LEGACY_REMOVED achieved
- **Receipt (certification):** `docs/v2/07-uat/CORE_ECHO_CERTIFICATION.md` — formal CERTIFIED + LEGACY_REMOVED combined verdict (S1)
- **Receipt (G4 fitness):** `docs/v2/07-uat/CORE_ECHO_G4_FITNESS_RECEIPT.md` — `S3EchoLegacyRemovedFitnessTest` 7/7 GREEN
- **Contract Suite (G7):** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/EchoStepContractSuiteTest.kt` — 17/17 GREEN
- **Echo test suite:** 5 files / 31/31 GREEN (LegacyEchoUnreachableProof + EchoDurableSpine + UatStep002 + CoreEchoSeam + EchoStepContractSuite)
- **Real examples:** 01-hello, 02-multi-stage, 04-kotlin-control-flow, 05-failing-step, 06-durable, 07-catch-error, 08-parallel, 09-retry
- **Real execution parity:** `examples/01-hello.pipeline.kts` → 9 events SUCCESS, 1 EchoOutputCaptured (proves registry path resolution)

```text
core.echo:
  delivery:    CORE
  execution:   REGISTRY_PRIMARY
  legacy:      REMOVED
  certification: CERTIFIED

proof (machine-derived):
  - G4 architecture fitness (S3EchoLegacyRemovedFitnessTest, 7/7 GREEN)
  - G7 StepContractSuite (EchoStepContractSuiteTest, 17/17 GREEN)
  - Echo test suite (5 files, 31/31 GREEN)
  - Real execution parity (examples/01-hello.pipeline.kts, SUCCESS)
```

State updated: `CERTIFIED (S3 burn-down)` → `CERTIFIED + LEGACY_REMOVED (S1 certification recording)`.

### `core.sh` — CERTIFIED (production registry)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt:34` `val KEY: PluginStepId = PluginStepId("core.sh")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt:39` `CoreShellStep.registerInto(this)`
- **DSL:** `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:1006/1017/1821/1859` + `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptedExecutionApi.kt:157` (suspend `sh`)
- **Capability:** `SHELL_OPERATIONS_CAPABILITY` declared in `CoreShellStep.contract.requiredCapabilities`
- **Receipt:** `docs/v2/07-uat/LB02_S6_BURN_DOWN_AND_CERTIFICATION.md` — G0..G8 burn-down complete
- **Contract Suite:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ShStepContractSuiteTest.kt`
- **Real examples:** 03-shell, 05-failing-step, 06-durable, 07-catch-error, 08-parallel, 09-retry, 10-timeout
- **Event Harness contracts:** 07 (CatchErrorTriggered adjacent), 09 (RetryAttemptFinished), 10 (TimeoutScheduled)

### `core.error` — CERTIFIED + LEGACY_REMOVED (production registry, S2-A1 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreErrorStep.kt` `val KEY: PluginStepId = PluginStepId("core.error")`
- **DSL:** `PipelineDsl.kt:1034` `fun error(message: String, failureKind: String = "UNKNOWN")`
- **Real example:** `v2/compatibility/15-error.pipeline.kts` (used as G8 fresh+replay scenario)
- **Descriptor:**
  - `effects = { Effect.ABORTS_PIPELINE }` (terminal failure semantics)
  - `replayPolicy = ReplayPolicy.NEVER` (no reproducible effect)
  - `requiredCapabilities = emptySet()` (handler is pure)
- **Typed carrier:** `CoreErrorOutput` (`TypedStepOutput`); `outcome == StepOutcome.Failure(failure)` is the single authority.
- **Receipts (certification):**
  - G5 REGISTRY_PRIMARY: `docs/v2/07-uat/S2_A1_CORE_ERROR_G5_REGISTRY_PRIMARY_RECEIPT.md`
  - G6 LEGACY_REMOVED: `docs/v2/07-uat/S2_A1_CORE_ERROR_G6_LEGACY_REMOVED_RECEIPT.md`
  - G7 CONTRACT_SUITE: `docs/v2/07-uat/S2_A1_CORE_ERROR_G7_CONTRACT_CERTIFICATION_RECEIPT.md`
  - G8 REAL_CLI_SCENARIO + CERTIFIED: `docs/v2/07-uat/S2_A1_CORE_ERROR_G8_FINAL_CERTIFICATION_RECEIPT.md`
- **Gate scoreboard at G8 close:**
  ```
  ErrorStepContractSuiteTest           17 PASS / 1 N.A. / 0 FAIL
  CoreErrorStepUnitTest                20/20
  CoreErrorRegistryPrimaryFitnessTest  14/14
  S3ErrorLegacyRemovedFitnessTest      12/12
  Fresh CLI (15-error.pipeline.kts)    exit=1, 1 USER StepFailed
  Replay CLI (15-error.pipeline.kts)   exit=1, 1 INFRASTRUCTURE replay-abort StepFailed
  legacy counters                      11 / 11 / 11
  ```

```text
core.error:
  delivery:       CORE
  execution:      REGISTRY_PRIMARY
  legacy:         REMOVED
  certification:  CERTIFIED
```

State updated: `IMPLEMENTED_UNCERTIFIED (LB-02 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-A1 closure)` at LFC-2E1-S2-A1 / G8.

### `core.sleep` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1041` `fun sleep(seconds: Long)`
- **Legacy decoder:** `CanonicalCoreStepDecoder.kt: Sleep` data class with `pluginId = "core.sleep"`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalSleepNodeDispatcher.kt:16`
- **Real example:** none in 01..10 oracle
- **Missing:** all registry items as `core.error`

### `core.file.writeFile` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1302` `fun writeFile(file: String, text: String, encoding: String = "UTF-8")`
- **Legacy decoder:** `CanonicalCoreStepDecoder.kt: WriteFile` data class with `pluginId = "core.file.writeFile"`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalWriteFileNodeDispatcher.kt`
- **Real example:** none in 01..10 oracle

### `core.emit.event` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** emits canonical DomainEvent kinds (`PipelineDsl.kt:1319` `registryStep` is generic; the typed `emitEvent` is the operational entry)
- **Legacy decoder:** `CanonicalCoreStepDecoder.kt: EmitEvent` data class with `pluginId = "core.emit.event"`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalEmitEventNodeDispatcher.kt:11`
- **Real example:** none direct in 01..10

### `core.milestone` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1704` `fun milestone(ordinal: Int, label: String? = null)`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalMilestoneNodeDispatcher.kt`

### `core.deleteDir` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1456` `fun deleteDir(path: String = ".")`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDeleteDirNodeDispatcher.kt`

### `core.cleanWs` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1469/1479` `fun cleanWs(...)`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalCleanWsNodeDispatcher.kt`

### `core.load` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1614` `fun load(path: String)`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalLoadNodeDispatcher.kt`

### `core.pwd` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1570` `fun pwd(tmp: Boolean = false): String`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalPwdNodeDispatcher.kt:18`
- **Note:** claims "real typed runtime value" in matrix; canonical is legacy, NOT registry

### `core.isUnix` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1590` `fun isUnix(): Boolean`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalIsUnixNodeDispatcher.kt:15`

### `core.waitUntil` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1629` `fun waitUntil(...)`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalWaitUntilNodeDispatcher.kt:21`

### `core.archiveArtifacts` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1408` `fun archiveArtifacts(...)`
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalArchiveArtifactsNodeDispatcher.kt:28`

### `example.uppercase` — CERTIFIED (external plugin)

- **StepDefinition:** `examples/example-uppercase-plugin/src/main/kotlin/example/uppercase/UppercaseStepDefinition.kt` `val KEY = PluginStepId("example.uppercase")`
- **Contributor:** `examples/example-uppercase-plugin/src/main/resources/META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor` → `example.uppercase.UppercaseContributor`
- **DSL:** `examples/example-uppercase-plugin/src/main/kotlin/example/uppercase/UppercaseDsl.kt`
- **Receipt:** `docs/v2/07-uat/LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md` — full EP burn-down
- **Contract Suite:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UppercaseStepContractSuiteTest.kt`

## Block-step and orchestration DSL (NOT Step keys, but use Step keys)

These DSL functions orchestrate Step execution but are NOT themselves Step keys:

- `pipeline(...)`, `stages(...)`, `stage(...)`, `steps(...)` (PipelineDsl.kt L879, L946, L964)
- `parallel(...)` (L1142) — composable Named Bodies (ADR-0073)
- `withEnv(...)` (L1368, L1380, L1390)
- `withCredentials(...)` (L1168, L1202)
- `retry(...)` (L1209, L1737)
- `catchError(...)` (L1502)
- `warnError(...)` (L1532)
- `unstable(...)` (L1558)
- `timeout(...)` (L1722)
- `dir(...)` (L1436)
- `timestamps(...)` (L1658)
- `ansiColor(...)` (L1672)
- `node(...)` (L1686) — DEFERRED_REMOTE (no fake remote scheduling per user law #7)
- `whenCondition(...)` (L1263)
- `script(...)` (L1275)

These are controller-level orchestration and depend on the canonical block-step machinery (ADR-0073); they enter the engine through `BodyInvoker.invoke` / `BranchInvoker.invokeAll`. They are out of scope for LFC-2E0 (the Step inventory), but their certification path will be addressed separately in LFC-2E1 (universal core freeze).

## Categories not represented in production (matrix-only claims)

- **Git family:** `git`, `checkout`, `readScmFile`, shallow/depth, submodules, LFS, sparse checkout, SVN/Mercurial — no StepDefinition, no canonical dispatcher (the matrix lists `pipeline-step-sdk/scm-git` as OFFICIAL_PLUGIN candidate, but it is currently NOT registered as a StepDefinition and is not in the registry or LEGACY_PLUGIN_IDS).
- **Testing/reports:** `junit`, `publishHTML` — NOT_STARTED in code.
- **HTTP/SSH/notifications:** NOT_STARTED.
- **Lock/input:** NOT_STARTED.
- **Docker/Podman:** NOT_STARTED.
- **Advanced Git:** NOT_STARTED.
- **Vendor integrations:** NOT_STARTED.

## Key corrections to STEP_ECOSYSTEM_MATRIX.md

1. **Production registry contains only 2 core keys**, not "many core rows". The matrix's `CERTIFIED` claims for Steps other than `core.echo`, `core.sh` are incorrect.
2. **All 12 LEGACY_PLUGIN_IDS Steps are IMPLEMENTED_UNCERTIFIED**, not "registered in core". They go through `Canonical*NodeDispatcher`, not through the registry seam. They have a typed façade but lack `StepDefinition`, codecs (in the registry seam sense), capabilities, replay policy, contract suite.
3. **`example.uppercase` is the only external plugin**; the matrix's broader external plugin inventory is hypothetical.
4. **Git family, testing/reports, HTTP, lock/input, Docker, advanced Git, vendor** — no production StepDefinitions exist. The matrix's `NOT_STARTED` rows are correct, but they should be presented as **negative inventory** (what is NOT there), not as planning rows.
5. **`core.pwd`, `core.isUnix`** — matrix claims "real typed runtime value"; reality: DSL exists but they are legacy, not registry, and lack a `StepDefinition<I,O>` form.

## Sequencing for LFC-2E1..

Per AGENTS.md "Burn-down sequence template (G0..G8)" and the LB-01 / LEGACY_BURNDOWN_POLICY
state machine (LEGACY → DUAL_AVAILABLE → REGISTRY_PRIMARY → LEGACY_UNREACHABLE →
LEGACY_REMOVED → CERTIFIED; **CERTIFIED requires LEGACY_REMOVED**):

```text
LFC-2E1-S1 (CLOSED 2026-09-11, ad4867f1): core.echo CERTIFICATION RECORDING
  - core.echo = CERTIFIED + LEGACY_REMOVED (S1 closure, recording only)
  - G4 architecture fitness: S3EchoLegacyRemovedFitnessTest 7/7 GREEN
  - G7 StepContractSuite:    EchoStepContractSuiteTest 17/17 GREEN
  - Echo test suite (5):     31/31 GREEN
  - Real execution parity:   examples/01-hello SUCCESS
  - Receipts:
    docs/v2/07-uat/CORE_ECHO_CERTIFICATION.md
    docs/v2/07-uat/CORE_ECHO_G4_FITNESS_RECEIPT.md
  - 0 production files modified; 0 tests refixtured (already GREEN pre-S1)
  - core.sh NOT touched (already CERTIFIED + LEGACY_REMOVED per LB-02 S6.8)
  - S3ShLegacyRemovedFitnessTest NOT created (DEDICATED_FITNESS_GAP, deferred)

LFC-2E1-S2 (next, separate cycle): burn-down of the 12 legacy keys
  P0 first (in order):
    - core.error      (registry StepDefinition + capability; G0 baseline → G8 CERTIFIED)
    - core.sleep      (registry StepDefinition; G0 → G8)
    - core.pwd        (registry; must produce typed String value)
    - core.isUnix     (registry; must produce typed Boolean)
  P1 second:
    - core.deleteDir, core.cleanWs, core.waitUntil
  P2 third:
    - core.milestone, core.load, core.archiveArtifacts, core.emit.event, core.file.writeFile
  For each Step:
    G0 baseline evidence
    G1 registry seam proof (handler + contract + codecs + capabilities)
    G2 corpus migration (durable characterisation drives registry path)
    G3 REGISTRY_PRIMARY (CoreStepRegistryFactory contains the Step)
    G4 LEGACY_UNREACHABLE (decoder/dispatcher/metadata row deleted; runtime)
    G5 LEGACY_REMOVED (source-level absence of all 3 legacy forms; static)
    G6 architecture fitness
    G7 StepContractSuite (16/17 rows)
    G8 CERTIFIED (per-Step state in burn-down ledger)
  Law: registry implementation + legacy fallback != completion;
       registry implementation + parity + legacy unreachable
       + legacy removed + certification = closure.
  DO NOT migrate the 12 in a single commit/ciclo; group by semantic family.
```

```text
LFC-2E2: utilities (filesystem + typed deterministic values; OFFICIAL_PLUGIN)
LFC-2E3: testing/reports (junit + publishHTML first time)
LFC-2E4: artifacts/stash (cleanWs already promoted in E1)
LFC-2E5: toolchains/config (no DSL surface today)
LFC-2E6: HTTP/SSH/notifications (new Step families)
LFC-2E7: lock/input (new Step families)
LFC-2E8: Docker/Podman (new Step families)
LFC-2E9: advanced Git (LFS, sparse, submodules)
LFC-2E10: complex external reference plugin (Artifactory / SonarQube / vendor)
```

EVT-4 must remain PENDING-DEFERRED-BY-LOCAL-FIRST-PRIORITY throughout.
