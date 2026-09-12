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
- `CoreMilestoneStep.resetState()` for test isolation

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
  AUTHORITY_FLIP_READY = false (pending PROBLEMA 1 state seam fix)
  characterization evidence = THIS RECEIPT (revised)
```

## 5. State machine transition

```
core.milestone: G2 (IMPLEMENTED_UNCERTIFIED)
  → G3 (IMPLEMENTED_UNCERTIFIED, AUTHORITY_FLIP_READY=false)
```

**NOTA IMPORTANTE**: Este receipt declara IMPLEMENTED_UNCERTIFIED, NO CERTIFIED.
Per ADR-0074, `core.milestone` NO PUEDE ser CERTIFIED hasta que:
1. Se resuelva el PROBLEMA 1 (estado global mutable en handler)
2. Se complete G4 (LEGACY_REMOVED)
3. Se complete G8 (CERTIFIED formal)

## 6. Revalidation post-rebase

**Base SHA**: `2a247b18`
**Revalidation SHA**: `ec37ed3f` (post-rebase)
**Test counts re-ejecutados**:
- CoreMilestoneStepContractSuiteTest: 19 tests
- CoreMilestoneStepUnitTest: 18 tests
- UatLocal013MilestoneTimingTest: 4 tests

## 7. Production code touched in G3

```text
M v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStep.kt
  + resetState() method for test isolation
A v2/pipeline-application/src/test/kotlin/.../CoreMilestoneStepContractSuiteTest.kt
A docs/v2/07-uat/S2_A9_CORE_MILESTONE_G3_RECEIPT.md
```

No changes to LEGACY_PLUGIN_IDS, legacy decoder, legacy dispatcher, or legacy metadata.

## 8. Stop condition

G3 is STOP. Per the batch manifest:

> stop_after: G3 → estado IMPLEMENTED_UNCERTIFIED y STOP (AUTHORITY_FLIP_READY=false pendiente del fix de estado)

`core.milestone` is now:
- G1: CoreMilestoneStep implemented and registered (CERTIFIED)
- G2: Corpus migrated (18 tests green)
- G3: Contract suite passed (19 tests green, IMPLEMENTED_UNCERTIFIED)

**NOT AUTHORITY_FLIP_READY** — `core.milestone` permanece IMPLEMENTED_UNCERTIFIED
hasta que se resuelva el PROBLEMA 1 (estado global mutable) y se complete G4/LEGACY_REMOVED.

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
