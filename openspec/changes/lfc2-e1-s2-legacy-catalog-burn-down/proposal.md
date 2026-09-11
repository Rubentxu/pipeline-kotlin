# S2-A1 — core.error — registry burn-down

## Why

LFC-2E1-S1 closed `core.echo` as `CERTIFIED + LEGACY_REMOVED` and proved the burn-down
pattern (registry implementation + parity + legacy unreachable + legacy removed +
certification = closure). S2 applies the same pattern to the 12 legacy keys still
executable through `CanonicalCoreStepDecoder` + `CanonicalCoreStepMetadata` + per-Step
`CanonicalXxxNodeDispatcher`.

S2-A1 is the **first slice**: `core.error`. It must close before S2-A2 (`core.sleep`)
opens.

The directive from the user:

```text
S2-A1 core.error
  G0 → G8
  legacy count 12 → 11
  commit/checkpoint
```

## What changes

1. **New** `CoreErrorStep` registry StepDefinition with typed `ErrorInput` (and no
   typed output value — the typed handler outcome is `StepOutcome.Failure` directly).
2. **Edit** `CoreStepRegistryFactory` to register `CoreErrorStep`.
3. **New** `ErrorStepContractSuiteTest` (the contractual rows for the failure-bearing Step).
4. **New** `S3ErrorLegacyRemovedFitnessTest` (structural G4 architecture fitness).
5. **Edit** `CanonicalCoreStepDecoder`:
   - Remove `data class Error` from `CanonicalCoreStepCommand`.
   - Remove `ERROR_PLUGIN_ID` constant.
   - Remove the `core.error` `when` branch.
   - Remove `"core.error"` from `LEGACY_PLUGIN_IDS`.
6. **Edit** `CanonicalCoreStepMetadata` — remove the `core.error` row.
7. **Delete** `CanonicalErrorNodeDispatcher.kt` (the entire file).
8. **Refixture** existing tests that exercised the legacy path so they exercise the
   registry path with the same observable contract.
9. **Reuse** `v2/compatibility/15-error.pipeline.kts` (no new example).

## What does NOT change

- `core.echo` and `core.sh` (already CERTIFIED + LEGACY_REMOVED, untouched).
- The 11 other legacy keys.
- `FailureKind`, `PipelineFailure`, `StepOutcome`, `RunOutcome` — the typed failure
  taxonomy is the contract; we route through it, we do not redefine it.
- `EVT-4/M4` plumbing.
- The general failure-classification matrix in `ScriptedRuntime.failureKindToException`.

## Counter delta

```text
LEGACY_PLUGIN_IDS  : 12 → 11
metadata rows       : 12 → 11
dispatcher classes  : 12 → 11
```

All three sets must remain aligned at 11 (and must contain exactly the same 11 keys:
`sleep, file.writeFile, emit.event, milestone, deleteDir, cleanWs, load, pwd, isUnix,
waitUntil, archiveArtifacts`).

## Exit criterion

```text
core.error:
  delivery:    CORE
  execution:   REGISTRY_PRIMARY
  legacy:      REMOVED
  certification: CERTIFIED

proof:
  - G4 architecture fitness: S3ErrorLegacyRemovedFitnessTest N/N GREEN
  - G7 StepContractSuite:    ErrorStepContractSuiteTest     M/M GREEN
  - core.error tests:        refixtured legacy tests, P/P GREEN
  - real scenario:           v2/compatibility/15-error.pipeline.kts
                              exit=1, 1×StepFailed(USER,"test error message")
```

Then `STOP` and report before opening S2-A2.
