# S2-A9 / G0 — Audit & Characterization Receipt

> Cycle: `cycle/lfc2-e1-milestone`
> Slice: S2-A9 (`core.milestone`)
> Gate: **G0 — Audit & Characterization (NO production change)**
> Branch HEAD: `f5e4003d` (base = main @ f5e4003d)
> Date: 2026-09-12T14:30Z

## 1. Purpose

G0 characterizes the **current legacy `core.milestone` authority end-to-end** before any
implementation decision. The objective is to answer, with evidence, a single concrete
question:

> **What does `core.milestone` actually do in pipeline-kotlin today — especially
> ordinal monotonicity, events, and replay semantics?**

G0 produces NO production change. G0 produces only characterization evidence.

## 2. Authority map (current chain)

```text
DSL milestone(ordinal: Int, label: String?)
  ↓
StepSpec.Milestone(ordinal, label)
  ↓
DslCompiledPipelineCompiler.milestonePayload(ordinal, label) →
  payload {"kind":"milestone","ordinal":N,"label":"..."}
  ↓
CanonicalCoreStepDecoder.decode(...)
  ↓
CanonicalCoreStepCommand.Milestone(ordinal, label)
  ↓
StepDescriptor: effects={READ_ONLY}; replay=MEMOIZED; location=CONTROLLER
  ↓
LegacyExecutionBoundary.prepare → PreparedLegacyExecution
  ↓
CanonicalNodeDispatcher.dispatch(Milestone, context) →
  CanonicalMilestoneNodeDispatcher.dispatch(command, ctx)
  ↓
milestoneDispatcher (in-memory lastReachedOrdinal Int?)
  ↓
  ordinal > lastReachedOrdinal?
    YES → emit MilestoneReached(runId, ordinal, label) → return Success
    NO  → emit MilestoneAborted(runId, ordinal, reason) → return Unstable
```

Source files (the four legs of the chain):

```text
DSL facade:        v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt
  milestone(ordinal, label) at ~719
StepSpec.Milestone:  v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:112
Compiler:          v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt:611
  milestonePayload(ordinal, label) → {"kind":"milestone","ordinal":N,"label":?}
Decoder:           v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt:226
  decode() → CanonicalCoreStepCommand.Milestone(ordinal, label)
Metadata:          v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt:21
  "core.milestone" → StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
Dispatcher:        v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalMilestoneNodeDispatcher.kt
  dispatch(command, ctx) — in-memory lastReachedOrdinal state
Events:            v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/DomainEvent.kt:785
  MilestoneReached(eventId, runId, sequence, occurredAt, ordinal, label)
  MilestoneAborted(eventId, runId, sequence, occurredAt, ordinal, reason)
```

## 3. Characterization matrix

Test files:

```text
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal013MilestoneTimingTest.kt
  → 4 tests pinning MilestoneReached/MilestoneAborted ordinal semantics (SC-013-01..04)

v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinatorTest.kt
  → 2 tests: milestone increasing ordinals → MilestoneReached+Success;
    milestone out-of-order → MilestoneAborted+Unstable (record-only)

v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal011WorkflowControlTest.kt
  → 13 tests (12 run, 1 skipped) — full workflow control including milestone-adjacent steps
```

### 3.1 Per-dimension verdict

| Dimension | Current observed authority | Verdict |
|---|---|---|
| input ordinal | `Int > 0` required; decoder validates `ordinal > 0` | **OK** |
| label | optional `String?`; null allowed | **OK** |
| monotonically increasing ordinal | `ordinal > lastReachedOrdinal` → MilestoneReached + Success | **OK** |
| non-monotonic ordinal | `ordinal <= lastReachedOrdinal` → MilestoneAborted + Unstable | **OK** (record-only, local single-run per ADR-0046 ML-R9 T-09) |
| pipeline continues after MilestoneAborted | exit=0, pipeline continues to downstream steps | **OK** (per SC-013-02 acceptance criterion) |
| MilestoneReached event | typed `MilestoneReached(eventId, runId, sequence, occurredAt, ordinal, label)` | **OK** |
| MilestoneAborted event | typed `MilestoneAborted(eventId, runId, sequence, occurredAt, ordinal, reason)` | **OK** |
| in-memory state | `CanonicalMilestoneNodeDispatcher.lastReachedOrdinal: Int?` — per-dispatcher in-memory | **limitation**: state is lost on coordinator restart |
| capabilities | none declared (legacy has no capability contract); uses only `EventSink` via `CanonicalMilestoneDispatchContext` | **n/a legacy** |
| effects | `Effect.READ_ONLY` declared; no file/network effects | **OK** |
| replay | `ReplayPolicy.MEMOIZED` declared; in-memory state means no durable ordinal persistence | **limitation**: ordinal state not durable |
| events | `StepStarted`, `StepFinished`, `MilestoneReached`/`MilestoneAborted` | **OK** |

### 3.2 Key findings

```text
F1 — Typed events are correct.
    MilestoneReached and MilestoneAborted are well-typed sealed ADT events
    with ordinal and label fields. The dispatcher emits them correctly.

F2 — Ordinal monotonicity semantics are correct (record-only).
    Strictly increasing ordinal → MilestoneReached + Success.
    Non-increasing ordinal → MilestoneAborted + Unstable (record-only, never aborts run).
    Matches Jenkins verbatim per ADR-0046 §ML (ML-R9 T-09).

F3 — In-memory state is a limitation.
    lastReachedOrdinal is stored in-memory in CanonicalMilestoneNodeDispatcher.
    State is lost if the coordinator restarts mid-pipeline.
    The registry candidate MUST address this at G2 (journal-aware state).

F4 — ReplayPolicy.MEMOIZED declared but no durable ordinal state.
    Replay will re-run the milestone handler with fresh in-memory state,
    potentially emitting MilestoneReached again for an ordinal that already
    succeeded. This is a known gap for the registry candidate to address.

F5 — Capabilities: EVENT_SINK only.
    The dispatcher uses CanonicalMilestoneDispatchContext(eventSink: EventSink).
    No other capabilities required.
```

## 4. Evidence (test results)

### 4.1 In-process tests

```text
$ ./v2/gradlew -p v2 :pipeline-application:test --tests 'UatLocal013*' --tests 'UatLocal011*' --tests 'CanonicalDurableRunCoordinatorTest.milestone*'

UatLocal013MilestoneTimingTest
  tests="4"  failures="0"  errors="0"   time="23.361s"
  SC-013-01 milestone increasing ordinals emit MilestoneReached in order()          PASS
  SC-013-02 milestone out-of-order emits MilestoneAborted()                        PASS
  SC-013-03 milestone file lock serializes concurrent milestone acquisition()       PASS
  SC-013-04 milestone with label emits MilestoneReached with correct label()       PASS

CanonicalDurableRunCoordinatorTest (milestone subset)
  tests="2"  failures="0"  errors="0"   time="0.399s"
  milestone dispatches MilestoneReached for strictly increasing ordinals()          PASS
  milestone out-of-order ordinal emits MilestoneAborted and continues as Unstable() PASS

UatLocal011WorkflowControlTest
  tests="13"  skipped="1"  failures="0"  errors="0"   time="67.433s"
  (12 non-milestone tests + 1 skipped canary)
```

### 4.2 Pre-existing failures (documented, NOT regressions from this work)

```text
UppercaseStepContractSuiteTest — compile error (example.uppercase JAR not built)
  This is a pre-existing issue unrelated to core.milestone.
  Build fix: ./v2/gradlew -p examples/example-uppercase-plugin build
  The JAR is required for pipeline-application test compilation.
```

## 5. Production code touched in G0

**None.** G0 added only this receipt. No production code was changed.

```text
git status --porcelain (working tree at G0 close)
?? docs/v2/07-uat/S2_A9_CORE_MILESTONE_G0_BASELINE_RECEIPT.md
```

No edits to:
- `CanonicalCoreStepDecoder.kt`
- `CanonicalCoreStepMetadata.kt`
- `CanonicalNodeDispatcher.kt`
- `CanonicalMilestoneNodeDispatcher.kt`
- `CoreStepRegistryFactory.kt`
- `DomainEvent.kt`
- LEGACY_PLUGIN_IDS (still 12)

## 6. State machine (S2-A9 at G0 close)

```text
LEGACY_PLUGIN_IDS              = 12   (unchanged)
CanonicalCoreStepMetadata rows = 12   (unchanged)
per-Step dispatchers           = 12   (unchanged)

core.milestone:
  execution     = LegacyCore (CanonicalMilestoneNodeDispatcher via CanonicalNodeDispatcher)
  registry      = absent
  certification = legacy / uncertified
  characterization evidence = THIS RECEIPT (6 in-process tests GREEN)
```

## 7. Byte-equivalence analysis for G1 candidate

The registry candidate MUST produce byte-identical payloads to the legacy dsl-v1:

**Input envelope** (from `DslCompiledPipelineCompiler.milestonePayload`):
```json
{"kind":"milestone","ordinal":N,"label":"optional-label-or-null"}
```

The candidate's `StepCodec<MilestoneInput>.encode()` MUST emit this exact form.

**Output**: No legacy typed output. Registry candidate exposes `MilestoneOutput` as
typed runtime output (optional, not used by pipeline consumers).

**Events**: `MilestoneReached` and `MilestoneAborted` are already typed events.
The registry handler reuses the same event constructors, preserving byte-equivalence.

**Descriptor metadata**:
- `effects = [Effect.READ_ONLY]` (byte-equivalent to existing metadata row)
- `replayPolicy = ReplayPolicy.MEMOIZED` (byte-equivalent)
- `recoveryPolicy = RecoveryPolicy.None` (atomic from caller's POV)

**Capabilities**: Only `EVENT_SINK_CAPABILITY` required (same as legacy
`CanonicalMilestoneDispatchContext(eventSink: EventSink)`).

## 8. G1 Plan

```kotlin
// CoreMilestoneStep.kt

data class MilestoneInput(val ordinal: Int, val label: String?)
data class MilestoneOutput(val ordinal: Int, val label: String?, val status: MilestoneStatus)
sealed class MilestoneStatus {
    object Reached : MilestoneStatus()
    data class Aborted(val reason: String) : MilestoneStatus()
}

val KEY = PluginStepId("core.milestone")

val inputCodec = object : StepCodec<MilestoneInput> {
    override fun encode(value: MilestoneInput): EncodedStepValue = ...
    override fun decode(encoded: EncodedStepValue): MilestoneInput = ...
}

val outputCodec = object : StepCodec<MilestoneOutput> { ... }

val descriptor = StepDescriptor(
    stepId = "core.milestone",
    name = "milestone",
    configRef = "",
    executionLocation = ExecutionLocation.CONTROLLER,
    effects = listOf(Effect.READ_ONLY),
    replayPolicy = ReplayPolicy.MEMOIZED,
)

val definition = object : StepDefinition<MilestoneInput, MilestoneOutput> {
    override val contract = StepContract(
        key = KEY,
        descriptor = descriptor,
        inputCodec = inputCodec,
        outputCodec = outputCodec,
        requiredCapabilities = setOf(EVENT_SINK_CAPABILITY),
    )
    override val handler = StepHandler { input, ctx ->
        val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
        // ordinal monotonicity check
        // emit MilestoneReached or MilestoneAborted
        // return MilestoneOutput
    }
}

fun registerInto(registry: StepRegistry) { registry.register(definition) }
```

**Registration** in `CoreStepRegistryFactory.registry()`:
```kotlin
CoreMilestoneStep.registerInto(this)  // G1: candidate only, no LEGACY_PLUGIN_IDS change
```

## 9. SHA-256 evidence (G0 base)

```
728e00dbff207be6278e07789f76ab43257fcb92818d28a626fd1b4115bdcf5c  CanonicalCoreStepDecoder.kt
690c6239185e19401263ee26e91b046275fc786d61e877ef45233736281b67a0  CanonicalCoreStepMetadata.kt
ffbe8fe82827f62fe3c8404425ce72eb26d6ffc5351636670530e8af371a0b1e  CanonicalMilestoneNodeDispatcher.kt
80d36abb073a5b1adb2a1cb7e7552816d04da2cf518b1723656d6c502fbf23a4  CanonicalNodeDispatcher.kt
```

## 10. Awaiting GO

G0 is closed. Awaiting GO for G1 of S2-A9.

---
**G0 closed.**
