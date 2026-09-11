# S2-A1 / G6 — `core.error` LEGACY_REMOVED Receipt

**Cycle**: LFC-2E1 / S2 (legacy catalog burn-down)
**Branch**: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
**Author**: Ilargia (jcode)
**Closed**: 2026-09-11T11:31Z
**Authority**: S2 plan, AGENTS.md G0..G8 burn-down template, ADR-0070..0074, STEP_CONSTITUTION, S3EchoLegacyRemovedFitnessTest precedent

---

## Goal

Mechanically remove every legacy source representation of `core.error` so the Step
exists ONLY as an open `StepDefinition` (`CoreErrorStep`). After G6:

```text
core.error:
  REGISTERED          = true   (CoreErrorStep in CoreStepRegistryFactory.registry())
  REGISTRY_PRIMARY    = true   (StructuralFamilyResolver → Registry, registry owns the key)
  LEGACY_UNREACHABLE  = true   (production routing no longer reaches the legacy path)
  LEGACY_REMOVED      = true   (legacy source physically deleted from the source tree)
  CERTIFIED           = false  (G7 / G8 will mark it)
```

`LEGACY_PLUGIN_IDS` stays at **11** through G6 (the production routing authority
flipped at G5; G6 only deletes the now-unreachable legacy code).

---

## Production deletions (mechanical, source-level absence)

```text
CanonicalCoreStepCommand.Error                  (sealed subtype)         DELETED
ERROR_PLUGIN_ID                                 (decoder constant)       DELETED
ERROR_PLUGIN_ID -> { ... } branch               (decoder when)           DELETED
CanonicalErrorNodeDispatcher.kt                 (per-Step dispatcher)    DELETED
errorDispatcher field                           (facade dispatcher)      DELETED
errorDispatcher.dispatch(command) branch        (facade when)            DELETED
CanonicalCoreStepMetadata["core.error"] row     (legacy metadata)        DELETED
StepDescriptorRegistry.standard()["core.error"] (compile-time descriptor) DELETED
"core.error" in NON_CANONICAL_CANONICAL_BRIDGE_ERROR message string     REMOVED
```

NOT touched (per user directive):
- `CoreErrorStep` (registered `StepDefinition`)
- `CoreErrorOutput` (typed carrier)
- `CoreStepRegistryFactory.registry()` registration
- `RegistryExecutionPreparation` / `RegistryExecutionBoundary` (registry spine)
- `CommonExecutionBoundary` / `StructuralFamilyResolver`
- `LEGACY_PLUGIN_IDS` (unchanged at 11)

---

## Test deletions (legacy-class-dependent fixtures)

```text
CoreErrorLegacyRegistryParityTest.kt                       DELETED (14 active tests removed)
CanonicalErrorNodeDispatcherTest.kt                        DELETED (legacy dispatcher exercise)
CanonicalCoreStepDecoderTest.decodes_a_versioned_error_node REMOVED (legacy decoder case)
CanonicalCoreStepCommandRegistryTest.Error_*_test          REMOVED (sealed subtype gone)
                                                       sealedSubclasses.size: 12 -> 11
                                                       LEGACY_PLUGIN_IDS expected set updated
```

Preserved and refactored:
- `CoreErrorStepUnitTest` (G1) — 20/20 GREEN (carrier pattern intact)
- `CoreErrorRegistryPrimaryFitnessTest` (G5) — 14/14 GREEN, counter test rewritten to assert
  G6 final state (`LEGACY_PLUGIN_IDS = 11, metadata rows = 11, dispatchers = 11`)
- `CoreErrorMigrationReadinessFitnessTest` — archived (G4 pre-flip, historical evidence)
- `CoreErrorStepG2RegistryAdmissionTest` — archived (G2 transient assertions, historical evidence)
- `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` — negative pin moved from `core.error`
  to `core.sleep` (next legacy key, surgical flip proof)
- `RegistryStepMetadataResolverTest` — legacy key sample moved from `core.error` to `core.sleep`
- `PipelineRuleParityTest` — comment updated (no code change)

---

## Counter evidence (G6 close)

| Counter                              | Before (G5) | After (G6) |
| ------------------------------------ | ----------- | ---------- |
| `LEGACY_PLUGIN_IDS.size`             | 11          | 11 (unchanged; G6 does not touch) |
| `CanonicalCoreStepMetadata` rows     | 12          | **11** (core.error row deleted) |
| Per-Step dispatcher files in `durable/` | 12       | **11** (`CanonicalErrorNodeDispatcher.kt` deleted) |
| `CoreErrorStep.definition` in registry | YES        | YES (unchanged; permanent) |

```text
11 / 11 / 11  ← G6 close
```

The transition was:
- G4: 12 / 12 / 12 (legacy intact, registry ready)
- G5: 11 / 12 / 12 (registry primary, legacy still on disk)
- G6: 11 / 11 / 11 (legacy removed)

---

## Tests (L1 / L2 evidence)

### G6 new fitness: `S3ErrorLegacyRemovedFitnessTest` (12/12 GREEN) — `:pipeline-architecture-tests`

| Section | Test                                                                                  | Result |
| ------- | ------------------------------------------------------------------------------------- | ------ |
| (1)     | `core error is registered in the production StepRegistry`                            | PASS   |
| (2)     | `core error is NOT in the closed legacy authority LEGACY_PLUGIN_IDS`                  | PASS   |
| (2b)    | `LEGACY_PLUGIN_IDS is exactly the 11 residual keys (post-G6 full-set equality)`       | PASS   |
| (3)     | `core error is NOT decodable by CanonicalCoreStepDecoder`                             | PASS   |
| (4)     | `core error is NOT dispatched by CanonicalNodeDispatcher`                             | PASS   |
| (5)     | `core error is NOT in the legacy metadata table`                                      | PASS   |
| (6)     | `CanonicalErrorNodeDispatcher kt does NOT exist on disk`                              | PASS   |
| (cert)  | `certified registry-routed plugins are disjoint from LEGACY_PLUGIN_IDS`              | PASS   |
| (rule)  | `core error satisfies the LEGACY_REMOVED rule (id AND command AND decoder AND dispatcher AND metadata absent)` | PASS |
| (count) | `counter snapshot at G6 close -- LEGACY_PLUGIN_IDS equals the 11 residual legacy keys`     | PASS   |
| (count) | `counter snapshot at G6 close -- CanonicalCoreStepMetadata table keys equal the 11 residual legacy keys` | PASS |
| (count) | `counter snapshot at G6 close -- per-Step dispatcher files equal the 11 residual legacy dispatcher classes` | PASS |

Three independent counter snapshots (LEGACY_PLUGIN_IDS / metadata / dispatchers)
are now asserted via **full-set equality**, not just counts — accidental removals
or additions surface as discrete failures.

The dispatcher filenames are **hardcoded**, not derived from plugin ids. The
plugin id → class name mapping is not a contract; it is historical naming
accident. The fitness asserts the REAL residual structure.

The regex for metadata uses the concrete entry shape `"core.x" to StepMetadata(`,
not a fragile non-greedy `mapOf\(...\)` (which mis-cuts on nested parens). All
source reads go through `codeOnly(...)` so documentation mentions of deleted
symbols do not count as code.

### G5 fitness (preserved + counter updated): `CoreErrorRegistryPrimaryFitnessTest` (14/14 GREEN)

The `G5 counters` test was rewritten to assert the G6 final state:
`LEGACY_PLUGIN_IDS = 11, metadata rows = 11 (core.error deleted), dispatchers = 11
(file deleted)`. The other 13 tests are unchanged (registry primary, metadata
authority, prepare/coexecute, capability, identity).

### Targeted regression scope

| Test class                                                   | Count | Status |
| ------------------------------------------------------------ | ----- | ------ |
| `CoreErrorStepUnitTest` (G1)                                 | 20    | GREEN  |
| `CoreErrorRegistryPrimaryFitnessTest` (G5 + G6 counter)      | 14    | GREEN  |
| `CoreErrorMigrationReadinessFitnessTest` (G4, archived)      | 0 active / 16 archived | archived |
| `CoreErrorStepG2RegistryAdmissionTest` (G2, archived)        | 0 active / 6 archived  | archived |
| `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` (LB-02)             | 11    | GREEN  |
| `CanonicalCoreStepCommandRegistryTest` (refactored to 11)     | 13    | GREEN  |
| `CanonicalCoreStepDecoderTest` (4 cases)                     | 4     | GREEN  |
| `RegistryStepMetadataResolverTest` (refactored)              | 5     | GREEN  |
| `CoreShellStepTest` / `ShStepContractSuiteTest` / etc.       | runs  | GREEN  |

---

## Real-scenario evidence (L1)

### `v2/compatibility/15-error.pipeline.kts` — fresh run

```bash
./v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application \
    run --db /tmp/g6-error.db --control-root /tmp/g6-error-ctrl \
    v2/compatibility/15-error.pipeline.kts
```

```text
StepStarted  error-step/error-0 (stepType=error)
StepFailed   error-step/error-0 (failureKind=USER, message="test error message")
StepFinished error-step/error-0
RunFinished  outcome=failure
EXIT=1
```

### Replay (same `--db`/`--control-root`)

```text
StepStarted  error-step/error-0 (stepType=error)
StepFailed   error-step/error-0 (failureKind=INFRASTRUCTURE, message="Replay aborted for '...'")
StepFinished error-step/error-0
RunFinished  outcome=failure
EXIT=1
```

ReplayPolicy.NEVER semantic preserved across G6:
```text
fresh / no durable entry -> EXECUTE (typed failure, USER kind, "test error message")
existing durable history -> ABORT (no handler execution, INFRASTRUCTURE kind, "Replay aborted ...")
```

### Regression — `12-error-handling.pipeline.kts` (sh+warnError+catchError)

```text
StepFailed   ... sh (failureKind=SCRIPT, message="shell exited with code 1")
RunFinished  outcome=unstable
EXIT=0
```

### Pre-existing failure (NOT a G6 regression)

`CompatibilityCorpusTest.fixture14CredentialsBindings()` and
`UatCompat001CorpusSmokeRunTest.corpus smoke-runs green and satisfies M2 exit criterion()`
both fail because `14-credentials-bindings.pipeline.kts` exits 1. This is a
**pre-existing failure** confirmed by fresh base-vs-head evidence:

```text
BASE SHA c6783f95 (pre-S2-A1, post-S1 integration):
  ./pipeline-application run ... 14-credentials-bindings.pipeline.kts
  EXIT_BASE=1
  Pipeline finished with FAILURE

HEAD SHA (this commit, post-G6):
  ./pipeline-application run ... 14-credentials-bindings.pipeline.kts
  EXIT=1
  Pipeline finished with FAILURE
```

The same fixture fails on the cycle base SHA, so it is not introduced by the
S2-A1 burn-down. Per AGENTS rule 16: "Never classify a failure as pre-existing
without fresh base-vs-head evidence (worktree method)" — done.

This is one of the four pre-existing compatibility/UAT failures noted at the
start of the LFC-2E1 cycle closure (UatLocal008 / UatLocal009 / corpus 14),
which remain out of S2-A1 scope.

---

## Closed-irreversible semantics

After G6, the following invariants hold **permanently** (mechanically defended by
`S3ErrorLegacyRemovedFitnessTest`):

```text
LEGACY_REMOVED(core.error) =
    legacy id absent
  ∧ legacy command absent
  ∧ legacy decoder absent
  ∧ legacy dispatcher absent
  ∧ legacy metadata absent
```

Any future change that reintroduces any of these forms will fail the fitness
suite with a discrete failure (the test class is in `:pipeline-architecture-tests`
so it runs in the architecture verification gate, not just the per-module gates).

`CERTIFIED ∩ LEGACY_EXECUTABLE = ∅` — the registry-routed set
`{core.echo, core.sh, core.error}` is provably disjoint from `LEGACY_PLUGIN_IDS`.

`REGISTRY_PRIMARY = {core.echo, core.sh, core.error}` — proven by
`CoreErrorRegistryPrimaryFitnessTest.G5 flip -- registry primary keys are core echo,
core sh, core error (positive control)`.

---

## What does NOT exist at G6 (forbidden)

```text
- CanonicalCoreStepCommand.Error sealed subtype
- ERROR_PLUGIN_ID decoder constant or decoder when-branch for "core.error"
- CanonicalErrorNodeDispatcher.kt on disk
- errorDispatcher field / errorContext() helper / CanonicalCoreStepCommand.Error
  branch in CanonicalNodeDispatcher
- "core.error" row in CanonicalCoreStepMetadata.table
- "core.error" entry in StepDescriptorRegistry.standard()
- "core.error" in NON_CANONICAL_CANONICAL_BRIDGE_ERROR message string
- A parallel dispatcher / decoder / metadata authority for core.error
```

---

## Counter dashboard (project-wide)

```text
Certified Steps:             3  (core.echo, core.sh, core.error)
Legacy executable Steps:    11  (S2 burn-down pending; counter unchanged through G6)
Registry-primary Steps:      3  (S2-A1 complete)
```

S2-A1 / G6 closes the S2-A1 slice but `core.error = CERTIFIED` still requires
G7 (StepContractSuite 16/17 coverage) + G8 (real executable certification
scenario) — separate GO.

---

## Open items / next gates

- **G7 (StepContractSuite)** — 16/17 coverage for `core.error` (separate GO).
- **G8 (real executable certification scenario)** — closes S2-A1 with
  `core.error = CERTIFIED` (separate GO).
- **S2-A2** (`core.sleep`, 11 → 10 on LEGACY_PLUGIN_IDS) awaits its own cycle
  branch after S2-A1 is fully CERTIFIED.

---

## Closing receipt

S2-A1 / G6 is closed. The legacy source representations of `core.error` are
physically deleted from the source tree (5 production sites + 4 test sites).
The Step now exists ONLY as an open `StepDefinition` (`CoreErrorStep`)
registered through `CoreStepRegistryFactory`. The `S3ErrorLegacyRemovedFitnessTest`
in `:pipeline-architecture-tests` (12/12 GREEN) mechanically defends the
irreversible invariants: LEGACY_REMOVED rule, registry disjointness, three
independent counter snapshots (LEGACY_PLUGIN_IDS / metadata / dispatchers)
via full-set equality. The 15-error pipeline scenario remains green on both
fresh execution and replay (ReplayPolicy.NEVER ABORT). The two pre-existing
failures (fixture14CredentialsBindings, UatCompat001 aggregate) are confirmed
NOT regressions via fresh base-vs-head evidence. Awaits GO for G7.
