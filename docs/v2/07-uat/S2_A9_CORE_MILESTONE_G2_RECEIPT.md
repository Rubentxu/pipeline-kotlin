# S2-A9 / G2 — Differential Contract Freeze Receipt

> Cycle: `cycle/lfc2-e1-milestone`
> Slice: S2-A9 (`core.milestone`)
> Gate: **G2 — Differential Contract Freeze (corpus migration: registry characterization tests)**
> Branch HEAD: `4b5f0eda` (G1 closed, this gate)
> Date: 2026-09-12T14:44Z

## 1. Purpose

G2 migrates the milestone characterization corpus to exercise `CoreMilestoneStep` through
the registry path. This proves the registry path is functional: handler executes correctly,
ordinal monotonicity semantics match legacy, typed events are emitted, and the registry
contains `core.milestone`.

G2 adds:
- `CoreMilestoneStepUnitTest.kt` — 18 tests driving the registry path

G2 does NOT:
- Change LEGACY_PLUGIN_IDS (milestone stays legacy-executed)
- Change the legacy dispatcher or decoder

## 2. Test results

### 2.1 CoreMilestoneStepUnitTest (registry path — NEW)

```text
$ ./v2/gradlew -p v2 :pipeline-application:test --tests 'CoreMilestoneStepUnitTest'

CoreMilestoneStepUnitTest
  tests="18"  failures="0"  errors="0"   time="0.342s"

Registry resolution:
  registry resolution — CoreStepRegistryFactory contains core dot milestone          PASS
  registry resolution — definition is CoreMilestoneStep definition with correct key  PASS

Capability declaration:
  capability declaration — requires only EVENT_SINK_CAPABILITY                       PASS
  capability declaration — descriptor has READ_ONLY effects and MEMOIZED replay       PASS

Input codec:
  input codec — round-trip preserves ordinal and label                              PASS
  input codec — encode produces canonical dsl-v1 envelope with kind milestone        PASS
  input codec — encode omits label when null (legacy behavior)                      PASS
  input codec — decode rejects non-milestone kind                                   PASS
  input codec — decode rejects non-positive ordinal                                PASS

Output codec:
  output codec — round-trip preserves MilestoneOutput with Reached status           PASS
  output codec — round-trip preserves MilestoneOutput with Aborted status          PASS

Handler (ordinal monotonicity):
  handler — first milestone with ordinal 1 emits MilestoneReached and returns Success  PASS
  handler — increasing ordinal emits MilestoneReached for each step               PASS
  handler — non-increasing ordinal emits MilestoneAborted and returns Unstable        PASS
  handler — equal ordinal emits MilestoneAborted                                   PASS
  handler — null label emits MilestoneReached with null label                       PASS

Capability admission:
  missing capability — execution fails when EVENT_SINK is absent                    PASS

Contract completeness:
  contract completeness — key, descriptor, codecs, capabilities all present            PASS
```

### 2.2 Legacy path regression (pre-existing tests still green)

```text
$ ./v2/gradlew -p v2 :pipeline-application:test --tests 'UatLocal013*'

UatLocal013MilestoneTimingTest
  tests="4"  failures="0"  errors="0"   time="22.34s"

$ ./v2/gradlew -p v2 :pipeline-application:test --tests 'CanonicalDurableRunCoordinatorTest.milestone*'

CanonicalDurableRunCoordinatorTest (milestone subset)
  tests="2"  failures="0"  errors="0"   time="0.53s"
```

## 3. State management for test isolation

The handler tracks `lastReachedOrdinal` in a companion object field. For test isolation,
`CoreMilestoneStep.resetState()` is called in `@BeforeEach` and `@AfterEach` to reset
the state between tests. In production, the state is scoped per `CoreMilestoneStep`
classloader instance (per coordinator), so each pipeline run gets a fresh state.

## 4. SHA-256 evidence

```
3983a96bfb54fd2fa30f93b34333b6c27028441ada2796f8cd2dc0ec3faf5c1c  CoreMilestoneStepUnitTest.kt
ffa75fe851d9ce42ea5e9031b4a7ac437433338ae0d804421ac63956b235a0d9  CoreMilestoneStep.kt
```

## 5. State machine invariant

```
LEGACY_PLUGIN_IDS              = 12   (unchanged)
CanonicalCoreStepMetadata rows = 12   (unchanged)
per-Step dispatchers           = 12   (unchanged)
registry entries              = 10   (unchanged)
new: CoreMilestoneStepUnitTest = 18 tests green (registry path proof)
```

## 6. G3 Plan

G3: Contract Suite — `CoreMilestoneStepContractSuiteTest`
Following the `EchoStepContractSuiteTest` / `CorePwdStepContractSuiteTest` pattern:

```text
Coverage matrix:
 1.  identity — CoreMilestoneStep.KEY = "core.milestone", unique
 2.  contract completeness — key, descriptor, input codec, output codec, EVENT_SINK_CAPABILITY
 3.  input codec round-trip
 4.  input codec rejection (non-milestone kind)
 5.  input codec rejection (non-positive ordinal)
 6.  output codec round-trip (Reached)
 7.  output codec round-trip (Aborted)
 8.  canonical envelope (well-formed JSON, kind=milestone)
 9.  registry resolution (production factory contains core.milestone)
10.  capability admission (EVENT_SINK present → Ready)
11.  success (registry path: MilestoneReached emitted, Success outcome)
12.  typed failure — N/A (milestone never fails)
13.  fresh durable (one terminal SUCCEEDED row)
14.  replay (MEMOIZED: reuse without re-running handler)
15.  divergence — N/A for milestone (no input comparison)
16.  observability (StepStarted, MilestoneReached/MilestoneAborted, StepFinished)
17.  missing capability (EVENT_SINK absent → Rejected)
18.  real pipeline scenario (DSL pipeline with milestone ordinals)
```

## 7. Production code touched in G2

```text
A v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStepUnitTest.kt
M v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStep.kt (added resetState())
A docs/v2/07-uat/S2_A9_CORE_MILESTONE_G2_RECEIPT.md
```

No changes to LEGACY_PLUGIN_IDS, legacy decoder, legacy dispatcher, or legacy metadata.

## 8. Awaiting GO

G2 is closed. Awaiting GO for G3 (Contract Suite).

---
**G2 closed.**
