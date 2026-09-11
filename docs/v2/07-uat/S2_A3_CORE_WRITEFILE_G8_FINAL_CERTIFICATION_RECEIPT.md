# S2-A3 — `core.file.writeFile` G8 FINAL CERTIFICATION RECEIPT

Slice: LFC-2E1 / S2-A3 (`core.file.writeFile`)
Status: **CERTIFIED** (ADR-0074; burn-down G0→G8 complete)
Date: 2026-09-11T15:15:33Z
Branch: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
Head: `9ab67c72`

## Gate chain

| Gate | Commit | Summary |
| --- | --- | --- |
| G0+G1 | `02d09f34` | Baseline audit + `CoreWriteFileStep` registry candidate (typed I/O, `WORKSPACE_OPERATIONS_CAPABILITY`, `WorkspaceOperationsAdapter` over certified `FileWriteExecutor`) |
| G3/G4 | `7e36db93` | REGISTRY_PRIMARY: `core.file.writeFile` removed from `LEGACY_PLUGIN_IDS` (10→9) |
| G5 | `92810856` | LEGACY_REMOVED: legacy command subtype, decoder branch, metadata row, `CanonicalWriteFileNodeDispatcher` (+ test) physically deleted |
| G6 | `9ab67c72` | `WriteFileStepContractSuiteTest` 21/21 |
| G8 | this receipt | Installed-CLI certification |

## Counters (post-S2-A3 convergence)

```text
Certified Steps:           6   (core.echo, core.sh, core.error, core.sleep, core.file.writeFile, example.uppercase)
Legacy executable Steps:   9
Registry-primary Steps:    6
N + M = 15 = |LEGACY_PLUGIN_IDS| residuals (9) + certified (6)
```

## G8 evidence (installed distribution)

Environment: `JAVA_HOME=temurin-21.0.8+9.0.LTS`,
`v2/pipeline-application/build/install/pipeline-application` (fresh `installDist` at G8).
Fixture: `v2/compatibility/17-writeFile.pipeline.kts`
(`writeFile(file="out.txt", text="hello")` + `sh("cat out.txt")`).

### 1. Fresh execution — exit 0

- `RunFinished outcome=success`; output `out.txt` content `hello`,
  SHA-256 `2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824`.
- Event stream: `StepStarted/StepFinished(stepType=file)` via the registry path;
  `FileWritten` emitted solely by the `FileWriteExecutor` substrate (single emitter).

### 2. Replay (same `--db` / `--control-root`) — exit 0

Replay event listing (second `RunStarted` segment, same runId):

```text
StepStarted file  (re-executed: WRITES_WORKSPACE effectful rerun law, like core.sh)
StepFinished file
```

- The `sh` child is NOT re-executed (no `EchoOutputCaptured` in the replay segment):
  READ_ONLY reuse. This is the effect-family law of `DefaultEffectReplayPolicy`:
  `WRITES_WORKSPACE` re-materializes idempotently; `READ_ONLY` reuses.
- Workspace file after replay: byte-identical SHA-256 (`2cf24dba…9824`).

### 3. Negative control — fail closed

`writeFile(file = "", text = "x")` → exit 1, `Pipeline finished with FAILURE`
(`CoreWriteFileInput` blank-file invariant rejects before any workspace effect;
no file created).

### 4. Installed-JAR isolation (LEGACY_REMOVED is physical)

`unzip -l pipeline-application-*.jar | grep -i WriteFile` shows ONLY:

```text
CoreWriteFileInput.class / CoreWriteFileOutput.class / CoreWriteFileStep.class
CoreWriteFileStep$inputCodec$1 / $outputCodec$1 / $capabilityRoutedHandler$1 / $definition$1
```

`CanonicalWriteFileNodeDispatcher.class` is ABSENT from the installed artifact.

### 5. Static test evidence (fresh XML, 0 failures)

| Suite | Tests |
| --- | --- |
| WriteFileStepContractSuiteTest | 21/0 |
| CoreWriteFileRegistryPrimaryFitnessTest | 5/0 |
| CoreWriteFileStepUnitTest | 9/0 |
| S3WriteFileLegacyRemovedFitnessTest | 4/0 |
| CanonicalCoreStepCommandRegistryTest | 11/0 |
| CanonicalCoreStepDecoderTest | 3/0 |
| CoreErrorRegistryPrimaryFitnessTest | 14/0 |
| CoreSleepRegistryPrimaryFitnessTest | 6/0 |
| CompatibilityCorpusTest.fixture17 | 1/0 |
| S3SleepLegacyRemovedFitnessTest | 4/0 |
| S3ErrorLegacyRemovedFitnessTest | 12/0 |

Evidence digests: fresh.json `62e171e9deb6c14b…9bbb684960`,
replay.json `3f9a1e456ff871e8…0047f5b03`.

## Architectural notes certified

- Handler reaches the filesystem ONLY through the typed `WorkspaceOperations`
  capability (`WORKSPACE_OPERATIONS_CAPABILITY`); capability admission is
  fail-closed at `RegistryExecutionPreparation.prepare`.
- The typed `CoreWriteFileOutput` value and the durable console transcript are
  independent channels; the substrate stays the single `FileWritten` emitter.
- Zero production semantic changes: adding this Step required no coordinator,
  dispatcher, compiler, or metadata-table edits beyond the burn-down deletions.
- Envelope is byte-identical to the legacy dsl-v1 form
  `{"kind":"writeFile","file":…,"text":…,"encoding":…}` (durable fingerprint continuity).
