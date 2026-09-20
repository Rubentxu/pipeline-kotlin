# WU-LPR-023 — Parallel migration / BranchInvoker plan port (closure receipt, partial)

**Status:** CLOSED — PORT-ONLY slice. The full WU-LPR-023 spec
("Named bodies + BranchInvoker, composable siblings, no coordinator-specific
branch") is split across multiple cycles; this WU lands the **pure plan
port** only.

**Outcome:** `BranchInvoker` interface + `BranchPlan` sealed ADT +
`NamedBranch` carrier + `DefaultBranchInvoker` implementation. Maps
`ParallelDecision` × `branches` → typed plan (`LaunchAll` / `Resume` /
`NoOp`). Pure, total, no effects. Out-of-range indices are dropped
silently (reconciler is authoritative).

The canonical coordinator still inlines `runParallelStage` — this WU
produces the **plan contract** the coordinator (and future engines) will
consume. The effectful runner (supervisorScope launch + join + branch
events + aggregate closure row) is WU-LPR-024.

---

## 1. Scope

> Named bodies + BranchInvoker, composable siblings, no coordinator-specific
> branch.

Per `docs/v2/05-roadmap/LPR_WORK_UNITS.md` (WU-LPR-023, L28-30).

**This WU produces**:
- The pure plan port (`BranchInvoker`).
- The `BranchPlan` typed ADT (LaunchAll / Resume / NoOp).
- 16 tests pinning the plan contract (boundary cases + exhaustiveness).

**This WU does NOT produce** (deferred to WU-LPR-024):
- The effectful runner (parallel supervisorScope + join + branch events).
- Migration of `CanonicalDurableRunCoordinator.runParallelStage` to consume
  the port.

## 2. Three-layer parallel model

```text
1. RECONCILE   ParallelReconciler.reconcile(input)
               → ParallelDecision               (already pure, already exists)

2. PLAN        BranchInvoker.plan(decision, branches)
               → BranchPlan                     (NEW — this WU)

3. RUNNER      CanonicalDurableRunCoordinator.runParallelStage
               performs the plan's effects     (still inlined, WU-LPR-024)
```

`StageBody.Parallel(branches: List<StageNode>)` already names each branch
(via `StageNode.name`). The plan carries those names so the runner's
`ParallelBranchStarted(name=...)` events stay stable and human-readable.

## 3. Decision → Plan table

| Decision                       | Plan                                            |
|--------------------------------|-------------------------------------------------|
| `Start(branchIndices)`         | `LaunchAll(resolve(branchIndices, branches))`   |
| `ResumeBranches(branchIndices)`| `Resume(resolve(branchIndices, branches))`     |
| `ReuseSuccess`                 | `NoOp`                                          |
| `ReuseUnstable`                | `NoOp`                                          |
| `ReuseFailure`                 | `NoOp`                                          |
| `CloseFromChildren(outcome)`   | `NoOp`                                          |
| `RejectDivergence(reason)`     | `NoOp` (runner MUST NOT launch)                 |
| `RejectAmbiguousOutcome(reason)`| `NoOp`                                         |

`LaunchAll` and `Resume` carry `NamedBranch(index, name)` lists; the order
preserves the decision's order (not the named list's order), so a reconciler
that decides `[2, 0]` yields `[branch(2), branch(0)]`.

## 4. Test surface (16 tests, 0 failures, 0 errors)

`v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WULpr023BranchInvokerTest.kt`

| Nested group | Tests |
|--------------|-------|
| `Start` (all, subset, empty, out-of-range, order-preserve) | 5 |
| `ResumeBranches` (in-range, out-of-range) | 2 |
| `ClosureCases` (ReuseSuccess, ReuseUnstable, ReuseFailure, CloseFromChildren, RejectDivergence, RejectAmbiguousOutcome) | 6 |
| `NamedBranchInvariants` (negative index, blank name) | 2 |
| `Exhaustiveness` (top-level total over ADT) | 1 |

Total = 16 tests, 5 nested groups, 0 failures, 0 errors, 0 skipped.

## 5. Build evidence

```text
L0: ./gradlew -p v2 :pipeline-domain:compileKotlin
    BUILD SUCCESSFUL in 12s

L1: ./gradlew -p v2 :pipeline-domain:test --tests 'WULpr023BranchInvokerTest'
    BUILD SUCCESSFUL in 14s (16 tests, 0/0/0)

L2: ./gradlew -p v2 :pipeline-domain:test
    BUILD SUCCESSFUL in 13s (539 tests, 0 failures, 0 errors, 0 skipped)
```

## 6. Production code impact

**Zero production coordinator migration in this WU.** The canonical
`CanonicalDurableRunCoordinator.runParallelStage` continues to inline the
parallel dispatch (supervisorScope + async + join). The plan port is the
**migration target**: a future WU that publishes the canonical types
(WU-LPR-024) and migrates the inline loop to a typed engine will consume
`DefaultBranchInvoker.plan()` to decide which branches to launch/resume
for each reconciliation decision.

## 7. Findings for follow-up

| ID | Finding | Suggested follow-up | Severity |
|----|---------|---------------------|----------|
| F1 | Canonical coordinator's `runParallelStage` is inlined; no engine driver yet | **WU-LPR-024** (parallel engine driver + supervisorScope launch + join) | `POST_LPR` |
| F2 | `StageBody.Parallel(branches)` is the only fan-out shape; `StageBody.Matrix` exists in the closed ADT but is not wired into the engine | **WU-LPR-024** (matrix shape) | `POST_LPR` |
| F3 | `BodyExecutionPolicy.Parallel -> Unimplemented(policy.shape)` in `projectBodyExecution` — the parallel policy still has no interpreter (separate from this WU's `StageBody.Parallel` carrier) | **WU-LPR-024** | `POST_LPR` |

## 8. Auto-continue

LPR-2 train per the roadmap: `WU-LPR-020 → 021 → 022 → 023 → 024`.
The next slice is **WU-LPR-024** (InvocationEngine seam — extract
metadata/fingerprint/journal/replay/capability boundary while preserving
coordinator lifecycle). WU-LPR-024 is the largest single LPR-2 WU: it
publishes the canonical `BlockShellScope` / `BodyExecutionProjection` /
parallel types out of `CanonicalDurableRunCoordinator`, introduces the
canonical-projection → descriptor adapter, and migrates the inline loops
(retries, parallel branches, scope projections) behind the engines built
in WU-LPR-021..023.

---

**CLOSED (PORT-ONLY) — 2026-09-20.**
