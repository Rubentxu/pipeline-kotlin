# WU-LPR-060 — Certification Ledger (GENERATED)

Generated: 2026-09-18T22:03:05+00:00 by `scripts/gen-certification-ledger.py`.
Single authority for counts. DO NOT edit counts by hand — regenerate.

## 1. Registered core Steps (from `CoreStepRegistryFactory`)

Registered core StepDefinitions: **16**

| StepKey | Registered class | Admission (032) | Certification |
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

- SUPPORTED_CERTIFIED: **15**
- EXPERIMENTAL: **1** (`core.pwd.tmp`)
- SUPPORTED_NOT_CERTIFIED: 0 (none: every SUPPORTED key carries a G-receipt CERTIFIED provenance in 032)
- DEFERRED / UNSUPPORTED registered Steps: 0

## 2. Live corpus coverage (fixture -> exercised keys)

| Fixture | Exercised registered keys |
|---|---|
| `01-basic.pipeline.kts` | `core.echo` |
| `02-environment.pipeline.kts` | `core.echo` |
| `03-stages.pipeline.kts` | `core.echo` |
| `04-sh.pipeline.kts` | `core.echo`, `core.sh` |
| `05-scripted-if.pipeline.kts` | `core.echo` |
| `06-loop.pipeline.kts` | `core.echo`, `core.sh` |
| `08-withEnv-pipeline.pipeline.kts` | `core.echo`, `core.sh` |
| `09-sh-then-echo.pipeline.kts` | `core.echo`, `core.sh` |
| `10-smoke-e2e.pipeline.kts` | `core.archiveArtifacts`, `core.echo`, `core.sh` |
| `11-workflow-control.pipeline.kts` | `core.deleteDir`, `core.echo`, `core.error`, `core.sh` |
| `12-error-handling.pipeline.kts` | `core.echo`, `core.error`, `core.milestone`, `core.sh` |
| `13-workspace-helpers.pipeline.kts` | `core.echo`, `core.isUnix`, `core.pwd`, `core.waitUntil` |
| `14-credentials-bindings.pipeline.kts` | `core.echo`, `core.sh` |
| `15-error.pipeline.kts` | `core.error` |
| `16-sleep.pipeline.kts` | `core.echo`, `core.sleep` |
| `17-writeFile.pipeline.kts` | `core.file.writeFile`, `core.sh` |
| `18-cleanWs.pipeline.kts` | `core.cleanWs`, `core.sh` |
| `19-isunix.pipeline.kts` | `core.echo`, `core.isUnix`, `core.sh` |
| `20-pwd-tmp.pipeline.kts` | `core.echo`, `core.pwd`, `core.pwd.tmp`, `core.sh` |
| `21-milestone.pipeline.kts` | `core.echo`, `core.error`, `core.milestone` |
| `22-wait-until.pipeline.kts` | `core.echo`, `core.sh`, `core.waitUntil` |
| `23-readfile.pipeline.kts` | `core.echo`, `core.file.writeFile`, `core.fileExists`, `core.readFile` |

Fixture verdicts (WU-LPR-103): 21 LIVE + 1 HISTORICAL (`05-scripted-if`, compile-reject pin); corpus gate 22/22 green including the typed-fail-close pin.

Registered keys WITHOUT a live corpus fixture: **1** (`core.emit.event`)

## 3. Disabled tests

Counts are OWNED by `WU_LPR_061_DISABLED_INVENTORY.md` (generator: `scripts/gen-disabled-inventory.py`).
MANDATORY_SUPPORTED there = 0, so no disabled test gates LPR-GATE-1.

## 4. Known gaps (pre-existing, tracked)

- UatLocal008 `CR-BD-027 CredentialUsed per use(Path)`: PRE_EXISTING on base `c29e3c1f` (reproduced in a clean worktree); CredentialUsed event emission per use() is NOT certified. Tracked as a defect WU; does not flip any admission row above.
- `pipeline credentials` / `pipeline version` / `pipeline doctor` / `pipeline events` CLI surface: DEFERRED per 032 §CLI.
