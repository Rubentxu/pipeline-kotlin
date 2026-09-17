# LFC-2E0 Zero Legacy Residual — Closure Receipt

**Cycle:** LFC-2E0 (Step Inventory Truth & First Zero Legacy Residual)
**Date:** 2026-09-17
**Branch:** `cycle/wu-g5b`
**Commits in this closure:** `a31cc8c6` (WU-G5B), `0be16af2` (CORE-LOAD-REJECTED)
**Status:** **CLOSED — FIRST ZERO LEGACY RESIDUAL achieved**

---

## 1. Result

The V2 Step execution spine has reached **FIRST ZERO LEGACY RESIDUAL**:

```text
LEGACY_PLUGIN_IDS       = setOf()        (verified line 59 of CanonicalCoreStepMetadata.kt)
Legacy executable Steps = 0
Registry-primary Steps  = 12 (CERTIFIED) + 2 (STOPPED_G7)
External references     = 1 (example.uppercase)
Rejected Steps          = 1 (core.load)
Total production keys   = 14 (12+2+1 - 1 sample/example uppercase)
```

The canonical production authority for every Step key is now either:

1. The **registry seam** (`StepRegistry` → `StepDefinition` → `StepHandler` → declared
   capabilities → durable engine → typed result/events), or
2. The **canonical RepeatUntil machinery** (`core.waitUntil`, ADR-0073 body-reentry path).

There is no remaining production path that routes through `CanonicalCoreStepCommand`
subtypes or `Canonical*NodeDispatcher`.

---

## 2. Counter rollup

| Category | Count | Keys |
|---|---:|---|
| CERTIFIED + LEGACY_REMOVED | **12** | core.echo, core.sh, core.error, core.sleep, core.file.writeFile, core.emit.event, core.isUnix, core.deleteDir, core.milestone, core.cleanWs, core.archiveArtifacts, core.waitUntil |
| EXTERNAL_REFERENCE (CERTIFIED plugin) | **1** | example.uppercase |
| STOPPED_G7 | **2** | core.pwd, core.pwd.tmp |
| REJECTED | **1** | core.load |
| LEGACY_REMOVED (historical) | (12) | all 11 legacy Step keys + load = REJECTED → 0 executable legacy |
| LEGACY_EXECUTABLE | **0** | (none — counter is empty) |

`M → 0` per the canonical counter law (where `N + M = total` and `N = registry-primary
Steps`). Convergence achieved.

---

## 3. Per-slice timeline

| Slice | Date | Commit | Result | Counter after |
|---|---|---|---|---|
| S1 (pre-cycle) | 2026-08 | (historical) | 11 LEGACY_EXECUTABLE + 2 CERTIFIED (echo, sh) | 2/11/0 |
| S2-A1 (core.error) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 3/10/0 |
| S2-A2 (core.sleep) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 4/9/0 |
| S2-A3 (core.writeFile) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 5/8/0 |
| S2-A4 (core.emit.event) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 6/7/0 |
| S2-A5 (core.isUnix) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 7/6/0 |
| S2-A6 (core.pwd) | 2026-09 | (closed earlier) | LEGACY_REMOVED; G7 BLOCKED → STOPPED_G7 | 7/5/0 |
| S2-A7 (core.deleteDir) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 8/5/0 |
| S2-A9 (core.milestone) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 9/5/0 |
| S2-A10 (core.cleanWs) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 10/5/0 |
| S2-B10 (core.archiveArtifacts) | 2026-09 | (closed earlier) | LEGACY_REMOVED + CERTIFIED | 11/5/0 |
| WU-G5B (core.waitUntil) | 2026-09-17 | `a31cc8c6` | LEGACY_REMOVED + CERTIFIED | 12/4/0 |
| CORE-LOAD-REJECTED | 2026-09-17 | `0be16af2` | 6 legacy forms deleted; load rejected | **13/2/1** |

Final: **CERTIFIED = 13 (12 core + 1 external); LEGACY_EXECUTABLE = 0; REJECTED = 1**.

---

## 4. Commits in this cycle slice

### WU-G5B — core.waitUntil closure (`a31cc8c6`)

Burned down `core.waitUntil` from `IMPLEMENTED_UNCERTIFIED` to `CERTIFIED + LEGACY_REMOVED`.
Deleted:

- `CanonicalCoreStepCommand.WaitUntil` subtype
- `WAIT_UNTIL_PLUGIN_ID` decoder branch + constant
- `CanonicalCoreStepMetadata["core.waitUntil"]` row
- `CanonicalWaitUntilNodeDispatcher.kt`
- `CanonicalNodeDispatcher` waitUntil seam
- `WaitUntilStepTest.kt` and other 3 dependent tests

Production routing is exclusively the canonical RepeatUntil machinery (ADR-0073).
`CoreWaitUntilStep.kt` is retained as the typed codec source for `WaitUntilPolled` and
`WaitUntilCompleted` events (not a registry handler). Receipt:
`docs/v2/07-uat/S3_WU_G5B_CORE_WAITUNTIL_BURNDOWN_RECEIPT.md`.

### CORE-LOAD-REJECTED (`0be16af2`)

Five converging signals classified `core.load` as REJECTED (not LEGACY_REMOVED):

1. Silent no-op dispatcher (legacy execution path did nothing).
2. Latent contract defect: `Load` payload had no `path` field, so the contract was
   structurally un-implementable without a redesign.
3. `UatLocal011::SC-011-11 @Disabled` (INC-024) — the only behavioral test existed only
   to be skipped.
4. Zero `load(...)` usage anywhere in the codebase (corpus scan: 0 hits).
5. SPIKE-018 §1.3 declares `core.load` the **LAST** legacy lift requiring new infrastructure
   (`SCRIPT_COMPILATION_CAPABILITY` + `BODY_INVOKER_CAPABILITY`) out of LFC-2 scope.

Deleted:

- `CanonicalCoreStepCommand.Load` subtype
- `LOAD_PLUGIN_ID` decoder branch + constant
- `CanonicalCoreStepMetadata["core.load"]` row
- `CanonicalLoadNodeDispatcher.kt`
- DSL `fun PipelineScope.load(path: String): StepSpec.Load` from `PipelineDsl.kt`
- `StepSpec.Load` sealed type
- `ExecutionBoundaryFactoryTest.kt` entirely (deleted all 7 tests that depended on
  `Load` subtype)

DSL-side deletion is enforced: any `.pipeline.kts` calling `load(...)` now fails at
DSL compile time (`Unresolved reference 'load'`) — fail-closed as required by the
Step Constitution.

Receipt: `docs/v2/07-uat/S2_A5_CORE_LOAD_REJECTION_RECEIPT.md`.

---

## 5. Fitness evidence

### Lfc2ZeroLegacyResidualFitnessTest — 7/7 GREEN

Created at CORE-LOAD-REJECTED slice to mechanically verify **FIRST ZERO LEGACY RESIDUAL**:

```text
[1/7] legacyPluginIdsIsEmpty
        CanonicalCoreStepMetadata.LEGACY_PLUGIN_IDS == setOf()

[2/7] noLoadSubtypeInCanonicalCoreStepCommand
        CanonicalCoreStepCommand::class sealed members ⊄ {Load}

[3/7] noLoadPluginIdConstant
        no file in pipeline-application contains "LOAD_PLUGIN_ID" string

[4/7] noCanonicalLoadNodeDispatcherFile
        CanonicalLoadNodeDispatcher.kt does not exist on filesystem

[5/7] noLoadDslFunctionInPipelineDsl
        PipelineDsl.kt has no `fun load(`

[6/7] noLoadSealedSubtypeInStepSpec
        StepSpec::class sealed members ⊄ {Load}

[7/7] loadRejectionIsFailClosed
        A `.pipeline.kts` calling `load(...)` fails at DSL compile time
```

### L4 architecture fitness (Lfc2RegistryFamilyFitnessTest) — green

The canonical registry-aware architecture remains green after both slices; no
regression introduced by removing load or waitUntil legacy paths.

---

## 6. Real-CLI canary

```bash
# core.* registry Steps execute via canonical path
$ ./gradlew -p v2 installDist
$ echo 'pipeline { stages { stage("s") { steps { echo("ok") } } } }' > /tmp/ok.pipeline.kts
$ ./v2/pipeline-application/build/install/pipeline/bin/pipeline -f /tmp/ok.pipeline.kts
# → SUCCESS, exit 0

# core.waitUntil via canonical RepeatUntil machinery
$ cat > /tmp/wu.pipeline.kts << 'EOF'
pipeline {
  stages {
    stage("s") {
      steps {
        waitUntil {
          script { return shStdout("echo done").trim() == "done" }
        }
      }
    }
  }
}
EOF
$ ./v2/pipeline-application/build/install/pipeline/bin/pipeline -f /tmp/wu.pipeline.kts
# → SUCCESS, exit 0

# core.load is fail-closed at DSL compile time
$ cat > /tmp/load.pipeline.kts << 'EOF'
pipeline { stages { stage("s") { steps { load("/tmp/x") } } } }
EOF
$ ./v2/pipeline-application/build/install/pipeline/bin/pipeline -f /tmp/load.pipeline.kts
# → FAILED, exit 1
# error: Unresolved reference 'load'
```

---

## 7. Mandatory disabled tests

The Step Constitution forbids recording DONE/PASS for an uncertified Step. The
rejection of `core.load` reclassifies the only behavioral test that existed for it:

- `UatLocal011::SC-011-11` (was `@Disabled` INC-024 "deferred") → reclassified
  `OBSOLETE-per-REJECTION` per AGENTS.md ADR-0074 (`IMPLEMENTED_UNCERTIFIED` / quarantine
  rules). No Step can be quarantined post-rejection — the contract surface no longer
  exists.

The receipt for this reclassification is preserved in
`docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` (mandatory disabled tests section).

---

## 8. Canonical files (machine-readable + human-readable)

Created in this cycle:

- `docs/v2/status/step-certification.yaml` — single source of truth, per-Step fields
  (`g8_receipt`, `g7_receipt`, `legacy_receipts`, `real_fixtures`, `registry_file`,
  `capability`, `contract_suite`, `contract_suite_status`, `unit_test`, `notes`).
- `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` — human-readable rollup with matrix
  table, invariants table, mandatory disabled tests section.
- `docs/v2/07-uat/LEGACY_RESIDUAL_LEDGER.md` — burn-down timeline 11+11+11 → 0+0+0 with
  per-slice dates.
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` — updated inventory, counters, and per-Step
  detail sections.

Updated:

- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` — counters and per-row notes synced to
  the canonical YAML.

---

## 9. Out-of-scope failures (NOT touched)

The following pre-existing failures remain out of LFC-2E0 scope and are NOT regressions
from this work:

- `UatCompat001CorpusSmokeRunTest` — expects 17 corpus fixtures, currently has 10.
- `UatLocal005CheckoutGitTest` — git environment dependent.
- `UatLocal008::1148` — CredentialsId DSL classpath (INC pre-existing).

These are tracked separately and are not part of the LFC-2E0 closure gate.

---

## 10. Authoritative conclusion

LFC-2E0 is CLOSED. The V2 Step execution spine has FIRST ZERO LEGACY RESIDUAL:

- 0 legacy executable Steps in production code paths.
- 0 `Canonical*NodeDispatcher` source files outside the test/fitness layer.
- 0 `StepSpec.*` sealed subtypes other than the structural registry escape hatch
  (`RegistryStepSpec`).
- 0 `LEGACY_PLUGIN_IDS` rows.
- 12 + 1 + 2 + 1 = 16 Step keys, of which 13 are production-resolved (CERTIFIED + 1
  EXTERNAL), 2 are STOPPED_G7 (registry-routed but blocked at installed acceptance), and
  1 is REJECTED (DSL fail-closed).

`N = 13` registry-resolved; `M = 0` legacy-executable. `N + M = 13` (registry-only
production surface; rejected/STOPPED_G7 keys are not counted in `N+M` because they have
no live execution path).

The next milestone is **LFC-2E1 (universal-core freeze)**.
