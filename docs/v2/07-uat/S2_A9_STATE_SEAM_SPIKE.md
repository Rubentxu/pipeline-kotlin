# S2-A9 — State Seam Spike Receipt

> Cycle: `cycle/lfc2-e1-milestone`
> Slice: S2-A9 (`core.milestone`)
> Spike: State seam for milestone ordinal tracking
> Date: 2026-09-12

## 1. Problem Statement

**Reviewer finding (PR #26):** `CoreMilestoneStep` is an `object` with `private var lastReachedOrdinal: Int? = null`. This is **mutable global state** at the classloader level.

### Why this is forbidden

AGENTS.md §STEP IMPLEMENTATION — OPERATIVE GUIDE explicitly prohibits:
- `var` in the handler
- Global maps
- Lifecycle state in the handler
- State in `CanonicalRuntimeCapabilityAccess` (that bridge is constructed per invocation from `CanonicalRuntimeContext` — a stateful capability there would reset between Steps)

### What the legacy does

The legacy `CanonicalMilestoneNodeDispatcher` tracks `lastReachedOrdinal` **per dispatcher instance**, which is constructed alongside the coordinator for each pipeline run:

```kotlin
class CanonicalMilestoneNodeDispatcher {
    private var lastReachedOrdinal: Int? = null  // per coordinator instance = per run
    
    fun advance(ordinal: Int): MilestoneAdvanceResult { ... }
}
```

The key insight: **the legacy state is run-scoped**, not global. A singleton `object` with `var` is NOT equivalent.

## 2. Solution: Run-Scoped State via Capability

### Design

```
MilestoneStateStore           # lifetime = coordinator/run
        ↓
MilestoneOperationsAdapter   # implements MilestoneOperations capability interface
        ↓
MILESTONE_OPERATIONS_CAPABILITY (declared in StepContract)
        ↓
CoreMilestoneStep            # handler: no var, no maps, no state
                              # consumes ops.peek() / ops.advance(ordinal)
```

### Why run-scoped?

The milestone ordinal is **defined per pipeline run**. Milestones from run A do not affect run B. The store must live at the coordinator level, matching the legacy `CanonicalMilestoneNodeDispatcher` scope.

### Wiring guarantee

The store is created **at coordinator construction time** and passed through the capability system:

```
CanonicalDurableRunCoordinator(..., milestoneStateStore = MilestoneStateStore())
    ↓
ExecutionBoundaryFactory.build(..., milestoneStateStore = store)
    ↓
RegistryExecutionBoundary.adapt(milestoneStateStore = store)
    ↓
CanonicalRuntimeCapabilityAccess(context, milestoneStateStore = store)
    ↓
MILESTONE_OPERATIONS_CAPABILITY → MilestoneOperationsAdapter(store)
```

**The store is created once per coordinator**, not per handler invocation. This matches the legacy's per-run semantics.

## 3. Files Created/Modified

### New files

```
v2/pipeline-application/src/main/kotlin/.../application/MilestoneOperations.kt
  + MilestoneOperations interface (peek, advance)
  + MilestoneAdvanceResult sealed class (Reached, Aborted)
  + MilestoneStateStore class (thread-safe, run-scoped)
  + MilestoneOperationsAdapter class
```

### Modified files

| File | Change |
|------|--------|
| `Capabilities.kt` | + `MILESTONE_OPERATIONS_CAPABILITY` |
| `CoreMilestoneStep.kt` | - `var lastReachedOrdinal`, - `resetState()`; handler delegates to capability |
| `CanonicalDurableRunCoordinator.kt` | + `milestoneStateStore` constructor param |
| `CanonicalRuntimeCapabilityAccess.kt` | + milestone store param; populates MILESTONE_OPERATIONS_CAPABILITY |
| `ExecutionBoundaryFactory.kt` | + milestone store param; passes to RegistryExecutionBoundary |
| `RegistryExecutionBoundary.kt` | + `adapt(milestoneStateStore)` overload; uses store when building capability access |
| `CoreMilestoneStepUnitTest.kt` | Updated to create own store; + missing capability tests |
| `CoreMilestoneStepContractSuiteTest.kt` | Updated to pass store to harness |

## 4. Test Updates

### CoreMilestoneStepUnitTest (18 tests)

**Before:**
```kotlin
@BeforeEach
fun setup() {
    CoreMilestoneStep.resetState()  // hack to reset singleton state
}
```

**After (S2-A9 spike):**
```kotlin
private lateinit var milestoneStore: MilestoneStateStore

@BeforeEach
fun setup() {
    milestoneStore = MilestoneStateStore()  // fresh store per test
    val capabilities = MapStepCapabilityAccess(mapOf(
        EVENT_SINK_CAPABILITY to eventStore,
        MILESTONE_OPERATIONS_CAPABILITY to MilestoneOperationsAdapter(milestoneStore),
    ))
    handlerContext = StepHandlerContext(..., capabilities = capabilities)
}
```

No more `resetState()` — each test gets its own store.

### New tests added

- `missing capability — execution fails when MILESTONE_OPERATIONS is absent`
- `capability admission — admission succeeds when both EVENT_SINK_CAPABILITY and MILESTONE_OPERATIONS_CAPABILITY are present`
- `capability admission — admission rejects when MILESTONE_OPERATIONS is absent`

## 5. Capability Interface

```kotlin
interface MilestoneOperations {
    fun peek(): Int?
    fun advance(ordinal: Int): MilestoneAdvanceResult
}

sealed class MilestoneAdvanceResult {
    data class Reached(override val previous: Int?) : MilestoneAdvanceResult()
    data class Aborted(override val previous: Int?, val reason: String) : MilestoneAdvanceResult()
}
```

## 6. Why This Is Not Global State

| Aspect | Singleton `object` with `var` | Run-scoped store via capability |
|--------|------------------------------|-------------------------------|
| Lifetime | Classloader ( JVM process) | Coordinator instance (pipeline run) |
| Test isolation | Requires explicit `resetState()` | Each test creates own store |
| Multi-run scenarios | State bleeds across runs | Clean per-run isolation |
| Concurrent pipeline runs | Shared state (bug) | Isolated per coordinator |

## 7. Thread Safety

`MilestoneStateStore` uses `@Synchronized` to support concurrent milestone invocations within a single run (e.g. parallel stages). The milestone ordinal is pipeline-run global, so concurrent access is serialized.

## 8. Comparison with Similar Patterns

| Step | State carrier | Pattern |
|------|---------------|---------|
| `core.sh` | `ShOperationsAdapter` | Process executor, not stateful |
| `core.pwd` | `WorkspaceIdentity` | Immutable value (no state) |
| `core.pwd.tmp` | `TemporaryWorkspaceOperationsAdapter` | Creates dirs, idempotent |
| `core.milestone` | `MilestoneStateStore` | **Run-scoped mutable state** |

The milestone case is unique because it **must** track state across invocations within a run. The pattern mirrors the legacy dispatcher.

## 9. Open Questions

1. **Replay semantics:** The `MilestoneStateStore` is in-memory. On replay with a fresh coordinator, the store is fresh — matching the legacy's behavior (milestones are not journaled for replay).

2. **Parallel stages:** If two parallel branches both execute milestones, they share the same store. Is this correct? The legacy dispatcher is also shared across parallel branches (same coordinator instance). **Decision:** shared store is correct — milestone monotonicity is per pipeline run, not per branch.

---
**SPIKE CLOSED — state seam implemented, tests updated, receipt documented.**
