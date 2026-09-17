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
  Legacy (Canonical*NodeDispatcher): 11 → **0** (post-WU-G5B + CORE-LOAD-REJECTED: ZERO LEGACY RESIDUAL; the 11 keys are historical — pwd, isUnix, sleep, writeFile, emitEvent, milestone, deleteDir, cleanWs, load, waitUntil, archiveArtifacts — and ALL have been retired via burn-down (CERTIFIED) or rejection (load); the canonical production authority for every Step key is now the registry seam or the canonical RepeatUntil machinery)
  External plugin (ServiceLoader):  1   (example.uppercase)
DSL extension functions declared: ~67 (PipelineDsl.kt L990-1900) — `load(...)` removed at CORE-LOAD-REJECTED
Real .pipeline.kts examples: 10 (01..10)
Event Harness contracts: 4 (07, 08, 09, 10)
CERTIFIED Steps: 12 (core.echo, core.sh, core.error, core.sleep, core.file.writeFile, core.emit.event, core.isUnix, core.deleteDir, core.milestone, core.cleanWs, core.archiveArtifacts, core.waitUntil) + 1 EXTERNAL_REFERENCE (example.uppercase)
STOPPED_G7 Steps: 2 (core.pwd, core.pwd.tmp — non-deterministic runtime return blocks G7)
REJECTED Steps: 1 (core.load, 2026-09-17, FIRST ZERO LEGACY RESIDUAL contribution)
CERTIFIED + EXTERNAL_REFERENCE total: 13
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
| `core.sleep` | CORE | registry | Y (L1041) | Y (`CoreSleepStep`) | Y (registry) | N (S2-A2/G5 LEGACY_REMOVED) | Y/Y | Y (`SHELL_OPERATIONS_CAPABILITY` or null) | Y (`ReplayPolicy.MEMOIZED`) | 16-sleep | — | **CERTIFIED** (S2-A2/G8, `S2_A2_CORE_SLEEP_G8_FINAL_CERTIFICATION_RECEIPT.md`) |
| `core.file.writeFile` | CORE | registry | Y (L1302) | Y (`CoreWriteFileStep`) | Y (registry) | N (S2-A3/G5 LEGACY_REMOVED) | Y/Y | Y (`WORKSPACE_OPERATIONS_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | 17-writeFile | — | **CERTIFIED** (S2-A3/G8, `S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md`) |
| `core.emit.event` | CORE | registry | Y (registryStep generic) | Y (`CoreEmitEventStep`) | Y (registry) | N (S2-A4/G5 LEGACY_REMOVED) | Y/Y | Y (`EVENT_SINK_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | 12-error-handling (catchError internal use) | — | **CERTIFIED** (S2-A4/G8, `S2_A4_CORE_EMITEVENT_G8_FINAL_CERTIFICATION_RECEIPT.md`) |
| `core.milestone` | CORE | registry | Y (L1704, registryStep generic) | Y (`CoreMilestoneStep`) | Y | N (S2-A9 burn-down) | Y/Y | Y (`EVENT_SINK_CAPABILITY` + `MILESTONE_OPERATIONS_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | 21-milestone | — | **CERTIFIED** (S2-A9/G8, `S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md`) |
| `core.deleteDir` | CORE candidate | registry | Y (L1456) | Y (`CoreDeleteDirStep`) | Y | N (S2-A7 burn-down) | Y/Y | Y (`DELETE_DIR_OPERATIONS_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | G7 scenarios | — | **CERTIFIED** (S2-A7/G8, PROPOSED — `S2_A7_CORE_DELETEDIR_G8_CERTIFICATION_RECEIPT.md`) |
| `core.cleanWs` | OFFICIAL_PLUGIN candidate | registry | Y (L1469, L1479, registryStep generic) | Y (`CoreCleanWsStep`) | Y | N (S2-A10 burn-down) | Y/Y | Y (`CLEAN_WS_OPERATIONS_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | G7 scenarios | — | **CERTIFIED** (S2-A10/G8, PROPOSED — `S2_A10_CORE_CLEANWS_G8_CERTIFICATION_RECEIPT.md`) |
| `core.load` | REJECTED (CORE-LOAD-REJECTED 2026-09-17) | (removed) | N (function deleted) | N (subtype deleted) | N (CanonicalLoadNodeDispatcher deleted) | N (LEGACY_PLUGIN_IDS, decoder, metadata row, dispatcher all removed) | — | — | — | — | — | **REJECTED** (FIRST ZERO LEGACY RESIDUAL contribution; `core.load` is not a candidate for Step burn-down — directive forbids second-execution-engine shape; SPIKE-018 §1.3 declares it the LAST legacy lift requiring `SCRIPT_COMPILATION_CAPABILITY` + child-body re-entry via `BODY_INVOKER_CAPABILITY`, which is out of LFC-2 scope) |
| `core.pwd` | CORE | registry | Y (L1570) | Y (`CorePwdStep`) | Y (registry) | N (S2-A6/G5 LEGACY_REMOVED) | Y/Y | Y (`PLATFORM_IDENTITY_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | 20-pwd-tmp | — | **STOPPED_G7** (G7 installed-acceptance BLOCKED: pwd() runtime return is non-deterministic; `S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`; G8 not attempted per ADR-0074) |
| `core.isUnix` | CORE | registry | Y (L1590) | Y (`CoreIsUnixStep`) | Y (registry) | N (S2-A5/G5 LEGACY_REMOVED) | Y/Y | Y (`PLATFORM_IDENTITY_CAPABILITY` + `EVENT_SINK_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | 13-workspace-helpers, 19-isunix | — | **CERTIFIED** (S2-A5/G8, `S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md`) |
| `core.waitUntil` | CORE | orchestration (WU-G5R) | Y (L1629, registryStep) | N (ORCHESTRATION; no standard registry handler — execution via `dispatchRepeatUntilBody` in coordinator; `CoreWaitUntilStep.kt` retained only as typed codec source for `WaitUntilPolled`/`WaitUntilCompleted` events) | Y (RepeatUntil) | N (WU-G5B LEGACY_REMOVED) | Y/Y | Y (`EVENT_SINK_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | 13-workspace-helpers, 22-wait-until | — | **CERTIFIED + LEGACY_REMOVED** (WU-G5B, `S2_A8_CORE_WAITUNTIL_WU_G5B_LEGACY_REMOVED_RECEIPT.md`) |
| `core.archiveArtifacts` | CORE | registry | Y (L1408, registryStep generic) | Y (`CoreArchiveArtifactsStep`) | Y | N (S2-B10 burn-down) | Y/Y | Y (`ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY`) | Y (`ReplayPolicy.MEMOIZED`) | G7 scenarios | — | **CERTIFIED** (S2-B10/G8, PROPOSED — `S2_B10_ARCHIVEARTIFACTS_G8_CERTIFICATION_RECEIPT.md`) |
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

### `core.sleep` — CERTIFIED + LEGACY_REMOVED (production registry, S2-A2 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepStep.kt` `val KEY: PluginStepId = PluginStepId("core.sleep")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt` `CoreSleepStep.registerInto(this)`
- **DSL:** `PipelineDsl.kt:1041` `fun sleep(seconds: Long)`
- **Receipts (certification):**
  - G0: `docs/v2/07-uat/S2_A2_CORE_SLEEP_G0_AUDIT.md`
  - G1: `docs/v2/07-uat/S2_A2_CORE_SLEEP_G1_REGISTRY_CANDIDATE_RECEIPT.md`
  - G2: `docs/v2/07-uat/S2_A2_CORE_SLEEP_G2_DIFFERENTIAL_CONTRACT_FREEZE.md`
  - G3: `docs/v2/07-uat/S2_A2_CORE_SLEEP_G3_DIFFERENTIAL_PARITY_READINESS.md`
  - G4: `docs/v2/07-uat/S2_A2_CORE_SLEEP_G4_REGISTRY_PRIMARY_RECEIPT.md`
  - G5: `docs/v2/07-uat/S2_A2_CORE_SLEEP_G5_LEGACY_REMOVED_RECEIPT.md`
  - G8: `docs/v2/07-uat/S2_A2_CORE_SLEEP_G8_FINAL_CERTIFICATION_RECEIPT.md`
- **Real example:** `v2/compatibility/16-sleep.pipeline.kts`

```text
core.sleep:
  delivery:        CORE
  execution:       REGISTRY_PRIMARY
  legacy:          REMOVED
  certification:   CERTIFIED (S2-A2/G8)
```

State updated: `IMPLEMENTED_UNCERTIFIED (LB-02 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-A2/G8 closure)`.

### `core.file.writeFile` — CERTIFIED + LEGACY_REMOVED (production registry, S2-A3 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWriteFileStep.kt` `val KEY: PluginStepId = PluginStepId("core.file.writeFile")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt` `CoreWriteFileStep.registerInto(this)`
- **DSL:** `PipelineDsl.kt:1302` `fun writeFile(file: String, text: String, encoding: String = "UTF-8")`
- **Capability:** `WORKSPACE_OPERATIONS_CAPABILITY` (declared in `CoreWriteFileStep.contract.requiredCapabilities`); handler is `WorkspaceOperationsAdapter` over the certified `FileWriteExecutor` SDK substrate.
- **Receipts (certification):**
  - G0: `docs/v2/07-uat/S2_A3_CORE_WRITEFILE_G0_AUDIT.md`
  - G4/G5/G6: `docs/v2/07-uat/S2_A3_CORE_WRITEFILE_G4_G5_G6_RECEIPTS.md`
  - G8: `docs/v2/07-uat/S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md`
- **Real example:** `v2/compatibility/17-writeFile.pipeline.kts`

```text
core.file.writeFile:
  delivery:        CORE
  execution:       REGISTRY_PRIMARY
  legacy:          REMOVED
  certification:   CERTIFIED (S2-A3/G8)
```

State updated: `IMPLEMENTED_UNCERTIFIED (LB-02 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-A3/G8 closure)`.

### `core.emit.event` — CERTIFIED + LEGACY_REMOVED (production registry, S2-A4 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEmitEventStep.kt` `val KEY: PluginStepId = PluginStepId("core.emit.event")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt` `CoreEmitEventStep.registerInto(this)`
- **DSL:** `PipelineDsl.kt:1319` (registryStep generic); the typed `emitEvent` is the operational entry.
- **Capability:** `EVENT_SINK_CAPABILITY` (handler is the canonical `EventSink` adapter).
- **Receipts (certification):**
  - G0: `docs/v2/07-uat/S2_A4_CORE_EMITEVENT_G0_AUDIT.md`
  - G1: `docs/v2/07-uat/S2_A4_CORE_EMITEVENT_G1_REGISTRY_CANDIDATE_RECEIPT.md`
  - G2: `docs/v2/07-uat/S2_A4_CORE_EMITEVENT_G2_DIFFERENTIAL_CONTRACT_FREEZE.md`
  - G3/G4: `docs/v2/07-uat/S2_A4_CORE_EMITEVENT_G3_G4_REGISTRY_PRIMARY_RECEIPT.md`
  - G5: `docs/v2/07-uat/S2_A4_CORE_EMITEVENT_G5_LEGACY_REMOVED_RECEIPT.md`
  - G6: `docs/v2/07-uat/S2_A4_CORE_EMITEVENT_G6_CONTRACT_CERTIFICATION_RECEIPT.md`
  - G8: `docs/v2/07-uat/S2_A4_CORE_EMITEVENT_G8_FINAL_CERTIFICATION_RECEIPT.md`
- **Real example:** `v2/compatibility/12-error-handling.pipeline.kts` (the catchError DSL is internally routed through `core.emit.event`; the marker steps in the fixture have `stepType=emit`).

```text
core.emit.event:
  delivery:        CORE
  execution:       REGISTRY_PRIMARY
  legacy:          REMOVED
  certification:   CERTIFIED (S2-A4/G8)
```

State updated: `IMPLEMENTED_UNCERTIFIED (LB-02 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-A4/G8 closure)`.

### `core.milestone` — CERTIFIED + LEGACY_REMOVED (production registry, S2-A9 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStep.kt` `val KEY: PluginStepId = PluginStepId("core.milestone")`
- **DSL:** `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:1704` `fun milestone(ordinal: Int, label: String? = null)` (now lowers to `StepSpec.RegistryStepSpec` directly, byte-equivalent canonical envelope; legacy `StepSpec.Milestone` subtype removed at G5)
- **Descriptor:** `effects = { Effect.WRITES_WORKSPACE }` (typed milestone state is persisted); `replayPolicy = ReplayPolicy.MEMOIZED`; `requiredCapabilities = { EVENT_SINK_CAPABILITY, MILESTONE_OPERATIONS_CAPABILITY }`
- **Typed carriers:** input `MilestoneInput(ordinal: Int, label: String?)`; output `MilestoneOutput(ordinal: Int, label: String?, outcome: StepOutcome, sequence: Int)` (sealed `Reached` / `Aborted`); durable event `MilestoneReached` / `MilestoneAborted`
- **Receipts (certification):**
  - G1: `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G1_RECEIPT.md`
  - G2: `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G2_RECEIPT.md`
  - G3: `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G3_RECEIPT.md`
  - G5 LEGACY_REMOVED: `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G5_LEGACY_REMOVED_RECEIPT.md` (counters 5/5/5 → 4/4/4)
  - G6 CONTRACT_SUITE: `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G6_CONTRACT_CERTIFICATION_RECEIPT.md` (24/0/0 with observability row)
  - G7 INSTALLED_ACCEPTANCE: implicit via `21-milestone.pipeline.kts` canary executed at G5 (RunFinished{outcome=success} + MilestoneReached{1,2})
  - G8 CERTIFIED: `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md` (this receipt)

```text
core.milestone:
  delivery:       CORE
  execution:      REGISTRY_PRIMARY
  legacy:         REMOVED
  certification:  CERTIFIED (proposed by G8 receipt; counters 4/4/4)
```

State updated: `IMPLEMENTED_UNCERTIFIED (LFC-2E0 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-A9 closure)` at LFC-2E1-S2-A9 / G8. LEGACY_PLUGIN_IDS residual: **4 / 4 / 4** (cleanWs, load, waitUntil, archiveArtifacts).

### `core.deleteDir` — CERTIFIED + LEGACY_REMOVED (production registry, S2-A7 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreDeleteDirStep.kt` `val KEY: PluginStepId = PluginStepId("core.deleteDir")`
- **DSL:** `PipelineDsl.kt:1456` `fun deleteDir(path: String = ".")`
- **Descriptor:** `effects = { Effect.WRITES_WORKSPACE }`; `replayPolicy = ReplayPolicy.MEMOIZED`; `requiredCapabilities = { DELETE_DIR_OPERATIONS_CAPABILITY }`
- **Typed carrier:** `DeleteDirOutput(path, deletedCount, sha256)`; durable event `DirDeleted` emitted by `DeleteDirOperationsAdapter`
- **Receipts (certification):**
  - G4 REGISTRY_PRIMARY: `docs/v2/07-uat/S2_A7_CORE_DELETEDIR_G4_REGISTRY_PRIMARY_RECEIPT.md`
  - G5 LEGACY_REMOVED: `docs/v2/07-uat/S2_A7_CORE_DELETEDIR_G5_LEGACY_REMOVED_RECEIPT.md` (counters 5/5/5)
  - G6 CONTRACT_SUITE: `CoreDeleteDirStepContractSuiteTest` 22/0/0
  - G7 INSTALLED_ACCEPTANCE: `docs/v2/07-uat/S2_A7_CORE_DELETEDIR_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` (4/4 PASS)
  - G8 CERTIFIED: `docs/v2/07-uat/S2_A7_CORE_DELETEDIR_G8_CERTIFICATION_RECEIPT.md`

```text
core.deleteDir:
  delivery:       CORE
  execution:      REGISTRY_PRIMARY
  legacy:         REMOVED
  certification:  CERTIFIED (proposed by G8 receipt; counters 5/5/5 unchanged)
```

State updated: `IMPLEMENTED_UNCERTIFIED (LB-02 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-A7 closure)` at LFC-2E1-S2-A7 / G8. LEGACY_PLUGIN_IDS residual remains 5 / 5 / 5.

### `core.cleanWs` — CERTIFIED + LEGACY_REMOVED (production registry, S2-A10 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreCleanWsStep.kt` `val KEY: PluginStepId = PluginStepId("core.cleanWs")`
- **DSL:** `PipelineDsl.kt:1469/1479` `fun cleanWs(deleteDirs: Boolean = true, patterns: List<String>? = null)` (S2-A10/G5 lowered to `StepSpec.RegistryStepSpec` direct, byte-equivalent to `CoreCleanWsStep.inputCodec.encode`).
- **Descriptor:** `effects = { Effect.WRITES_WORKSPACE }`; `replayPolicy = ReplayPolicy.MEMOIZED` (deleteAll) / `RERUN` (selective patterns); `requiredCapabilities = { CLEAN_WS_OPERATIONS_CAPABILITY }`.
- **Typed carrier:** `CleanWsOutput(deletedFiles, deletedDirs, patterns, sha256)`; durable event `WsCleaned` emitted by `CleanWsOperationsAdapter` (single emission authority over the existing `CleanWsExecutor` SDK substrate).
- **Receipts (certification):**
  - G4 REGISTRY_PRIMARY: `docs/v2/07-uat/S2_A10_CORE_CLEANWS_G4_REGISTRY_PRIMARY_RECEIPT.md` (counter 4 → 3)
  - G5 LEGACY_REMOVED: `docs/v2/07-uat/S2_A10_CORE_CLEANWS_G5_LEGACY_REMOVED_RECEIPT.md` (counters 3/4/4 → 3/3/3; `CanonicalCleanWsNodeDispatcher.kt` deleted; `cleanWs` removed from LEGACY_PLUGIN_IDS)
  - G6 CONTRACT_SUITE: `CoreCleanWsStepContractSuiteTest` 17/17 coverage matrix (observability row 13 provenanced to §LB-02; row 14 architecture fitness DELEGATED to `Lfc2RegistryFamilyFitnessTest` + `S3*LegacyRemovedFitnessTest` + `Core*RegistryPrimaryFitnessTest`)
  - G7 INSTALLED_ACCEPTANCE: `docs/v2/07-uat/S2_A10_CORE_CLEANWS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` (4/4 PASS — fresh deletion, event contract, rerun idempotency, legacy absence)
  - G8 CERTIFIED: `docs/v2/07-uat/S2_A10_CORE_CLEANWS_G8_CERTIFICATION_RECEIPT.md` (this receipt)

```text
core.cleanWs:
  delivery:       OFFICIAL_PLUGIN candidate (registry seam)
  execution:      REGISTRY_PRIMARY
  legacy:         REMOVED
  certification:  CERTIFIED (proposed by G8 receipt; counters 3/3/3 unchanged)
```

State updated: `IMPLEMENTED_UNCERTIFIED (LFC-2E0 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-A10 closure)` at LFC-2E1-S2-A10 / G8. LEGACY_PLUGIN_IDS residual was 2 / 2 / 2 at the time of this closure (`core.load`, `core.waitUntil`); the slice `core.archiveArtifacts` removed at S2-B10/G5; `core.waitUntil` factory entry removed at WU-G5R but decoder membership unchanged at the time).

### `core.load` — REJECTED (FIRST ZERO LEGACY RESIDUAL contribution, CORE-LOAD-REJECTED 2026-09-17)

> **CORE-LOAD-REJECTED (2026-09-17, this slice):** `core.load` is REJECTED. The directive
> explicitly forbids "handler → compiler arbitrario → execute child pipeline como segundo
> execution engine" — the `core.load` Step is, by Jenkins semantics, a *second* execution
> engine (it loads and evaluates a `.pipeline.kts` script from the stage workspace). SPIKE-018
> §1.3 declares `core.load` the "LARGEST remaining legacy lift", requiring
> `SCRIPT_COMPILATION_CAPABILITY` + child-body re-entry via `BODY_INVOKER_CAPABILITY` — both
> of which are out of LFC-2 scope. Converging signals:
>
> 1. Legacy `CanonicalLoadNodeDispatcher` is a silent no-op (reads file, emits
>    `WorkflowLoaded stepCount=0`, returns `Success` — does not execute child pipeline).
> 2. Latent contract defect: DSL `load(path)` → `OpaqueStepNode("core.load")` with no
>    `path` in the canonical envelope → fails closed at decode.
> 3. `UatLocal011WorkflowControlTest::SC-011-11` is `@Disabled` (INC-024).
> 4. Zero `load(...)` usage in `v2/compatibility/`.
>
> All six legacy forms physically deleted in this slice: `CanonicalCoreStepCommand.Load`
> subtype, `LOAD_PLUGIN_ID` decoder branch + constant, `CanonicalCoreStepMetadata["core.load"]`
> row, `CanonicalLoadNodeDispatcher.kt` file, `fun load(path)` DSL façade, `StepSpec.Load`
> data class. `LEGACY_PLUGIN_IDS` shrinks 1/1/1 → **0/0/0** (FIRST ZERO LEGACY RESIDUAL).
> The legacy executor's input surface is empty at the type level — no production StepKey can
> ever route through the legacy dispatcher.

```text
core.load:
  delivery:        REJECTED (was CORE candidate, removed 2026-09-17)
  execution:       (removed; no production path)
  legacy:          REMOVED (canonical decoder, dispatcher, metadata row, sealed subtype all deleted)
  certification:   (n/a — REJECTED, not CERTIFIED)
```

State: `IMPLEMENTED_UNCERTIFIED (LFC-2E0 inventory)` → **`REJECTED` (CORE-LOAD-REJECTED, 2026-09-17)**.
LEGACY_PLUGIN_IDS residual: **0 / 0 / 0** — the FIRST ZERO LEGACY RESIDUAL achievement.

Fitness (gate G6): `Lfc2ZeroLegacyResidualFitnessTest` 7/7 PASS.

Receipt: `docs/v2/07-uat/S2_A5_CORE_LOAD_REJECTION_RECEIPT.md` (this slice).

Future work for `core.load`-equivalent semantics: any "load child pipeline" Step must be
designed from scratch as a registry Step with `SCRIPT_COMPILATION_CAPABILITY` +
`BODY_INVOKER_CAPABILITY` (ADR-0073 child-body re-entry). This is an LFC-3+ design item,
not an LFC-2E burn-down item.

### `core.pwd` — STOPPED_G7 (registry-primary; LEGACY_REMOVED; G7 installed-acceptance BLOCKED)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdStep.kt` `val KEY: PluginStepId = PluginStepId("core.pwd")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt` `CorePwdStep.registerInto(this)`
- **DSL:** `PipelineDsl.kt:1570` `fun pwd(tmp: Boolean = false): String`
- **Capability:** `PLATFORM_IDENTITY_CAPABILITY`; handler reads `System.getProperty("user.dir")` via the canonical platform-identity port.
- **Receipts:**
  - G0: `docs/v2/07-uat/S2_A6_CORE_PWD_G0_CHARACTERIZATION_RECEIPT.md`
  - G3: `docs/v2/07-uat/S2_A6_CORE_PWD_G3_MIGRATION_READINESS.md`
  - G3R: `docs/v2/07-uat/S2_A6_CORE_PWD_G3T_DETERMINISTIC_TMP_RECEIPT.md` (`pwd(tmp=true)` deterministic-tmp variant)
  - G4: `docs/v2/07-uat/S2_A6_CORE_PWD_G4_REGISTRY_PRIMARY_RECEIPT.md`
  - G5: `docs/v2/07-uat/S2_A6_CORE_PWD_G5_LEGACY_REMOVED_RECEIPT.md`
  - G6: `docs/v2/07-uat/S2_A6_CORE_PWD_G6_CONTRACT_CERTIFICATION_RECEIPT.md`
  - **G7 STOP_BLOCKED: `docs/v2/07-uat/S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`**
- **G7 verdict:** `INSTALLED_ACCEPTANCE = false — G7 BLOCKED. G8 NOT attempted.` Per ADR-0074, STOPPED is a terminal state for the slice; CERTIFIED requires real-CLI green.
- **G7 breakdown:**
  - PWD-G7-01 `pwd()` runtime return → **FAIL** (non-deterministic; depends on `user.dir`)
  - PWD-G7-02 `pwd(tmp=true)` durable effect → **PASS**
  - PWD-G7-03 downstream Kotlin consume → **FAIL** (G7-01 fallout)
  - PWD-G7-04 replay/resume (same --db) → **PASS**
- **Real fixture:** `v2/compatibility/20-pwd-tmp.pipeline.kts`

```text
core.pwd:
  delivery:        CORE
  execution:       REGISTRY_PRIMARY
  legacy:          REMOVED (S2-A6/G5)
  certification:   STOPPED_G7 (G7 BLOCKED; G8 not attempted per ADR-0074)
```

State: `IMPLEMENTED_UNCERTIFIED (LB-02 inventory)` → `REGISTRY_PRIMARY + LEGACY_REMOVED (S2-A6/G5)` → `STOPPED_G7 (S2-A6/G7)`. Legacy forms are physically gone; the Step itself is not CERTIFIED because the runtime return path is non-deterministic (depends on `System.getProperty("user.dir")`, which differs between fresh and replay runs).

### `core.isUnix` — CERTIFIED + LEGACY_REMOVED (production registry, S2-A5 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixStep.kt` `val KEY: PluginStepId = PluginStepId("core.isUnix")`
- **Registry:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt` `CoreIsUnixStep.registerInto(this)`
- **DSL:** `PipelineDsl.kt:1590` `fun isUnix(): Boolean`
- **Capability:** `PLATFORM_IDENTITY_CAPABILITY` + `EVENT_SINK_CAPABILITY`; the handler reads OS name via the platform-identity port (deterministic classifier over canonical OS string) and emits `UnixDetected(isUnix, osName, sha256)` event.
- **Receipts (certification):**
  - G2: `docs/v2/07-uat/S2_A5_CORE_ISUNIX_G2_CANONICAL_DIFFERENTIAL_FREEZE.md`
  - G3: `docs/v2/07-uat/S2_A5_CORE_ISUNIX_G3_MIGRATION_READINESS.md`
  - G4: `docs/v2/07-uat/S2_A5_CORE_ISUNIX_G4_REGISTRY_PRIMARY_RECEIPT.md`
  - G5: `docs/v2/07-uat/S2_A5_CORE_ISUNIX_G5_LEGACY_REMOVED_RECEIPT.md`
  - G6: `docs/v2/07-uat/S2_A5_CORE_ISUNIX_G6_CONTRACT_CERTIFICATION_RECEIPT.md`
  - G8: `docs/v2/07-uat/S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md`
- **Real examples:** `v2/compatibility/13-workspace-helpers.pipeline.kts`, `v2/compatibility/19-isunix.pipeline.kts`

```text
core.isUnix:
  delivery:        CORE
  execution:       REGISTRY_PRIMARY
  legacy:          REMOVED (S2-A5/G5)
  certification:   CERTIFIED (S2-A5/G8)
```

State: `IMPLEMENTED_UNCERTIFIED (LB-02 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-A5/G8 closure)`.

### `core.waitUntil` — CERTIFIED + LEGACY_REMOVED (canonical RepeatUntil machinery, WU-G5B closure)

> **WU-G5B (2026-09-17, this slice):** core.waitUntil LEGACY_REMOVED. All six legacy forms
> physically deleted: `CanonicalCoreStepCommand.WaitUntil` subtype, `WAIT_UNTIL_PLUGIN_ID`
> decoder branch + constant, `CanonicalCoreStepMetadata["core.waitUntil"]` row,
> `CanonicalWaitUntilNodeDispatcher.kt` file, and the `CanonicalNodeDispatcher` waitUntil
> seams (field, when branch, waitUntilContext). `LEGACY_PLUGIN_IDS` shrinks 2/2/2 -> 1/1/1.
> Production routing is exclusively the canonical RepeatUntil machinery
> (`BlockStepNode(BodyExecutionPolicy.RepeatUntil)` → `dispatchRepeatUntilBody` in
> `CanonicalDurableRunCoordinator`, ADR-0073). The state flips from `IMPLEMENTED_UNCERTIFIED`
> to `CERTIFIED + LEGACY_REMOVED` at this closure proof.

- **DSL:** `PipelineDsl.kt:1629` `fun waitUntil(...)` (lowered to `StepSpec.WaitUntilBlock`
  → `BlockStepNode(BodyExecutionPolicy.RepeatUntil)` at WU-G5R.2)
- **Authority path:** `dispatchRepeatUntilBody` in `CanonicalDurableRunCoordinator` (ADR-0073);
  `BodyInvoker` re-entry for condition evaluation
- **Production routing:** canonical RepeatUntil machinery (registry entry was removed at
  WU-G5R.3; execution is purely the coordinator's block-step machinery, not a registry handler)
- **LEGACY_PLUGIN_IDS:** REMOVED at WU-G5B (counters 2/2/2 → 1/1/1; only `core.load` remains)
- **Real example:** `v2/compatibility/22-wait-until.pipeline.kts` (SHA256: `7befc004582257fa779b9403e65e01d65acb3f5ac7a8933603a2f8de1a9990b4`)
- **Fitness:**
  - `Lfc2WaitUntilCanonicalReentryFitnessTest` — installed CLI test: WaitUntilPolled + WaitUntilCompleted emitted through canonical path (4/4 PASS)
  - `LegacyResidualConvergenceFitnessTest` — `assertConverged` PASS (live residual == expected; counter 1/1/1)
- **Receipts:**
  - G1: `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G1_REGISTRY_CANDIDATE_RECEIPT.md`
  - G2: `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G2_DIFFERENTIAL_CONTRACT_FREEZE.md`
  - G3: `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G3_CONTRACT_SUITE_RECEIPT.md`
  - WU-G5R-GATE: `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_WU_G5R_GATE_CLOSURE_RECEIPT.md`
  - **WU-G5B LEGACY_REMOVED + CERTIFIED: `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_WU_G5B_LEGACY_REMOVED_RECEIPT.md` (this slice)**

```text
core.waitUntil:
  delivery:       CORE
  execution:      ORCHESTRATION (canonical RepeatUntil machinery via dispatchRepeatUntilBody)
  legacy:         REMOVED (LEGACY_PLUGIN_IDS, decoder subtype, metadata row, dispatcher file all deleted)
  certification: CERTIFIED + LEGACY_REMOVED (WU-G5B, 2026-09-17)
```

State: `IMPLEMENTED_UNCERTIFIED (WU-G5R: factory entry removed, ORCHESTRATION kind; LEGACY_PLUGIN_IDS counters 2/2/2 unchanged)` → **`CERTIFIED + LEGACY_REMOVED` (WU-G5B, 2026-09-17)**.

### `core.archiveArtifacts` — CERTIFIED + LEGACY_REMOVED (production registry, S2-B10 closure)

- **StepDefinition:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreArchiveArtifactsStep.kt` `val KEY: PluginStepId = PluginStepId("core.archiveArtifacts")`
- **DSL:** `PipelineDsl.kt:1408` `fun archiveArtifacts(...)` (lowered to `StepSpec.RegistryStepSpec`).
- **Descriptor:** `effects = { Effect.WRITES_WORKSPACE }`; `replayPolicy = ReplayPolicy.MEMOIZED`; `requiredCapabilities = { ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY }`.
- **Typed carrier:** `ArchiveArtifactsOutput` / `ArchiveArtifactsFailureOutput`; durable effect emitted by `ArchiveArtifactsOperations` (the adapter is the single observability authority; the handler does not consume `EVENT_SINK_CAPABILITY`).
- **Receipts (certification):**
  - G0 CLASSIFICATION: `S2_B10_ARCHIVEARTIFACTS_G0_CLASSIFICATION_MEMO.md`
  - G2 CONTRACT FREEZE: `S2_B10_ARCHIVEARTIFACTS_G2_DIFFERENTIAL_CONTRACT_FREEZE.md`
  - G3 READINESS: `S2_B10_ARCHIVEARTIFACTS_G3_READINESS_RECEIPT.md`
  - G4 REGISTRY_PRIMARY: `S2_B10_ARCHIVEARTIFACTS_G4_REGISTRY_PRIMARY_RECEIPT.md` (id counter 3 → 2; state 2/3/3)
  - G5 LEGACY_REMOVED: `S2_B10_ARCHIVEARTIFACTS_G5_LEGACY_REMOVED_RECEIPT.md` (counters 2/3/3 → 2/2/2; `CanonicalArchiveArtifactsNodeDispatcher.kt` deleted; metadata row removed; id removed from `LEGACY_PLUGIN_IDS`)
  - G6 CONTRACT_SUITE: `S2_B10_ARCHIVEARTIFACTS_G6_CONTRACT_CERTIFICATION_RECEIPT.md` (17/17 coverage matrix; `CoreArchiveArtifactsStepContractSuiteTest` 27 tests)
  - G7 INSTALLED_ACCEPTANCE: `S2_B10_ARCHIVEARTIFACTS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` (5/5 PASS — non-empty byte-identical, empty-match failure, allow-empty discriminator, excludes delta, legacy-absence probe over 37 jars)
  - G8 CERTIFIED: `S2_B10_ARCHIVEARTIFACTS_G8_CERTIFICATION_RECEIPT.md` (this receipt)

```text
core.archiveArtifacts:
  delivery:       CORE (registry seam)
  execution:      REGISTRY_PRIMARY
  legacy:         REMOVED
  certification:  CERTIFIED (proposed by G8 receipt; counters 2/2/2 unchanged)
```

State updated: `IMPLEMENTED_UNCERTIFIED (LFC-2E0 inventory)` → `CERTIFIED + LEGACY_REMOVED (S2-B10 closure)` at LFC-2E1-S2-B10 / G8. LEGACY_PLUGIN_IDS residual is **2 / 2 / 2** (`core.load`, `core.waitUntil` — `core.waitUntil` LEGACY_PLUGIN_IDS membership unchanged at WU-G5R; see `S2_A8_CORE_WAITUNTIL_WU_G5R_GATE_CLOSURE_RECEIPT.md`).

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

1. **Production registry contains 12 CERTIFIED core keys** + 1 EXTERNAL_REFERENCE (`example.uppercase`) = **13 registry-resolved Steps** as of LFC-2E0 closure. The matrix's `CERTIFIED` claims were outdated pre-LFC-2E0 (it listed only 2/3 keys as certified); the canonical truth is `docs/v2/status/step-certification.yaml`.
2. **`core.pwd`** — STOPPED_G7, not CERTIFIED. The Step's runtime return path is non-deterministic (depends on `System.getProperty("user.dir")`), so G7 installed-acceptance BLOCKED at `pwd()` / downstream-consume criteria (PWD-G7-01 / G7-03 FAIL). G8 not attempted per ADR-0074. The Step is registry-primary with LEGACY_REMOVED at S2-A6/G5, but the Step itself is not CERTIFIED.
3. **`core.isUnix`** — CERTIFIED + LEGACY_REMOVED at S2-A5/G8. The matrix's "real typed runtime value" claim is correct for `core.isUnix` (registry-routed, deterministic over canonical OS string); it was incorrect for `core.pwd` (see #2).
4. **`core.load`** — REJECTED at CORE-LOAD-REJECTED (2026-09-17). Five converging signals; directive forbids the only viable implementation shape ("handler → compiler arbitrario → execute child pipeline como segundo execution engine"); SPIKE-018 §1.3 declares it the LAST legacy lift requiring new infrastructure (`SCRIPT_COMPILATION_CAPABILITY` + `BODY_INVOKER_CAPABILITY`) out of LFC-2 scope.
5. **`example.uppercase`** — the only external plugin (CERTIFIED). The matrix's broader external plugin inventory is hypothetical.
6. **Git family, testing/reports, HTTP, lock/input, Docker, advanced Git, vendor** — no production StepDefinitions exist. The matrix's `NOT_STARTED` rows are correct, but they should be presented as **negative inventory** (what is NOT there), not as planning rows.
7. **`core.waitUntil`** — CERTIFIED + LEGACY_REMOVED at WU-G5B (2026-09-17). Production routing is exclusively the canonical RepeatUntil machinery (ADR-0073); `CoreWaitUntilStep.kt` is retained as typed codec source for `WaitUntilPolled` / `WaitUntilCompleted` events (NOT as registry handler).

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
    - core.deleteDir, core.cleanWs, core.waitUntil (WU-G5R: AUTHORITY_FLIPPED)
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
