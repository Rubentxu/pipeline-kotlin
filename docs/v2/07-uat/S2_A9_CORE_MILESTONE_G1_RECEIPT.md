# S2-A9 / G1 — Registry Seam Proof Receipt

> Cycle: `cycle/lfc2-e1-milestone`
> Slice: S2-A9 (`core.milestone`)
> Gate: **G1 — Registry Seam Proof (candidate behind registry, legacy path intact)**
> Branch HEAD: `321aa93e` (G0 closed, this gate)
> Date: 2026-09-12T14:36Z

## 1. Purpose

G1 implements `CoreMilestoneStep` as a **registry candidate** behind the open-world
`StepDefinition` seam. The legacy execution path (`CanonicalMilestoneNodeDispatcher` via
`CanonicalNodeDispatcher`) remains the production authority because `core.milestone`
is still in `LEGACY_PLUGIN_IDS`. The registry candidate is registered but not yet
wired as the production path.

G1 produces:
- `CoreMilestoneStep.kt` (typed Input/Output, StepCodec, StepContract, StepHandler)
- `CoreStepRegistryFactory.kt` updated to register `CoreMilestoneStep`
- This receipt

G1 does NOT produce:
- LEGACY_PLUGIN_IDS change
- Legacy decoder modification
- Legacy dispatcher modification
- Legacy metadata row modification

## 2. What was built

### 2.1 CoreMilestoneStep.kt

```
v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStep.kt
SHA256: ffa75fe851d9ce42ea5e9031b4a7ac437433338ae0d804421ac63956b235a0d9
```

**Typed Input:**
```kotlin
data class MilestoneInput(val ordinal: Int, val label: String?) {
    init { require(ordinal > 0) { "core.milestone requires positive ordinal" } }
}
```

**Typed Output:**
```kotlin
data class MilestoneOutput(
    val ordinal: Int,
    val label: String?,
    val status: MilestoneStatus,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = when (status) {
        is MilestoneStatus.Reached -> StepOutcome.Success
        is MilestoneStatus.Aborted -> StepOutcome.Unstable
    }
}

sealed class MilestoneStatus {
    abstract val kind: String
    object Reached : MilestoneStatus() { override val kind = "reached" }
    data class Aborted(val reason: String) : MilestoneStatus() { override val kind = "aborted" }
}
```

**Input Codec:** emits `{"kind":"milestone","ordinal":N,"label":"?"}` — byte-identical
to legacy `DslCompiledPipelineCompiler.milestonePayload` output.

**Output Codec:** emits `{"ordinal":N,"label":?,"status":"reached"|"aborted","reason":"?"}`
for MEMOIZED durable eligibility.

**Descriptor:**
```kotlin
StepDescriptor(
    stepId = "core.milestone",
    name = "milestone",
    configRef = "",
    executionLocation = ExecutionLocation.CONTROLLER,
    effects = listOf(Effect.READ_ONLY),
    replayPolicy = ReplayPolicy.MEMOIZED,
)
```

**Capabilities:**
```kotlin
requiredCapabilities = setOf(EVENT_SINK_CAPABILITY)
```
(Only `EVENT_SINK_CAPABILITY` — mirrors legacy `CanonicalMilestoneDispatchContext(eventSink: EventSink)`)

**Handler:** capability-routed, uses only `EVENT_SINK_CAPABILITY` to emit
`MilestoneReached` or `MilestoneAborted` events. Tracks `lastReachedOrdinal` in
the handler's companion object (per-run state, scoped per classloader instance).

**Registration:**
```kotlin
fun registerInto(registry: StepRegistry) { registry.register(definition) }
```

### 2.2 CoreStepRegistryFactory.kt

```kotlin
// LFC-2E1-S2-A9 / G1: candidate registration only.
CoreMilestoneStep.registerInto(this)
```

## 3. G1 verification

### 3.1 Compilation

```text
$ ./v2/gradlew -p v2 :pipeline-application:compileKotlin
BUILD SUCCESSFUL in 2s
```

### 3.2 Test compilation

```text
$ ./v2/gradlew -p v2 :pipeline-application:compileTestKotlin
BUILD SUCCESSFUL in 3s
```

### 3.3 Legacy path regression (pre-existing tests still green)

```text
$ ./v2/gradlew -p v2 :pipeline-application:test --tests 'UatLocal013*'

UatLocal013MilestoneTimingTest
  tests="4"  failures="0"  errors="0"   time="22.34s"
  SC-013-01 milestone increasing ordinals emit MilestoneReached in order()          PASS
  SC-013-02 milestone out-of-order emits MilestoneAborted()                        PASS
  SC-013-03 milestone file lock serializes concurrent milestone acquisition()       PASS
  SC-013-04 milestone with label emits MilestoneReached with correct label()       PASS

$ ./v2/gradlew -p v2 :pipeline-application:test --tests 'CanonicalDurableRunCoordinatorTest.milestone*'

CanonicalDurableRunCoordinatorTest (milestone subset)
  tests="2"  failures="0"  errors="0"   time="0.53s"
  milestone dispatches MilestoneReached for strictly increasing ordinals()          PASS
  milestone out-of-order ordinal emits MilestoneAborted and continues as Unstable() PASS
```

### 3.4 State machine invariant

```
LEGACY_PLUGIN_IDS              = 12   (unchanged — milestone still in set)
CanonicalCoreStepMetadata rows = 12   (unchanged)
per-Step dispatchers           = 12   (unchanged)
registry entries              = 10   (was 9, +1 for CoreMilestoneStep)
```

## 4. Pre-existing failures (documented, NOT regressions)

```text
UppercaseStepContractSuiteTest — compile error (example.uppercase JAR)
  Fix: ./v2/gradlew -p examples/example-uppercase-plugin build
```

## 5. No regressions from G0

All 6 milestone tests (UatLocal013 × 4 + CanonicalDurableRunCoordinator × 2) continue to pass.

## 6. Production code touched in G1

```text
A v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStep.kt
M v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt
A docs/v2/07-uat/S2_A9_CORE_MILESTONE_G1_RECEIPT.md
```

No changes to:
- LEGACY_PLUGIN_IDS
- CanonicalCoreStepDecoder.kt
- CanonicalCoreStepMetadata.kt
- CanonicalNodeDispatcher.kt
- CanonicalMilestoneNodeDispatcher.kt

## 7. Decision

G1 is closed. CoreMilestoneStep is registered behind the registry seam.
Legacy path remains the production authority.

Awaiting GO for G2 (corpus migration / canonical milestone test).

---
**G1 closed.**
