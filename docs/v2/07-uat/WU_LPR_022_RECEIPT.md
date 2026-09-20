# WU-LPR-022 — Retry/timeout engine policy seam (closure receipt, partial)

**Status:** CLOSED — POLICY-ONLY slice. The full WU-LPR-022 spec
("Move generic durable control execution behind engine. Kill/resume/replay
divergence tests mandatory") is split across multiple cycles; this WU
lands the **pure policy seam** only.

**Outcome:** `DurableControlEnginePolicy` interface + `EngineDirective`
sealed ADT + `DefaultDurableControlEnginePolicy` implementation. Maps
`RetryReconciliationDecision` × `maxAttempts` → typed directive
(`LaunchChild`, `CloseTerminal`, `FailClosed`). Pure, total, no effects.

The canonical coordinator still inlines its retry loop — this WU produces
the **policy contract** the coordinator (and future engines) will consume.
The effectful engine (loop driver, journal write, child dispatch,
kill/resume/replay tests) is WU-LPR-022-FOLLOWUP / WU-LPR-024.

---

## 1. Scope

> Move generic durable control execution behind engine. Kill/resume/replay
> divergence tests mandatory.

Per `docs/v2/05-roadmap/LPR_WORK_UNITS.md` (WU-LPR-022, L24-26).

**This WU produces**:
- The pure policy seam (`DurableControlEnginePolicy`).
- The `EngineDirective` typed ADT.
- 20 tests pinning the policy contract (boundary cases + exhaustiveness).

**This WU does NOT produce** (deferred to WU-LPR-022-FOLLOWUP / WU-LPR-024):
- The effectful engine loop driver.
- Migration of the canonical coordinator's inline retry loop.
- Kill/resume/replay divergence tests against real journal state.
- `WaitUntil` policy table (the seam is retry-shaped; WaitUntil folds into
  the same engine with a different directive mapping).

## 2. Three-layer durable control model

```text
1. RECONCILE   RetryReconciler.reconcile(input)
               → RetryReconciliationDecision       (already pure, already exists)

2. POLICY      DurableControlEnginePolicy.directiveFor(decision, maxAttempts)
               → EngineDirective                   (NEW — this WU)

3. RUNNER      CanonicalDurableRunCoordinator.dispatchBody
               performs the directive's effects   (still inlined, WU-LPR-024)
```

The policy is the **typed contract** between the reconciler (truth) and the
runner (effects). Adding a new decision case forces both the reconciler
(`when` exhaustiveness) and the policy (`when` exhaustiveness) to be
revisited; the runner consumes a closed ADT that already enumerates its
legal next actions.

## 3. Decision → Directive table

| Decision | Precondition | Directive |
|----------|--------------|-----------|
| `ScheduleAttempt(N)` | `N ∈ [1..max]` | `LaunchChild(N, fresh=true)` |
| `ScheduleAttempt(N)` | `N > max` or `N < 1` | `FailClosed("...attempt=N beyond maxAttempts=max...")` |
| `ResumeAttempt(N)` | `N ∈ [1..max]` | `LaunchChild(N, fresh=false)` |
| `ResumeAttempt(N)` | `N > max` | `FailClosed` |
| `CloseSuccessFromChild(N)` | always | `CloseTerminal(success=true, N)` |
| `AdvanceAfterFailure(_, T)` | `T ∈ [1..max]` | `LaunchChild(T, fresh=true)` |
| `AdvanceAfterFailure(_, T)` | `T > max` | `FailClosed` |
| `ReuseSuccess(N)` | always | `CloseTerminal(success=true, N)` |
| `ReuseFailure(N)` | always | `CloseTerminal(success=false, N)` |
| `RejectDivergence(_, reason)` | always | `FailClosed(reason)` |

Engine is **fail-closed** against decision/budget incoherence. ZERO children
launched on `FailClosed` — the runner surfaces it as a typed
`PipelineFailure(FailureKind.ENGINE, ...)`.

## 4. Test surface (20 tests, 0 failures, 0 errors)

`v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WULpr022DurableControlEnginePolicyTest.kt`

| Nested group | Tests |
|--------------|-------|
| `ScheduleAttempt` (budget edge, beyond, below 1, negative) | 6 |
| `ResumeAttempt` (in-budget, edge, beyond) | 3 |
| `CloseSuccessFromChild` (in-budget, attempt > budget) | 2 |
| `AdvanceAfterFailure` (in-budget, edge, beyond, below 1) | 4 |
| `ReuseSuccess / ReuseFailure` (in-budget, attempt > budget) | 3 |
| `RejectDivergence` (reason verbatim) | 1 |
| `Exhaustiveness` (top-level total over ADT) | 1 |

Total = 20 tests, 7 nested groups, 0 failures, 0 errors, 0 skipped.

## 5. Build evidence

```text
L0: ./gradlew -p v2 :pipeline-domain:compileKotlin
    BUILD SUCCESSFUL in 12s

L1: ./gradlew -p v2 :pipeline-domain:test --tests 'WULpr022DurableControlEnginePolicyTest'
    BUILD SUCCESSFUL in 16s (20 tests, 0/0/0)

L2: ./gradlew -p v2 :pipeline-domain:test
    BUILD SUCCESSFUL in 13s (523 tests, 0 failures, 0 errors, 0 skipped)
```

## 6. Production code impact

**Zero production coordinator migration in this WU.** The canonical
`CanonicalDurableRunCoordinator.dispatchBody` continues to inline its
retry loop. The seam is the **migration target**: a future WU that
publishes the canonical types (WU-LPR-024) and migrates the inline loop
to a typed engine will consume `DefaultDurableControlEnginePolicy` to
decide each attempt's next action.

## 7. Findings for follow-up

| ID | Finding | Suggested follow-up | Severity |
|----|---------|---------------------|----------|
| F1 | Canonical coordinator's retry loop is inlined; no engine driver yet | **WU-LPR-022-FOLLOWUP** (engine driver + journal write + child dispatch) | `GATE_1_BLOCKER` per spec |
| F2 | `WaitUntil` policy table not yet modelled as an `EngineDirective` mapping (its reconciler uses different decision ADT) | **WU-LPR-022-FOLLOWUP** | `POST_LPR` |
| F3 | Kill/resume/replay divergence tests mandatory per spec — not yet written | **WU-LPR-022-FOLLOWUP** (golden parity + divergence tests against real journal state) | `GATE_1_BLOCKER` per spec |
| F4 | `core.retry` / `core.timeout` / `core.waitUntil` StepKey registration uses the existing inline path; engine migration does not change StepKey registration | folds into WU-LPR-024 | `POST_LPR` |

## 8. Auto-continue

The LPR-2 train per the roadmap is `WU-LPR-020 → 021 → 022 → 023 → 024`.
The next slice is **WU-LPR-023** (Parallel migration — Named bodies +
BranchInvoker, composable siblings, no coordinator-specific branch).
WU-LPR-023 is closer in size to WU-LPR-021 (seam creation, no coordinator
migration): `StageBody.Parallel(branches: List<StageNode>)` is already
named, the missing piece is a typed `BranchInvoker` port that the engine
consumes for fan-out.

---

**CLOSED (POLICY-ONLY) — 2026-09-20.**
