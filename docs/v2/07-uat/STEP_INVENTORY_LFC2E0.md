# LFC-2E0 — Step Inventory (machine-derived, 2026-09-11)

Status: **DRAFT — sources cited per row; no production code change.**

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
  Registry (open-world Step seam): 2   (core.echo, core.sh)
  Legacy (Canonical*NodeDispatcher): 12
  External plugin (ServiceLoader):  1   (example.uppercase)
DSL extension functions declared: ~67 (PipelineDsl.kt L990-1900)
Real .pipeline.kts examples: 10 (01..10)
Event Harness contracts: 4 (07, 08, 09, 10)
CERTIFIED Steps: 3 (core.echo, core.sh, example.uppercase)
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
| `core.error` | CORE | legacy | Y (L1034) | N | Y (CanonicalErrorNodeDispatcher) | Y | Y/N | N | — | 05 | — | IMPLEMENTED_UNCERTIFIED |
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

### `core.echo` — CERTIFIED (production registry)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEchoStep.kt:34` `val KEY: PluginStepId = PluginStepId("core.echo")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt:31` `CoreEchoStep.registerInto(this)`
- **DSL:** `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:1002` `fun echo(text: String)`, `:1817`, `:1855`
- **Receipt:** `docs/v2/07-uat/S3_ECHO_BURNDOWN_CERTIFICATION.md` — G0..G8 burn-down complete; LEGACY_REMOVED achieved
- **Contract Suite:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/EchoStepContractSuiteTest.kt` — 16/17 rows per LB-02
- **Real examples:** 01-hello, 02-multi-stage, 04-kotlin-control-flow, 05-failing-step, 06-durable, 07-catch-error, 08-parallel, 09-retry

### `core.sh` — CERTIFIED (production registry)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt:34` `val KEY: PluginStepId = PluginStepId("core.sh")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt:39` `CoreShellStep.registerInto(this)`
- **DSL:** `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:1006/1017/1821/1859` + `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptedExecutionApi.kt:157` (suspend `sh`)
- **Capability:** `SHELL_OPERATIONS_CAPABILITY` declared in `CoreShellStep.contract.requiredCapabilities`
- **Receipt:** `docs/v2/07-uat/LB02_S6_BURN_DOWN_AND_CERTIFICATION.md` — G0..G8 burn-down complete
- **Contract Suite:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ShStepContractSuiteTest.kt`
- **Real examples:** 03-shell, 05-failing-step, 06-durable, 07-catch-error, 08-parallel, 09-retry, 10-timeout
- **Event Harness contracts:** 07 (CatchErrorTriggered adjacent), 09 (RetryAttemptFinished), 10 (TimeoutScheduled)

### `core.error` — IMPLEMENTED_UNCERTIFIED (legacy)

- **DSL:** `PipelineDsl.kt:1034` `fun error(message: String, failureKind: String = "UNKNOWN")`
- **Legacy decoder:** `CanonicalCoreStepDecoder.kt: Error` data class with `pluginId = "core.error"` (in LEGACY_PLUGIN_IDS)
- **Legacy dispatcher:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalErrorNodeDispatcher.kt:7`
- **Real example:** 05-failing-step (uses `error(...)`)
- **Missing:** StepDefinition; canonical registry path; typed output (returns Unit/no value); capabilities; replay policy; contract suite; certification receipt
- **State:** IMPLEMENTED_UNCERTIFIED — burn-down required (G0 baseline → G8 CERTIFIED per AGENTS.md)

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

Per AGENTS.md "Burn-down sequence template (G0..G8)":

```text
LFC-2E1: universal core freeze
  1. promote core.{error, sleep, writeFile, emit.event, milestone, deleteDir, cleanWs, load, pwd, isUnix, waitUntil, archiveArtifacts}
     from legacy to registry
  2. for each: StepDefinition<I,O> + codecs + descriptor + capability declaration + contract suite
  3. G0 baseline evidence → G8 CERTIFIED
  4. burn-down each one through the LEGACY_REMOVED gate
```

```text
LFC-2E2: utilities (no production Step; review + scope)
LFC-2E3: testing/reports (junit + publishHTML first time)
LFC-2E4: artifacts/stash (cleanWs already promoted)
LFC-2E5: toolchains/config (no DSL surface today)
LFC-2E6: HTTP/SSH/notifications (new Step families)
LFC-2E7: lock/input (new Step families)
LFC-2E8: Docker/Podman (new Step families)
LFC-2E9: advanced Git (LFS, sparse, submodules)
LFC-2E10: complex external reference plugin (Artifactory / SonarQube / vendor)
```

EVT-4 must remain PENDING-DEFERRED-BY-LOCAL-FIRST-PRIORITY throughout.
