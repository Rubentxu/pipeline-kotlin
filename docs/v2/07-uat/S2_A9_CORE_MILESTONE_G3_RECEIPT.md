# S2-A9 / G3 — Contract Certification Receipt

> Cycle: `cycle/lfc2-e1-milestone`
> Slice: S2-A9 (`core.milestone`)
> Gate: **G3 — Contract Suite (CoreMilestoneStepContractSuiteTest)**
> Branch HEAD: `cf220563` (G2 closed, this gate)
> Date: 2026-09-12T14:48Z

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
LEGACY_PLUGIN_IDS              = 12   (unchanged — milestone still in set)
CanonicalCoreStepMetadata rows = 12   (unchanged)
per-Step dispatchers           = 12   (unchanged)
registry entries              = 10   (unchanged)

core.milestone:
  execution     = LegacyCore (CanonicalMilestoneNodeDispatcher)
  registry      = present (CoreMilestoneStep.registered in CoreStepRegistryFactory)
  certification = G3 CERTIFIED
  characterization evidence = THIS RECEIPT
```

## 5. State machine transition

```
core.milestone: G2 (IMPLEMENTED_UNCERTIFIED)
  → G3 (CERTIFIED, READY_FOR_AUTHORITY_FLIP)
```

Per ADR-0074, `core.milestone` is now **CERTIFIED** and ready for the G4
authority flip (removing `core.milestone` from LEGACY_PLUGIN_IDS).

## 6. Production code touched in G3

```text
M v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStep.kt
  + resetState() method for test isolation
A v2/pipeline-application/src/test/kotlin/.../CoreMilestoneStepContractSuiteTest.kt
A docs/v2/07-uat/S2_A9_CORE_MILESTONE_G3_RECEIPT.md
```

No changes to LEGACY_PLUGIN_IDS, legacy decoder, legacy dispatcher, or legacy metadata.

## 7. Stop condition

G3 is STOP. Per the batch manifest:

> stop_after: G3 → estado READY_FOR_AUTHORITY_FLIP y STOP

`core.milestone` is now:
- G1: CoreMilestoneStep implemented and registered (CERTIFIED)
- G2: Corpus migrated (18 tests green)
- G3: Contract suite passed (19 tests green, CERTIFIED)

**READY_FOR_AUTHORITY_FLIP** — milestone is CERTIFIED for the registry authority flip.
The next gate (G4) is handled by the lane owner.

---
**G3 CLOSED — core.milestone CERTIFIED, READY_FOR_AUTHORITY_FLIP.**
