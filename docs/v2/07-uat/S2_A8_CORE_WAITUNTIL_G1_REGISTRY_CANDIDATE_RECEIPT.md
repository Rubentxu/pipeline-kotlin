# S2-A8 / G1 — `core.waitUntil` Registry Candidate Receipt

**Batch**: LFC-2E1 / WAVE 1
**Step**: `core.waitUntil`
**Base SHA**: `f5e4003d`
**This SHA**: `HEAD` (86a3a3a5 + G1 changes)
**Branch**: `cycle/lfc2-e1-wait-until`
**Date**: 2026-09-12
**Gate**: G1 — STOP

## 1. Purpose

Implement `CoreWaitUntilStep` as a registry candidate behind the registry seam. Register it in `CoreStepRegistryFactory` WITHOUT changing `LEGACY_PLUGIN_IDS`, legacy decoder, metadata row, or legacy dispatcher.

## 2. Changes

### 2.1 New Files

| File | Purpose |
|------|---------|
| `v2/pipeline-application/src/main/kotlin/.../CoreWaitUntilStep.kt` | Registry candidate with typed Input/Output, codecs, handler |
| `v2/pipeline-application/src/test/kotlin/.../CoreWaitUntilStepUnitTest.kt` | Unit tests for registry candidate |

### 2.2 Modified Files

| File | Change |
|------|--------|
| `v2/pipeline-application/src/main/kotlin/.../CoreStepRegistryFactory.kt` | Register `CoreWaitUntilStep` |

## 3. Registry Candidate Design

### 3.1 Input Type

```kotlin
data class WaitUntilInput(
    val initialRecurrencePeriod: Long = 1000L,
    val quiet: Boolean = false,
)
```

### 3.2 Output Type

```kotlin
data class WaitUntilOutput(
    val resultOutcome: String, // "completed" or "deadline-exceeded"
    val totalAttempts: Int,
    val totalDurationMs: Long,
) : TypedStepOutput
```

### 3.3 Codecs

- **Input codec**: Encodes to `{"kind":"waitUntil","initialRecurrencePeriod":X,"quiet":Y}`
- **Output codec**: Encodes to `{"kind":"waitUntil","outcome":...,"totalAttempts":...,"totalDurationMs":...}`

### 3.4 Handler

The handler follows the stub pattern from the legacy dispatcher:
1. Emits one `WaitUntilPolled` event
2. Emits one `WaitUntilCompleted` event with `"completed"` outcome
3. Returns `WaitUntilOutput`

**Note**: The actual condition evaluation (polling loop) requires BodyInvoker (ADR-0073). This G1 candidate follows the stub pattern.

### 3.5 Capabilities

- `EVENT_SINK_CAPABILITY` — required to emit typed events

### 3.6 Metadata

- `Effect.READ_ONLY`
- `ReplayPolicy.MEMOIZED`

## 4. G1 Unit Test Results

| Test | Result |
|------|--------|
| `identity — CoreWaitUntilStep KEY is core dot waitUntil` | PASS |
| `identity — duplicate registration fails` | PASS |
| `contract — has descriptor, codecs, and EVENT_SINK capability` | PASS |
| `codec input — WaitUntilInput encodes to canonical dsl-v1 envelope` | PASS |
| `codec input — round-trip reconstructs WaitUntilInput` | PASS |
| `codec input — decode rejects non-waitUntil kind` | PASS |
| `codec output — WaitUntilOutput round-trips` | PASS |
| `registry — production factory contains core dot waitUntil` | PASS |
| `structural family — core waitUntil stays LegacyCore while in LEGACY_PLUGIN_IDS` | PASS |

**Total: 9 tests, 9 PASS, 0 FAIL**

## 5. Legacy Authority Verification

```kotlin
// LEGACY_PLUGIN_IDS unchanged — "core.waitUntil" still in the set
assertTrue("core.waitUntil" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
```

The legacy decoder branch, metadata row, and dispatcher remain the production authority.

## 6. Counter State

```
LEGACY_PLUGIN_IDS       = 6   (UNCHANGED)
metadata rows           = 6   (UNCHANGED)
dispatcher files        = 6   (UNCHANGED)
```

| Residual Key | Status |
|-------------|--------|
| `core.milestone` | LEGACY_EXECUTABLE |
| `core.deleteDir` | LEGACY_EXECUTABLE |
| `core.cleanWs` | LEGACY_EXECUTABLE |
| `core.load` | LEGACY_EXECUTABLE |
| `core.waitUntil` | **Registry candidate registered (G1)** |
| `core.archiveArtifacts` | LEGACY_EXECUTABLE |

## 7. Pre-Existing Tests (Unchanged)

| Test | Result |
|------|--------|
| `CanonicalWaitUntilNodeDispatcherTest` | 2/2 PASS |
| `UatLocal011WorkflowControlTest.SC-011-12` | PASS |
| `CompatibilityCorpusTest.fixture13` | PASS |

## 8. What G1 Did NOT Change

- `LEGACY_PLUGIN_IDS` — unchanged
- `CanonicalCoreStepDecoder` — unchanged
- `CanonicalCoreStepMetadata` — unchanged
- `CanonicalNodeDispatcher` — unchanged
- `CanonicalWaitUntilNodeDispatcher` — unchanged
- Legacy execution path — intact

## 9. Block Step Note

`waitUntil` is a Block Step with a condition body. The registry candidate:
- Emits typed events (`WaitUntilPolled`, `WaitUntilCompleted`)
- Uses the stub pattern (condition assumed true)

The actual polling loop with condition evaluation requires BodyInvoker (ADR-0073). This is deferred to future gates.

## 10. Next Steps (G2)

1. Migrate characterization corpus to drive the Step through the registry
2. Verify behavior parity between legacy stub and registry candidate
3. Proceed to G3 contract suite

---

**STOP**: G1 complete. Awaiting user GO for G2.
