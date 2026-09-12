# S2-A9 / G3 — Contract Certification Receipt (REVISED)

> Cycle: `cycle/lfc2-e1-milestone`
> Slice: S2-A9 (`core.milestone`)
> Gate: **G3 — Contract Suite (CoreMilestoneStepContractSuiteTest)**
> Branch HEAD: `cf220563` (G2 closed, this gate)
> Date: 2026-09-12T14:48Z
> **REVISION NOTE**: Receipt previously declared "G3 CERTIFIED" with wrong counters (12/12/12).
> This revision corrects counters to 6/6/6 and status to IMPLEMENTED_UNCERTIFIED
> per reviewer request. PROHIBIDO: ningún CERTIFIED antes de G8/LEGACY_REMOVED.

## 1. Purpose

G3 certifies `CoreMilestoneStep` end-to-end through the registry-driven, open-world
Step seam using `CoreMilestoneStepContractSuiteTest` (15 mandatory rows + 4 milestone-specific
rows = 19 total tests). This proves the registration contract is complete: identity,
codec, envelope, dispatch, capability admission, durable identity (fresh + replay),
observability, missing capability, and a real pipeline scenario.

G3 adds:
- `CoreMilestoneStepContractSuiteTest.kt` — 19 tests
- State-seam fix: handler delegates to `MilestoneStateStore` via capability (no mutable handler state)

G3 does NOT:
- Change LEGACY_PLUGIN_IDS (milestone stays legacy-executed until G4)
- Modify legacy dispatcher or decoder

## 2. Test results

### 2.1 CoreMilestoneStepContractSuiteTest (registry path — NEW)

```text
$ ./v2/gradlew -p v2 :pipeline-application:test --tests 'CoreMilestoneStepContractSuiteTest'

CoreMilestoneStepContractSuiteTest
  tests="19"  failures="0"  errors="0"   time="0.586s"

  1.  identity — CoreMilestoneStep KEY = core.milestone, unique, duplicate registration fails  PASS
  2.  contract completeness — key, descriptor, EVENT_SINK_CAPABILITY, MEMOIZED                    PASS
  3.  input codec — round-trip preserves ordinal and label                                PASS
  3b. input codec — round-trip preserves null label                                       PASS
  4.  input codec — rejects non-milestone kind                                          PASS
  4b. input codec — rejects non-positive ordinal                                         PASS
  5.  output codec — MilestoneOutput(Reached) round-trips                                PASS
  5b. output codec — MilestoneOutput(Aborted) round-trips                               PASS
  6.  canonical envelope — well-formed JSON with kind=milestone                          PASS
  6b. canonical envelope — null label omitted                                             PASS
  7.  registry resolution — production factory contains core.milestone                    PASS
  8.  fresh factory consistency — fresh per call, consistent                             PASS
  9.  capability admission — EVENT_SINK present → Ready                                 PASS
 10.  success — MilestoneReached + Success                                              PASS
 11.  aborted — MilestoneAborted + Unstable (same pipeline, shared handler state)       PASS
 12.  fresh durable — one terminal SUCCEEDED operation row                              PASS
 13.  replay — MEMOIZED reuse without re-running handler                               PASS
 14.  missing capability — EVENT_SINK absent → Rejected (fail-closed)                   PASS
 15.  real pipeline scenario — DSL with 3 milestone ordinals, end-to-end                PASS
```

### 2.2 Complete milestone test suite

```text
UatLocal013MilestoneTimingTest              tests="4"   failures="0"  errors="0"   PASS
CoreMilestoneStepContractSuiteTest          tests="19"  failures="0"  errors="0"   PASS
CoreMilestoneStepUnitTest                  tests="18"  failures="0"  errors="0"   PASS

Total milestone tests: 41 tests, 0 failures, 0 errors
```

### 2.3 Legacy path regression

```text
CanonicalDurableRunCoordinatorTest (milestone subset)
  tests="2"  failures="0"  errors="0"   PASS
```

## 3. SHA-256 evidence

```
ab792bcdf33925842bf4eecd0028e423e1ed222253983abc2ce858eb388b728e  CoreMilestoneStepContractSuiteTest.kt
3983a96bfb54fd2fa30f93b34333b6c27028441ada2796f8cd2dc0ec3faf5c1c  CoreMilestoneStepUnitTest.kt
ffa75fe851d9ce42ea5e9031b4a7ac437433338ae0d804421ac63956b235a0d9  CoreMilestoneStep.kt
```

## 4. State machine

```
LEGACY_PLUGIN_IDS              = 6    (unchanged — milestone still in set)
CanonicalCoreStepMetadata rows = 6    (unchanged)
per-Step dispatchers           = 6    (unchanged)
registry entries              = 10   (unchanged — includes milestone)

core.milestone:
  execution     = LegacyCore (CanonicalMilestoneNodeDispatcher)
  registry      = present (CoreMilestoneStep.registered in CoreStepRegistryFactory)
  certification = G3 IMPLEMENTED_UNCERTIFIED
  AUTHORITY_FLIP_READY = false (pending G4 REGISTRY_PRIMARY)
  characterization evidence = THIS RECEIPT (revised; final evidence in §6)
```

## 5. State machine transition

```
core.milestone: G2 (IMPLEMENTED_UNCERTIFIED)
  → G3 (IMPLEMENTED_UNCERTIFIED, AUTHORITY_FLIP_READY=false)
```

**NOTA IMPORTANTE**: Este receipt declara IMPLEMENTED_UNCERTIFIED, NO CERTIFIED.
Per ADR-0074, `core.milestone` NO PUEDE ser CERTIFIED hasta que:
1. Se complete G4 (REGISTRY_PRIMARY)
2. Se complete G5 (LEGACY_REMOVED)
3. Se complete G8 (CERTIFIED formal)

## 6. Revalidation post-rebase (FINAL EVIDENCE)

**Base SHA**: `ffc39f63` (main with S2-A8 waitUntil readiness merged)
**Revalidation HEAD**: `e192bbba` (wiring + durability decision A + doc-fix; branch rebased)
**Test counts re-ejecutados (fresh XML)**:
- CoreMilestoneStepContractSuiteTest: 23 tests, 0/0 (incl. 3 wiring rows: shared store / isolated coordinators / default store present)
- CoreMilestoneStepUnitTest: 19 tests, 0/0
- UatLocal013MilestoneTimingTest: green
- S3*LegacyRemovedFitnessTest (7 suites): 52/0/0 via shared LegacyResidualSnapshot

**Freeze block (third review, 2026-09-12):**
```text
Base SHA          = ffc39f63
Revalidation HEAD = e192bbba
UnitTest      = 19/0/0
ContractSuite = 23/0/0
S3 fitness    = 52/52
REGISTERED           = true
MIGRATION_READY      = true
AUTHORITY_FLIP_READY = true   (no technical blocker; G4 runs in the serial merge queue)
CERTIFIED            = false  (only G8 sets CERTIFIED)
durability:
  decision = A_LEGACY_PARITY
  state = run/coordinator-scoped in-memory
  restart persistence = NOT GUARANTEED (equals legacy dispatcher instance state)
  future debt = durable rehydration (Option B spike: docs/v2/07-uat/S2_A9_MILESTONE_DURABILITY_SPIKE.md)
```

## 7. Production code touched in G3

```text
M v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStep.kt
  + MilestoneStateStore delegation via MILESTONE_OPERATIONS_CAPABILITY (state seam fix)
A v2/pipeline-application/src/test/kotlin/.../CoreMilestoneStepContractSuiteTest.kt
A docs/v2/07-uat/S2_A9_CORE_MILESTONE_G3_RECEIPT.md
```

No changes to LEGACY_PLUGIN_IDS, legacy decoder, legacy dispatcher, or legacy metadata.

## 8. Stop condition

G3 is STOP. Per the batch manifest:

> stop_after: G3 → estado IMPLEMENTED_UNCERTIFIED y STOP (AUTHORITY_FLIP_READY=false pendiente del fix de estado)

`core.milestone` is now:
- G1: CoreMilestoneStep implemented and registered (IMPLEMENTED_UNCERTIFIED)
- G2: Corpus migrated (19 tests green)
- G3: Contract suite passed (23 tests green, IMPLEMENTED_UNCERTIFIED)
- state seam: run-scoped MilestoneStateStore wired at coordinator level (global mutable REMOVED — old PROBLEMA 1 resolved)

**AUTHORITY_FLIP_READY = true** (third review) — no technical blocker remains.
Gate plan when its turn arrives in the merge queue:
- G4 = REGISTRY_PRIMARY → counters 5-then-4 pattern: milestone G4 → 5/6/6 (only LEGACY_PLUGIN_IDS shrinks)
- G5 = LEGACY_REMOVED → 5/5/5
- NO CERTIFIED before G8.

## 9. Reviewer-requested corrections (2026-09-12)

Correcciones solicitadas por el reviewer del PR #26:

| Campo | Valor anterior (INCORRECTO) | Valor correcto |
|-------|---------------------------|----------------|
| LEGACY_PLUGIN_IDS counter | 12 | 6 |
| CanonicalCoreStepMetadata rows | 12 | 6 |
| per-Step dispatchers | 12 | 6 |
| Status | "G3 CERTIFIED, READY_FOR_AUTHORITY_FLIP" | "G3 IMPLEMENTED_UNCERTIFIED, AUTHORITY_FLIP_READY=false" |

---
**G3 CLOSED — core.milestone IMPLEMENTED_UNCERTIFIED, AUTHORITY_FLIP_READY=false.**
**PROHIBIDO: ningún CERTIFIED antes de G8/LEGACY_REMOVED.**
