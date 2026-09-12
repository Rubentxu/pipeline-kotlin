# S2-A8 / G0 — `core.waitUntil` Baseline Audit & Characterization Receipt

**Batch**: LFC-2E1 / WAVE 1
**Step**: `core.waitUntil`
**Base SHA**: `f5e4003d`
**Branch**: `cycle/lfc2-e1-wait-until`
**Date**: 2026-09-12
**Gate**: G0 — STOP

## 1. Purpose

Establish the baseline observable behavior of `core.waitUntil` at SHA `f5e4003d` before any registry candidate implementation. Document the legacy execution path, authority chain, and pre-existing test failures for reference in subsequent gates.

## 2. Authority Map (Current Chain)

```
DSL waitUntil{ ... }
    → PipelineDsl.WaitUntil(initialRecurrencePeriod, quiet)
    → StepSpec (compiler lowering)
    → StepNode(pluginStepId="core.waitUntil", payload=dsl-v1)
    → StructuralFamilyResolver.classify("core.waitUntil", registry)
        → LegacyCore (LEGACY_PLUGIN_IDS contains "core.waitUntil")
    → CanonicalCoreStepDecoder.decode(StepNode) → CanonicalCoreStepCommand.WaitUntil
    → CanonicalNodeDispatcher.dispatch(WaitUntil, context)
    → CanonicalWaitUntilNodeDispatcher.dispatchStub(command, context)
    → EventSink.append(WaitUntilPolled, WaitUntilCompleted)
    → StepOutcome.Success
```

### Key Files

| File | SHA-256 |
|------|---------|
| `CanonicalCoreStepDecoder.kt` | `728e00dbff207be6278e07789f76ab43257fcb92818d28a626fd1b4115bdcf5c` |
| `CanonicalCoreStepMetadata.kt` | `690c6239185e19401263ee26e91b046275fc786d61e877ef45233736281b67a0` |
| `CanonicalWaitUntilNodeDispatcher.kt` | `ee0a92e6b3c34b9cb320ee31d1bb98070efbfaf48ca6fd719fedc31aef31cead` |
| `CanonicalNodeDispatcher.kt` | `80d36abb073a5b1adb2a1cb7e7552816d04da2cf518b1723656d6c502fbf23a4` |

## 3. Legacy Source-of-Truth Inventory

### 3.1 Catalogue Entry
`CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS` contains `"core.waitUntil"`:
```kotlin
val LEGACY_PLUGIN_IDS: Set<String> = setOf(
    "core.milestone",
    "core.deleteDir",
    "core.cleanWs",
    "core.load",
    "core.waitUntil",        // ← THIS
    "core.archiveArtifacts",
)
```

### 3.2 Command Subtype
`CanonicalCoreStepCommand.WaitUntil` exists at lines 176-185:
```kotlin
data class WaitUntil(
    val initialRecurrencePeriod: Long = 1000L,
    val quiet: Boolean = false,
) : CanonicalCoreStepCommand {
    override val pluginId = "core.waitUntil"
}
```

### 3.3 Decoder Branch
`CanonicalCoreStepDecoder.decode()` has WAIT_UNTIL_PLUGIN_ID branch at lines 278-288:
```kotlin
WAIT_UNTIL_PLUGIN_ID -> {
    require(payload.requiredString("kind") == "waitUntil") { ... }
    val initialRecurrencePeriod = payload["initialRecurrencePeriod"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1000L
    val quiet = payload["quiet"]?.jsonPrimitive?.booleanOrNull ?: false
    CanonicalCoreStepCommand.WaitUntil(initialRecurrencePeriod, quiet)
}
```

### 3.4 Metadata Row
`CanonicalCoreStepMetadata.table["core.waitUntil"]`:
```kotlin
"core.waitUntil" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
```

### 3.5 Dispatcher
`CanonicalNodeDispatcher` has `waitUntilDispatcher` field and `dispatchStub` call at lines 33, 47, 89-93.

## 4. Behaviour Characterization Matrix (Legacy Dispatcher)

### 4.1 waitUntil Semantics

| Dimension | Behaviour |
|-----------|-----------|
| **Poll interval** | `initialRecurrencePeriod` (default 1000ms) |
| **Backoff** | Exponential ×2, cap at 60s |
| **Condition** | Lambda returns `true` → success |
| **Deadline** | `deadlineMs` parameter (default `Long.MAX_VALUE`) |
| **Events** | `WaitUntilPolled` per attempt, `WaitUntilCompleted` on finish |
| **Timeout outcome** | `"deadline-exceeded"` string in `WaitUntilCompleted.outcome` |
| **Success outcome** | `"completed"` string in `WaitUntilCompleted.outcome` |
| **Effect** | `READ_ONLY` |
| **Replay** | `MEMOIZED` |

### 4.2 Event Contract

**WaitUntilPolled** (per poll attempt):
- `eventId: String` (UUID)
- `runId: String`
- `sequence: Long`
- `occurredAt: Instant`
- `attempt: Int` (1-based)
- `durationMs: Long`
- `conditionResult: Boolean`

**WaitUntilCompleted** (on finish):
- `eventId: String` (UUID)
- `runId: String`
- `sequence: Long`
- `occurredAt: Instant`
- `totalAttempts: Int`
- `totalDurationMs: Long`
- `outcome: String` ("completed" | "deadline-exceeded")

### 4.3 Dispatch Stub Limitation

The legacy `dispatchStub` emits events with stub values because the condition lambda is not serializable across the canonical IR boundary:

```kotlin
// CanonicalWaitUntilNodeDispatcher.kt line 53
conditionResult = true, // Stub: assume condition is met
```

This is documented in the existing code comments and is the reason `waitUntil` requires special handling in the registry candidate.

## 5. Pre-Existing Test Baseline

### 5.1 Known Pre-Existing Failures

**One pre-existing build dependency**: `UppercaseStepContractSuiteTest` requires the `example-uppercase-plugin` JAR to be pre-built:

```
testImplementation(files(rootDir.resolve("../examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar")))
```

Resolution: Build the example plugin first:
```bash
cd examples/example-uppercase-plugin && ../../v2/gradlew build
```

### 5.2 waitUntil-Specific Test Results (SHA f5e4003d, base)

| Test Class | Tests | Pass | Fail | Skip |
|------------|-------|------|------|------|
| `CanonicalWaitUntilNodeDispatcherTest` | 2 | 2 | 0 | 0 |
| `UatLocal011WorkflowControlTest.SC-011-12` | 1 | 1 | 0 | 0 |
| `CompatibilityCorpusTest.fixture13` | 1 | 1 | 0 | 0 |

**Evidence**: Fresh XML results, verified 2026-09-12.

## 6. DSL Interface

`waitUntil` is a Block Step (has a body/condition lambda):

```kotlin
// PipelineDsl.kt lines 645-651
data class WaitUntil(
    val initialRecurrencePeriod: Long = 1L,
    val quiet: Boolean = false,
) : StepSpec {
    override val name: String get() = "waitUntil"
    override val type: String get() = "waitUntil"
}
```

**Note**: Default is `1L` ms in DSL, but `1000L` in the command/metadata. This is a known discrepancy to address in G1.

## 7. Key Challenges for Registry Candidate

### 7.1 Block Step Body Handling
`waitUntil` has a body (condition lambda) that must be evaluated. The canonical dispatcher uses a stub because lambdas aren't serializable. The registry candidate must handle this through the appropriate mechanism.

### 7.2 Event Emissions
The Step must emit typed `WaitUntilPolled` and `WaitUntilCompleted` events. These are already defined in the domain.

### 7.3 Polling Loop
The registry candidate must implement the polling loop with exponential backoff per Jenkins semantics.

## 8. Counter State

```
LEGACY_PLUGIN_IDS       = 6
metadata rows           = 6
dispatcher files        = 6
```

| Residual Key | Status |
|-------------|--------|
| `core.milestone` | LEGACY_EXECUTABLE |
| `core.deleteDir` | LEGACY_EXECUTABLE |
| `core.cleanWs` | LEGACY_EXECUTABLE |
| `core.load` | LEGACY_EXECUTABLE |
| `core.waitUntil` | **TARGET: G1 candidate** |
| `core.archiveArtifacts` | LEGACY_EXECUTABLE |

## 9. G0 Exit State

- **Counter invariant**: 6 / 6 / 6 (UNCHANGED)
- **LEGACY_PLUGIN_IDS**: unchanged, contains `"core.waitUntil"`
- **Legacy path**: INTACT, production authority
- **Pre-existing failures**: 1 (example plugin JAR build dependency, resolved)
- **waitUntil tests**: GREEN (3/3)

## 10. Next Steps (G1)

1. Implement `CoreWaitUntilStep` object with:
   - `WaitUntilInput` data class
   - `StepCodec<WaitUntilInput>`
   - `StepDefinition<WaitUntilInput, WaitUntilOutput>`
   - Handler emitting `WaitUntilPolled`/`WaitUntilCompleted` events
   - Register in `CoreStepRegistryFactory`

2. **DO NOT** modify:
   - `LEGACY_PLUGIN_IDS`
   - Legacy decoder branch
   - Legacy metadata row
   - Legacy dispatcher
   - Legacy canonical command

3. Target: registry candidate behind registry seam, legacy path remains production authority.

---

**STOP**: G0 complete. Awaiting user GO for G1.
