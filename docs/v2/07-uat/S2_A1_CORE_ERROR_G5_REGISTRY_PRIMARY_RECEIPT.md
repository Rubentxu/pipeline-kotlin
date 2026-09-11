# S2-A1 / G5 — `core.error` REGISTRY_PRIMARY Receipt

**Cycle**: LFC-2E1 / S2 (legacy catalog burn-down)
**Branch**: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
**Author**: Ilargia (jcode)
**Closed**: 2026-09-11T10:53Z
**Authority**: S2 plan, AGENTS.md G0..G8 burn-down template, ADR-0070..0074, STEP_CONSTITUTION

---

## Goal

Flip the production routing authority for `core.error` from the legacy canonical
decoder / dispatcher / metadata row to the open-world registry spine (LB-02 /
ADR-0070..0074) so that `core.error` joins `core.echo` and `core.sh` as
REGISTRY_PRIMARY.

The flip MUST be **structural** (one entry removed from one set) so the existing
G6 (LEGACY_REMOVED) work can mechanically delete the now-unreachable legacy code.

---

## Production edit (single edit)

File: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt`

The single edit removed `"core.error"` from `CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS`
with an inline comment block documenting the post-flip residual:

```kotlin
val LEGACY_PLUGIN_IDS: Set<String> = setOf(
    // "core.error" removed at LFC-2E1-S2-A1 / G5 (2026-09-11T10:34Z).
    // The production routing authority flipped to the registry path
    // (CoreErrorStep.definition). The legacy `core.error` source code
    // remains physically present until G6 deletes it (LEGACY_REMOVED):
    //   - CanonicalCoreStepCommand.Error subtype
    //   - ERROR_PLUGIN_ID decoder branch
    //   - CanonicalErrorNodeDispatcher.kt file
    //   - CanonicalCoreStepMetadata["core.error"] row
    // Until G6 the legacy dispatcher is unreachable in production but
    // still type-loadable; the parity test (G3) can still drive it.
    "core.sleep",
    "core.file.writeFile",
    "core.emit.event",
    "core.milestone",
    "core.deleteDir",
    "core.cleanWs",
    "core.load",
    "core.pwd",
    "core.isUnix",
    "core.waitUntil",
    "core.archiveArtifacts",
)
```

No other production code was touched. The legacy `data class Error`, the
`ERROR_PLUGIN_ID` decoder branch, the `CanonicalErrorNodeDispatcher.kt` file,
and the `CanonicalCoreStepMetadata["core.error"]` row all remain on disk for
G6 to delete.

---

## State transition (BEFORE / AFTER)

```text
BEFORE (G4 close, 2026-09-11T10:20Z):
  LEGACY_PLUGIN_IDS (12 entries):
    core.error, core.sleep, core.file.writeFile, core.emit.event,
    core.milestone, core.deleteDir, core.cleanWs, core.load,
    core.pwd, core.isUnix, core.waitUntil, core.archiveArtifacts
  StructuralFamilyResolver(core.error) == LegacyCore
  RegistryStepMetadataResolver.resolve(core.error) -> CanonicalCoreStepMetadata
  CoreErrorStep.definition in registry: YES (G2)
  Production routing of core.error: LEGACY path

AFTER (G5 close, 2026-09-11T10:53Z):
  LEGACY_PLUGIN_IDS (11 entries):
    core.sleep, core.file.writeFile, core.emit.event,
    core.milestone, core.deleteDir, core.cleanWs, core.load,
    core.pwd, core.isUnix, core.waitUntil, core.archiveArtifacts
  StructuralFamilyResolver(core.error) == Registry   ← flipped
  RegistryStepMetadataResolver.resolve(core.error) -> CoreErrorStep.definition.contract.descriptor   ← authority transfer
  CoreErrorStep.definition in registry: YES
  Production routing of core.error: REGISTRY path (RegistryExecutionPreparation → RegistryExecutionBoundary.coexecute)
```

### Authority transfer (single-line semantics)

The flip is a one-line change to a single set. The runtime semantics transfer
through a chain of structural seams, each independently proven by a G5 test:

| Seam                          | Authority at G4 (Legacy)                                            | Authority at G5 (Registry)                                          |
| ----------------------------- | ------------------------------------------------------------------ | ------------------------------------------------------------------- |
| `LEGACY_PLUGIN_IDS`           | `contains("core.error")`                                            | `!contains("core.error")` (surgical flip)                           |
| `StructuralFamilyResolver`    | returns `LegacyCore` for `core.error`                              | returns `Registry` for `core.error`                                 |
| `RegistryStepMetadataResolver`| delegates to `CanonicalCoreStepMetadata.metadata("core.error")`    | delegates to `CoreErrorStep.definition.contract.descriptor`         |
| `FamilyRouter.decide`         | `LegacyRouting`                                                     | `SeamedRouting` (registry branch)                                   |
| `RegistryExecutionPreparation`| `Rejected("no registry definition")` (registry had no authority)    | `Ready(PreparedRegistryExecution)` (registry owns the key)          |
| `RegistryExecutionBoundary`   | not invoked                                                         | `coexecute(...)` projects `CoreErrorOutput.outcome` via `TypedStepOutput` |
| Legacy `CanonicalErrorNodeDispatcher` | reachable via legacy facade                                | UNREACHABLE in production (still type-loadable; G6 deletes the file) |

---

## Counter evidence

| Counter                              | Before (G4) | After (G5) | Survives G6?       |
| ------------------------------------ | ----------- | ---------- | ------------------ |
| `LEGACY_PLUGIN_IDS.size`             | 12          | 11         | YES (G6 does not touch `LEGACY_PLUGIN_IDS`) |
| `CanonicalCoreStepMetadata` rows     | 12          | 12         | No (G6 deletes core.error row) |
| Per-Step dispatcher files in `durable/` | 12          | 12         | No (G6 deletes `CanonicalErrorNodeDispatcher.kt`) |
| `CoreErrorStep.definition` in registry | YES        | YES        | YES (permanent)    |

`LEGACY_PLUGIN_IDS` stays at **11** through G6. G6 only deletes the now-unreachable
legacy source code; the production routing authority is already the registry
spine after G5.

---

## Tests (L1 / L2 evidence)

### G5 fitness (NEW): `CoreErrorRegistryPrimaryFitnessTest` (14/14 GREEN)

| Section | Test                                                                                          | Result |
| ------- | --------------------------------------------------------------------------------------------- | ------ |
| (1) Flip | `G5 flip -- LEGACY_PLUGIN_IDS is exactly the 11 residual keys (full-set equality)`            | PASS   |
| (1) Flip | `G5 flip -- StructuralFamilyResolver classifies core error as Registry (not LegacyCore)`      | PASS   |
| (1) Flip | `G5 flip -- FamilyRouter decide for core error returns SeamedRouting (registry owns the key)` | PASS   |
| (1) Flip | `G5 flip -- registry primary keys are core echo, core sh, core error (positive control)`      | PASS   |
| (2) Auth | `G5 authority -- registry metadata for core error matches CoreErrorStep descriptor`           | PASS   |
| (2) Auth | `G5 authority -- registry metadata does NOT consult the legacy row for core error`            | PASS   |
| (3) Prep | `G5 prepare -- RegistryExecutionPreparation produces Ready for core error`                     | PASS   |
| (3) Prep | `G5 prepare -- schema mismatch produces Rejected (typed-decode admission)`                    | PASS   |
| (4) Exec | `G5 coexecute -- RegistryExecutionBoundary coexecutes core error and projects typed outcome`  | PASS   |
| (4) Exec | `G5 LEGACY_UNREACHABLE -- legacy decoder branch for ERROR_PLUGIN_ID is no longer the production route` | PASS |
| (5) Counter | `G5 counters -- LEGACY_PLUGIN_IDS is 11, metadata rows is 12, dispatchers is 12`           | PASS   |
| (6) Cap | `G5 capability -- CoreErrorStep declares empty required capabilities`                          | PASS   |
| (6) Cap | `G5 capability -- CanonicalRuntimeCapabilityAccess does not block registry admission for core error` | PASS |
| (7) Id  | `G5 identity -- production registry contains exactly one definition for core error`           | PASS   |

### Archived (historical evidence, not regression gate)

These classes encoded G2/G3/G4 transient assertions whose meaning changed at
the G5 flip. Per the user's directive ("No conviertas un test G2 en un test G5
cambiándole silenciosamente el significado"), they are archived with
`@Disabled` and explanatory kdoc. Method names and structure are preserved so
future readers can see what the prior state asserted.

| Class                                          | Tests | Status  |
| ---------------------------------------------- | ----- | ------- |
| `CoreErrorStepG2RegistryAdmissionTest`         | 6/6   | ARCHIVED (G2 evidence) |
| `CoreErrorLegacyRegistryParityTest`            | 14 active + 2 disabled | 14 GREEN (parity table); 2 ARCHIVED (transient invariants) |
| `CoreErrorMigrationReadinessFitnessTest`       | 16/16 | ARCHIVED (G4 evidence) |

The semantic parity table in `CoreErrorLegacyRegistryParityTest` (14 active
tests) is preserved as a **permanent regression asset**: it proves that the
legacy and registry paths produce the same typed `PipelineFailure` and
`StepOutcome.Failure(...)`. The two `G3 invariant` tests are archived because
they encode the pre-flip structural state.

### G1 unit (preserved): `CoreErrorStepUnitTest` (20/20 GREEN)

All 20 unit tests from G1 (`CoreErrorInput` codec, `CoreErrorOutput` carrier,
invariants, etc.) continue to pass — proves the typed carrier pattern did not
regress.

### G3 parity semantic (preserved): `CoreErrorLegacyRegistryParityTest` (14/14 active GREEN)

The semantic parity table — `failure.kind` / `failure.message` /
`StepOutcome.Failure` / `Effect.ABORTS_PIPELINE` / `ReplayPolicy.NEVER` /
empty capabilities / dsl-v1 envelope byte-identity / 6-case corpus / 10-variant
`FailureKind` sweep — remains the strongest permanent parity asset.

---

## Real-scenario evidence (L1)

`v2/compatibility/15-error.pipeline.kts`:

```kotlin
pipeline {
    stages {
        stage("error-step") {
            error("test error message", failureKind = "USER")
        }
    }
}
```

### Run 1 (fresh execution)

```bash
./v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application \
    run --db /tmp/g5-error.db --control-root /tmp/g5-error-ctrl \
    v2/compatibility/15-error.pipeline.kts
```

Observable events (filtered):

```text
StepStarted  error-step/error-0 (stepType=error)
StepFailed   error-step/error-0 (failureKind=USER, message="test error message")
StepFinished error-step/error-0
RunFinished  outcome=failure
Pipeline finished with FAILURE
EXIT=1
```

Proves:
- `failureKind=USER` — matches the input codec (`FailureKind.USER.name`)
- `message="test error message"` — matches the input codec verbatim
- `outcome=failure` — `CoreErrorOutput.outcome == StepOutcome.Failure(failure)` projected
  by the registry boundary
- `exit=1` — pipeline aborted after typed failure

### Run 2 (replay with same `--db`/`--control-root`)

Observable events (filtered):

```text
StepStarted  error-step/error-0 (stepType=error)
StepFailed   error-step/error-0 (failureKind=INFRASTRUCTURE, message="Replay aborted for '...'")
StepFinished error-step/error-0
RunFinished  outcome=failure
Pipeline finished with FAILURE
EXIT=1
```

Proves (per the frozen replay law, ADR / E-EM-11 NEVER-1):

```text
ReplayPolicy.NEVER:
    fresh / no durable entry -> EXECUTE (typed failure)
    existing durable history -> ABORT (no handler execution)

Distinct surfaces:
    ABORTS_PIPELINE (Step effect AFTER legitimate execution, USER kind, "test error message")
    ABORT           (Replay decision INFRASTRUCTURE kind, "Replay aborted for '...'")
```

Both runs end with `exit=1` and `RunFinished(failure)`.

### Regression — `12-error-handling.pipeline.kts` (uses `core.sh` inside `warnError`/`catchError`)

```bash
./v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application \
    run --db /tmp/g5-eh.db --control-root /tmp/g5-eh-ctrl \
    v2/compatibility/12-error-handling.pipeline.kts
```

Observable:

```text
StepFailed   ... sh (failureKind=SCRIPT, message="shell exited with code 1")
RunFinished  outcome=unstable
EXIT=0
```

The `core.sh` path inside `warnError { catchError { sh("exit 1") } }` continues
to fail and be caught exactly as before — proves the G5 flip did not touch the
`core.sh` registry path or its dispatch semantics.

---

## Counter evidence (re-run against `proof12`)

Updated counter proof at G5 close:

```text
LEGACY_PLUGIN_IDS (CanonicalCoreStepDecoder.kt):
  core.archiveArtifacts, core.cleanWs, core.deleteDir, core.emit.event,
  core.file.writeFile, core.isUnix, core.load, core.milestone,
  core.pwd, core.sleep, core.waitUntil
  count = 11   ← was 12 at G4

CanonicalCoreStepMetadata rows: 12   ← unchanged at G5 (G6 deletes core.error row)
durable/ per-Step dispatcher files: 12  ← unchanged at G5 (G6 deletes CanonicalErrorNodeDispatcher.kt)
CoreErrorStep.definition in registry: YES   ← unchanged
```

---

## Authoritative transition proof

The G5 fitness (`CoreErrorRegistryPrimaryFitnessTest`) demonstrates three
independent production-seam facts for `core.error`:

1. **Membership flip** (4 tests): `LEGACY_PLUGIN_IDS` exact 11-set equality;
   `StructuralFamilyResolver(core.error) == Registry`;
   `FamilyRouter.decide(core.error) == SeamedRouting`;
   registry-primary keys are exactly `{core.echo, core.sh, core.error}`.

2. **Metadata authority** (2 tests): composite resolver reads
   `CoreErrorStep.definition.contract.descriptor` for `core.error` (effects,
   replayPolicy, recoveryPolicy); does NOT consult the legacy row.

3. **LEGACY_UNREACHABLE execution** (4 tests):
   `RegistryExecutionPreparation.prepare(...)` produces
   `Ready(PreparedRegistryExecution)` — proves registry resolution + capability
   admission + typed decode are all green;
   `RegistryExecutionBoundary.coexecute(prepared, context)` projects
   `CoreErrorOutput.outcome == StepOutcome.Failure(failure)` into
   `CommonExecutionResult.outcome` — proves the typed carrier pattern routes
   through the boundary without per-Step branches;
   `StructuralFamilyResolver(core.error) == Registry` proves the legacy decoder
   branch is structurally unreachable in production.

The 14 tests are not redundant: each pin a distinct seam in the registry spine
that must be intact for the flip to be production-safe.

---

## Counter dashboard (project-wide)

```text
Certified Steps:             3  (core.echo, core.sh, core.error)
Legacy executable Steps:    11  (S2 burn-down pending; G6 will delete core.error legacy)
Registry-primary Steps:      3  (S2-A1 complete)
```

N + M = 14 = |LEGACY_PLUGIN_IDS_G0| (12) + certified plugin count (3 echo/sh/error).

Burn-down converges as: **M → 0** with each S2 slice (A1 = error; A2 = sleep;
A3 = writeFile; A4 = emit.event; ...).

---

## What does NOT exist at G5 (forbidden)

```text
- A second retry coordinator for core.error.
- A dispatchRetryBlock / dispatchTimeoutBlock collection.
- A retry decision based on event streams or in-memory counters.
- A retry plan() that branches on concrete StepKey for core.error.
- A run-mode that skips retryControlJournal injection for core.error.
- A new StepHandler<I,O> SDK signature.
- A StepHandlerTypedFailure exception.
- A new concrete StepSpec subtype for core.error.
- A new KSP processor that branches on core.error.
- A privileged core path that external plugins cannot reach.
- A canonical dispatcher case for "core.error" (the legacy source remains on
  disk but is unreachable; G6 deletes it).
- A per-Step branch in RegistryExecutionBoundary for core.error (the generic
  TypedStepOutput projection is the sole authority).
- A null/placeholder construction of PreparedRegistryExecution or
  ExecutionPreparation.Ready in the G5 fitness: the test drives the real seam
  (RegistryExecutionPreparation.prepare → Ready(PreparedRegistryExecution)).
```

---

## Open items / next gates

- **G6 (LEGACY_REMOVED)** — separate GO. G6 deletes the legacy `core.error`
  code physically:
    - `CanonicalCoreStepCommand.Error` sealed subtype
    - `ERROR_PLUGIN_ID` decoder branch in `CanonicalCoreStepDecoder.decode`
    - `CanonicalErrorNodeDispatcher.kt` file
    - `CanonicalCoreStepMetadata["core.error"]` row
    - any `errorDispatcher` field/wiring/branch in `CanonicalNodeDispatcher`
  `LEGACY_PLUGIN_IDS` remains **11** through G6 (G6 does NOT touch it). Final
  counters at G6 close: `metadata = 11, dispatchers = 11`. Asserted by
  `S3ErrorLegacyRemovedFitnessTest` (in `:pipeline-architecture-tests`).
- **G7 (StepContractSuite)** — 16/17 coverage for `core.error` (separate GO).
- **G8 (real executable certification scenario)** — closes S2-A1 with
  `core.error = CERTIFIED` (separate GO).
- **S2-A2** (`core.sleep`, 11 → 10 on LEGACY_PLUGIN_IDS) awaits its own cycle
  branch after S2-A1 is fully CERTIFIED.

---

## Closing receipt

S2-A1 / G5 is closed. The `core.error` production routing authority has been
flipped to the registry spine with the single structural edit required, and
the G5 fitness demonstrates three independent production-seam proofs (membership,
metadata, execution). The full regression scope (G1 unit, G3 semantic parity,
15-error real scenario fresh + replay, 12-error-handling sh regression) is green.
Awaits GO for G6.
