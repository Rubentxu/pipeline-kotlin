# WU-F4 — Ecosystem Matrix (GENERATED)

Generated: 2026-09-19T12:07:22+00:00 by `scripts/gen-ecosystem-matrix.py`.
Single authority for ecosystem inventory. DO NOT edit by hand — regenerate.

Source files:
  - `v2/pipeline-application/src/main/kotlin/.../CoreStepRegistryFactory.kt` (CORE)
  - `examples/*/src/main/kotlin/**/*StepDefinition.kt` (EXTERNAL_REFERENCE)
  - `v2/pipeline-scripting-api/src/main/kotlin/.../dsl/PipelineDsl.kt` (orchestration)
  - `docs/v2/07-uat/WU_LPR_032_RECEIPT.md` (admission)
  - `docs/v2/07-uat/WU_LPR_060_CERTIFICATION_LEDGER.md` (counts: SUPPORTED_CERTIFIED = 15)

## 1. CORE StepDefinitions (registered in `CoreStepRegistryFactory`)

Count: **16**

| StepKey | Registered class | Admission | Certification |
|---|---|---|---|
| `core.archiveArtifacts` | `CoreArchiveArtifactsStep` | **SUPPORTED** | S2-B10 G8 CERTIFIED |
| `core.cleanWs` | `CoreCleanWsStep` | **SUPPORTED** | S2-A10 G8 CERTIFIED |
| `core.deleteDir` | `CoreDeleteDirStep` | **SUPPORTED** | S2-A7 G8 CERTIFIED |
| `core.echo` | `CoreEchoStep` | **SUPPORTED** | S3 G8 CERTIFIED |
| `core.emit.event` | `CoreEmitEventStep` | **SUPPORTED** | S2-A4 G8 CERTIFIED |
| `core.error` | `CoreErrorStep` | **SUPPORTED** | S2-A1 G8 CERTIFIED |
| `core.file.writeFile` | `CoreWriteFileStep` | **SUPPORTED** | S2-A3 G8 CERTIFIED |
| `core.fileExists` | `CoreFileExistsStep` | **SUPPORTED** | WU-LPR-104 CERTIFIED |
| `core.isUnix` | `CoreIsUnixStep` | **SUPPORTED** | S2-A5 G8 CERTIFIED |
| `core.milestone` | `CoreMilestoneStep` | **SUPPORTED** | S2-A9 G8 CERTIFIED |
| `core.pwd` | `CorePwdStep` | **SUPPORTED** | S2-A6 G8 CERTIFIED |
| `core.pwd.tmp` | `CorePwdTmpStep` | **EXPERIMENTAL** | S2-A6 G3T CERTIFIED |
| `core.readFile` | `CoreReadFileStep` | **SUPPORTED** | WU-LPR-104 CERTIFIED |
| `core.sh` | `CoreShellStep` | **SUPPORTED** | S6 G8 CERTIFIED |
| `core.sleep` | `CoreSleepStep` | **SUPPORTED** | S2-A2 G8 CERTIFIED |
| `core.waitUntil` | `CoreWaitUntilStep` | **SUPPORTED** | S2-A8 G3R CERTIFIED |

SUPPORTED_CERTIFIED: **15**
EXPERIMENTAL: **1**
DEFERRED/UNSUPPORTED: **0**

## 2. OFFICIAL_PLUGIN StepDefinitions

Count: **0** (no `examples/*-plugin/` with official SDK packaging).

The official plugin SDK is provided by `pipeline-step-sdk` (per `STEP_PLUGIN_SDK.md`).
No project-owned OFFICIAL_PLUGIN exists in this repository at the time of this matrix.

## 3. EXTERNAL_REFERENCE StepDefinitions

Count: **1**

| Class | StepKey | Source path |
|---|---|---|
| `UppercaseStepDefinition` | `example.uppercase` | `examples/example-uppercase-plugin/src/main/kotlin/example/uppercase/UppercaseStepDefinition.kt` |

## 4. DEFERRED_REMOTE / REJECTED_JENKINS_INTERNAL

Count: **0** declared (categories are reserved for M4+ remote-controller work and for Jenkins Java-extension bridges that are intentionally never ported).

## 5. Orchestration blocks (NOT StepDefinitions)

Count: **20**

These are DSL funs that build structural IR (BlockStepNode / StepSpec.*) but
do not register a `StepDefinition` in the registry. They are NOT counted in
the CORE total above. The list mirrors `PipelineDsl.kt` and is split by the
admission gate observed in `WU-F3` empirical audit (HEAD `d89c0f9f`).

| Block | Structural shape | Status (per WU-F3 audit) |
|---|---|---|
| `pipeline` | `PipelineScope` | SUPPORTED (entry point) |
| `stages` | `StagesScope` | SUPPORTED |
| `stage` | `StageScope` | SUPPORTED |
| `environment` | `EnvironmentScope` | SUPPORTED |
| `options` | `OptionsScope` | SUPPORTED |
| `post` | `PostScope` | SUPPORTED (construction-time capture; runtime semantics per WU-LPR-401) |
| `parallel` | `ParallelScope` | DEFERRED (canonical body machinery per ADR-0073; not yet at LPR-GATE-1) |
| `withCredentials` | `CredentialsScope` | DEFERRED (credential binder scope; per `UatLocal008 PRE_EXISTING` ledger §4) |
| `retry` | `RetryBlock` | DEFERRED (durable retry; B12 in lfc2-step-constitution-plugin-seam; steered by E-EM-11 D1/D2) |
| `timeout` | `TimeoutBlock` | DEFERRED (durable timeout; B12 in lfc2-step-constitution-plugin-seam) |
| `withEnv` | `EnvOverrideBlock` | SUPPORTED (env-override block) |
| `dir` | `DirBlock` | DEFERRED (workspace-context block; B11) |
| `timestamps` | `TimestampsDecorator` | DEFERRED (output-decorator block; not certified) |
| `waitUntil` | `WaitUntilBlock` | SUPPORTED (S2-A8 G3R CERTIFIED; runtime predicate via BodyInvoker) |
| `whenCondition` | `WhenConditionBlock` | UNSUPPORTED, fail-closed at compile (canonical bridge rejects; see WU-F3) |
| `script` | `ScriptBlock` | UNSUPPORTED, fail-closed at compile (canonical bridge rejects; see WU-F3) |
| `node` | `NodeNoOp` | UNSUPPORTED, fail-closed at compile (canonical bridge rejects; see WU-F3) |
| `load` | `LoadStep` | UNSUPPORTED, fail-closed at compile (canonical bridge rejects; see WU-F3) |
| `pwd` | `RuntimeValue` | SUPPORTED (honest placeholder + execution-time materialisation; WU-LPR-402) |
| `isUnix` | `RuntimeValue` | SUPPORTED (honest placeholder + execution-time materialisation; WU-LPR-402) |

## 6. Counts summary

| Bucket | Count | Authority |
|---|---|---|
| CORE StepDefinitions | 16 | `CoreStepRegistryFactory.kt` |
| OFFICIAL_PLUGIN StepDefinitions | 0 | (none in repo) |
| EXTERNAL_REFERENCE StepDefinitions | 1 | `examples/*/...*StepDefinition.kt` |
| DEFERRED_REMOTE / REJECTED | 0 | (categories reserved) |
| Orchestration blocks (DSL funs) | 20 | `PipelineDsl.kt` |

**Not summed**: orchestration blocks are not StepDefinitions and live in a
different namespace. The CORE total governs `StepRegistry` size; the
orchestration total governs `BlockStepNode` shape coverage.
