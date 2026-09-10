# CTX-P0 — Parallel Execution Context Isolation: Inventory & Design

Base: PAR-D archived (receipt `docs/v2/07-uat/PAR_D_CLOSURE_RECEIPT.md`).
Scope: inventory/design only. NO production change in CTX-P0.

---

## 1. Grounded model (as it exists today)

### 1.1 The value type is already immutable

`ContextStack` (`pipeline-domain/.../CompiledPipeline.kt:203`):

```kotlin
data class ContextStack(val frames: List<ContextOverlay>)   // immutable value
fun push(o) = ContextStack(frames + o); fun pop() = ...; fun peek() = ...
```

`ContextOverlay` is a sealed ADT: `Environment(EnvironmentSpec)`, `Cwd`,
`Credentials(bindingId)`, `OutputDecorator`, `CancellationScope`,
`CatchErrorOverlay`, `TimeoutOverlay`, `RetryOverlay` — all `@Serializable`
data classes. No thread-locals, no coroutine-locals anywhere in the model.

### 1.2 The mutation surface is ONE coordinator field

`CanonicalDurableRunCoordinator.kt`:

```kotlin
private var contextStack: ContextStack = ContextStack.EMPTY   // L370
```

This `var` is the entire shared-mutable-state problem. Every push/pop is a
field reassignment guarded only by a captured-`parentStack` + `finally`
restore idiom:

| Site | Operation | Guard |
|---|---|---|
| L408 | reset to EMPTY (stage begin) | sequential |
| L426 | leak check (stage end): `check(isEmpty)` | read |
| L595-598 | catchError fold-walk `frames[i] is CatchErrorOverlay` | read |
| L818/829 | `applyOverlay` push/pop (CatchErrorEntered/Triggered) | unguarded reassign |
| L1316→1526 | `dispatchBody`: `parentStack` capture → push(dir/env) → `finally { contextStack = parentStack }` | finally restore |
| L1359 | `dir` push `Cwd` | inside dispatchBody |
| L1395 | `withEnv` push `Environment` | inside dispatchBody |
| L1841→1883 | `withCredentials`: `parentStack` capture → push `Environment(credential values)` → finally restore | finally restore |

### 1.3 The FUNCTIONAL context already flows as an immutable parameter

`ShOptions` (env map, workingDirectory, workspaceRoot) is passed BY VALUE
through `dispatch(... stageShOptions ...)` and derived immutably:

```kotlin
val childShOptions = stageShOptions.copy(env = stageShOptions.env + scope.env)   // withCredentials
stageShOptions.copy(workingDirectory = scope.target)                             // dir
val mergedEnv = scope.parentEnv + envOverrides                                   // withEnv
```

Branches already receive their own `stageShOptions` value. There is no
read of `contextStack` to build shell environment/cwd for step execution.
The functional execution context is NOT the problem; the structural
overlay stack is.

### 1.4 What `contextStack` is actually USED for (not just pushed)

1. **catchError fold-walk** (L595): on failure, walk trailing
   `CatchErrorOverlay` frames to publish `CatchErrorTriggered` at the real
   failure point (EM-5/EM-6). The only *semantic read* of the stack.
2. **Leak detection** (L426): `check(contextStack.isEmpty)` at stage end —
   structural unwind invariant.
3. **Everything else** is pure bookkeeping restored by `finally` and never
   read by step execution.

## 2. Reachability from concurrent branches — the decisive finding

**Branch grammar is fail-closed atomic-only today.** `BranchScope`
(`PipelineDsl.kt:1852`) admits exactly: `echo`, `sh`, `error`, `sleep`.
No `dir`, `withEnv`, `withCredentials`, `timeout`, `retry`, `catchError`,
`script`, and no nested `parallel` inside a branch. `executeBranchSteps`
(PAR-D) dispatches only these atomic steps; the `BlockStepNode` route
(L166 → `dispatchBody`, the sole writer of overlay frames during bodies)
is **unreachable from branch coroutines**.

Stage-level `catchError { parallel { ... } }` IS reachable: the
`CatchErrorOverlay` is pushed once, sequentially, before the branch fan-out
(`applyOverlay` runs before durable resolution, C6). Branches never push
or pop that frame; the fold-walk reads it after the join, sequentially.
No concurrent write exists on this path.

### Classification (per the CTX-P contract)

> **No reproducible production race exists today.** The shared `var` is a
> **potential architectural hazard**: the hazard is armed by two concrete
> future events — (a) widening `BranchScope` to block steps (likely, it is
> the obvious next DSL slice), or (b) any future code path that pushes an
> overlay from inside `executeBranchSteps`. Either event makes branch A
> mutation observable from branch B through the coordinator field, and the
> `finally`-restore idiom becomes a lost-update race (coroutine
> interleaving between capture and restore).

This is **architecture-unsafe, not bug-reproduced**. Therefore CTX-P is a
**behavior-preserving isolation refactor with equivalence tests** (the
contract's second branch), plus HF0 laws that become the RED for the
refactor (deterministic seam tests that WOULD fail against the shared
`var` if the branch grammar were widened — written against the new model
so the widening is safe by construction).

## 3. Ownership graph

```text
CanonicalDurableRunCoordinator
├── contextStack: var ContextStack      ← SHARED MUTABLE (the hazard)
│     writers: applyOverlay, dispatchBody, withCredentials, stage reset
│     readers: catchError fold-walk, leak check
├── stageShOptions: ShOptions (param)   ← immutable, correctly derived per call
│     derivation: projectShellScope + copy at dir/env/withCredentials sites
├── journal / eventSink / stepRegistry  ← NOT context; stay coordinator-owned
└── (proposed) ExecutionContext value   ← replaces BOTH roles of contextStack
```

`ExecutionContext` MUST NOT own: journal, event store, StepRegistry,
coroutine scope, process executor, service locator. It is a value
describing the execution environment (workspace, environment, credentials
in scope, active structural overlays).

## 4. Mutation/reachability table

| Element | Class | Branch-reachable today? | Notes |
|---|---|---|---|
| `contextStack` var | shared mutable | **read-only** (catchError frame pushed pre-fan-out) | hazard armed by grammar widening |
| `ShOptions.env/cwd` | immutable derived value | yes, per-branch | correct by construction |
| credential `scope.close()` | local mutable (borrow/close) | no (withCredentials unreachable in branch) | |
| `applyOverlay` C6 replay push | shared mutable write | no (stage level, sequential) | |
| `EventSink` sequence/occurredAt | coordinator-owned | concurrent appends | out of scope (events are not context) |
| `RuntimeConfig` holder (`PipelineDsl.kt:900`) | build-time global | no (construction phase) | not runtime context |
| ThreadLocal / CoroutineContext | none found | — | confirmed zero |

## 5. Race scenarios (potential, not reproducible)

- **R1 lost update**: branch A `dir("a")` captures `parentStack=S`;
  branch B pushes `Cwd(b)`; A's `finally` restores `S`, erasing B's frame
  while B is still inside its body. Leak check at stage end may or may not
  fire depending on timing. (Arms when blocks enter branches.)
- **R2 fold-walk misread**: concurrent pushes interleave with the
  catchError fold-walk; trailing-frame scan sees a frame from another
  branch. (Arms with R1.)
- **R3 applyOverlay vs branch**: only if overlays ever execute inside
  branches — currently impossible (fail-closed).

## 6. Proposed immutable context model

Types are indicative, grounded on what exists:

```kotlin
// pipeline-domain; replaces the coordinator var. The stack survives as a
// VALUE: List<ContextOverlay> structural copy (plain List + copy is enough;
// no persistent-collection dependency).
data class ExecutionContext(
    val overlays: List<ContextOverlay> = emptyList(),   // current ContextStack.frames
    // ShOptions remains the functional carrier; whether it folds into
    // ExecutionContext or stays a parallel parameter is decided in CTX-P1
    // after the equivalence suite exists. Single-authority outcome preferred.
)

fun ExecutionContext.pushed(o: ContextOverlay): ExecutionContext   // total
fun ExecutionContext.droppedTop(): ExecutionContext                // total, no underflow throw
fun ExecutionContext.trailingCatchError(): List<CatchErrorOverlay> // fold-walk read
```

Coordinator change (behavior-preserving):

```text
dispatch / dispatchBody / dispatchWithCredentialsBlock / applyOverlay
receive `context: ExecutionContext` as a PARAMETER and return the
successor context alongside the outcome (or thread it via a small
scoped value); the `var` and the finally-restore idiom are deleted.
Stage-end leak check becomes a property of the returned value.
```

Branch derivation (the CTX-P law, ready for when the grammar widens):

```text
executeBranch derives branchContext = parent.derive(branch)  // pure, per coroutine
```

**Kotlin context parameters**: evaluated, NOT adopted in CTX-P. The
property that matters is "immutable + branch-local value"; a
`context(ExecutionContext)` transport is a syntax decision deferred until
the semantics are green and the coordinator threading shape is known.
Avoids a mass migration dogma trap.

## 7. HF0/HF1 RED plan (deterministic seam, zero sleeps)

HF0 (pure, milliseconds) — the laws become executable before production
moves, so the grammar widening lands against an already-green model:

- CTX-1: A.withEnv(X=A), B.withEnv(X=B) → derived envs independent,
  parent unchanged. (Derivation-level today; coordinator-level at HF1.)
- CTX-2: dir("a")/dir("b") → independent workingDirectory values.
- CTX-3: nested dir { withEnv { sh } } ×2 branches → no cross-derivation.
- CTX-4: completion order permuted → same resulting contexts (pure
  function of derivation inputs, trivially true once context is a value —
  pinned by test).
- CTX-5: branch failure/cancellation → parent and sibling context values
  unchanged (value semantics; pinned).
- CTX-6: replay/resume — branch context is a pure function of
  (parentContext, structural branch identity), reconstructible from
  durable facts only.
- CTX-7: unwind — every pushed frame is dropped exactly once; leak check
  becomes `check(returned.overlays.isEmpty())`.

HF1 (in-process coordinator): same laws through `dispatch` with block
steps driven from BOTH branches concurrently under a deterministic
barrier seam (CyclicBarrier-style seam injected at scope-entry points),
proving the R1 lost-update scenario cannot exist in the new shape. This
HF1 suite IS the RED against the current `var` if run before the refactor
— it is the honest reproduction attempt, executed and reported even if
the hazard is unreachable from the real DSL grammar.

## 8. Fingerprint / durable-impact assessment

`Fingerprint.compute(input, stepId, replayPolicy, attempt)` = stepId +
params + runId + attempt + policy. Context (env values, cwd, credentials)
is **NOT** part of the fingerprint; it reaches execution via `ShOptions`
outside the durable contract.

- No CTX-P change touches fingerprint computation. Parity law holds.
- Observation (NOT a change, NOT silently absorbed): a `sh` step's
  behavior depends on its environment delta, which is not fingerprinted.
  Whether context-sensitive inputs should enter the fingerprint is a
  **separate durable decision** (STOP-and-report per contract) — recorded
  here as an open item for the v2 roadmap, out of CTX-P scope.
- CTX-6 replay-context law is satisfiable without fingerprint changes:
  contexts derive deterministically from structure + parent, and the
  parent chain derives from the script IR, which is already durable.

## 9. Proposed slice sequence

- CTX-P1: HF0 `ExecutionContext` ADT + laws 1..7 (RED-first where they
  constrain the new model).
- CTX-P2: behavior-preserving coordinator threading (param-based, delete
  the `var` + finally idiom); equivalence = existing durable suites green,
  row-identical rule-16 delta.
- CTX-P3: deterministic-barrier HF1 race-proof pair (current-shape RED
  evidence archived, new-shape GREEN).
- CTX-P4: fitness (single context authority, no coordinator context var)
  + receipt. AGENTS law candidate only if CTX-P3 proves it.

Open items carried: fingerprint-context membership (durable decision,
separate); Kotlin context parameters (deferred, syntax not semantics).
