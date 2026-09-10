# CTX-P CLOSURE RECEIPT — explicit immutable execution context

Cycle window: f5fdd150 (CTX-P0, docs-only cycle base) .. CTX-P4.
Fitness: `FArchExecutionContextOwnershipTest` (F1..F6, 6/6).
Global round gate: see §Gate below.
**CTX-P delta gate: PASS. New failures: 0. Baseline widened: 0.**

## Motivation (classification preserved)

No production race existed at baseline. CTX-P removed a **latent
shared-mutable ownership hazard** before BranchScope can grow concurrent
structural scopes. Baseline inventory (CTX-P0): BranchScope is
fail-closed atomic-only; the only overlay write reachable during a stage
was the sequential pre-fan-out catchError marker. The `var contextStack`
+ `finally`-restore idiom was an armed hazard, not a reproduced bug.

## Architecture before

```text
CanonicalDurableRunCoordinator
└─ var contextStack: ContextStack      (shared mutable authority)
   ├─ push: applyOverlay / dispatchBody(dir, env) / withCredentials
   ├─ semantic read: catchError fold-walk (top-down i--)
   ├─ leak check: isEmpty at stage boundary
   └─ finally restore: parentStack captured at 4 sites
```

## Architecture after

```text
ExecutionContext_n  (immutable domain value, pipeline-domain)
        +
structural input (overlay / scope marker)
        ↓ pure transition (pushed / exitCatchError → ContextTransition ADT)
ExecutionContext_n+1

branch execution: explicit immutable context value
(derive(parent, branch) — today identity; never coordinator state)
```

## P1 — ExecutionContext immutable domain value

`pushed()` (single derivation), `trailingCatchErrorChain()` (pure
query), `exitCatchError()` → `ContextTransition.Advanced/Rejected`
(added in P2 correction). HF0 laws GREEN.

## P2 — threading + the genuine RED

Equivalence RED (ERR-S-007, real installed-pipeline run): P1 had pinned
the catch chain **outermost-first**; the baseline walk starts at the top
frame and moves outward — **innermost-first**. Contractual correction of
a mis-characterized law, not a semantics change. Second learning: the
old `pop()` was the **ACTIVE→CLOSED scope transition**, not bookkeeping;
replaced by the typed `exitCatchError()` with fail-closed underflow
(`CATCH_ERROR_UNDERFLOW` → same IllegalStateException). `emitted=false`
preserved as a no-op exit. Threading: `dispatch` returns
`Dispatched(outcome, context)`; the coordinator `var`, all 4
parentStack captures and all finally-restores are deleted (structural
probe 0/0/0). `applyOverlay` replaced by pure `deriveOverlay`.

## P3 — deterministic concurrency proof (test-only model)

Legacy save/set/finally-restore algorithm: **lost update demonstrated
deterministically** under barrier-forced interleaving (final shared state
is one branch's stale parent restore; composed P+A+B unrepresentable).
Production reachability at baseline: **false**. Immutable model under the
same schedule: parent unchanged, zero sibling contamination, completion
order irrelevant, failure under supervisorScope mutates nothing, no
restore path exists. Branch derivation referentially transparent.

## Scope semantics (frozen distinction)

- Linear structural scope (catchError Enter/body/Exit in IR):
  `Context_n + Node_n → Context_n+1` across siblings.
- Lexical recursive scope (dir/withEnv/withCredentials bodies):
  `child = parent.pushed(...)`, caller keeps parent, no restore exists.

## CatchError (EM-5/6, unchanged semantics)

- failure fold observation order: inner → outer (innermost-first chain)
- `CatchErrorTriggered` marker: scope exit transition only; **zero
  duplicate event publishing**
- nested ERR-S-007: exactly 2 events (inner FAILURE re-throw → outer
  UNSTABLE suppress); a later unrelated failure observes **0 stale
  frames** (HF0 P2-7)
- underflow (Triggered without active scope): fail-closed, invariant
  preserved by test with its original law

## Parallel

`executeBranchSteps` receives the context explicitly
(`branchContext = executionContext`); ambient lookup = 0 (fitness F3);
derivation deterministic (replay-safe). PAR-D laws intact: UatDsl003 9/9,
UatParallelBlockDurable 3/3, ParallelReconciler HF0 13/13.

## Focused regression (fresh evidence)

CtxPExecutionContextTest 12/12 · CtxPConcurrencyOwnershipTest 5/5 ·
FArchExecutionContextOwnershipTest 6/6 · ErrorHandlingTest 7/7
(ERR-S-001..008 incl. 007) · CanonicalCoordinatorScopeStackTest 5/5 ·
UatDsl003ParallelTest 9/9 · UatParallelBlockDurable 3/3 ·
ParallelReconcilerTest 13/13. installDist-equivalent full chain
(DSL → compiler linearization → canonical coordinator → immutable
context transitions) exercised by ErrorHandlingTest + UatDsl003, which
spawn the real pipeline process per scenario.

## Durable impact

fingerprint changed: **NO** · journal schema changed: **NO** ·
replay semantics changed: **NO** · OpId changed: **NO**.
`ExecutionContext` is NOT a durable authority and carries no
secrets/ShOptions/journal/registry/scope references (fitness F4/F6).

## Gate

`timeout 1619 ./gradlew -p v2 check` (budget = last recorded full check
1245s x 1.3, cap 1800): BUILD FAILED in 21m2s — NON-ZERO, within budget.

Global round gate: **NON-ZERO due exclusively to known baseline debt.**
CTX-P delta gate: **PASS**. New failures: **0**. Baseline widened: **0**.

Self-caught during the gate and fixed before closure: the first P3-A
model read the final legacy-race state after a thread join, leaving the
loser-of-the-last-restore timing-dependent — a violation of this cycle's
own zero-timing-assumption rule. Fixed by sequencing the two restores
deterministically (barrier-lattice equivalent); the demonstration
schedule and conclusion are unchanged. CtxPConcurrencyOwnershipTest 5/5
post-fix.

Rule 16 (exact, base cbd0db1a anchor for the historical coordinator debt;
CTX-P cycle base f5fdd150):
- `CanonicalDurableRunCoordinatorTest`: 12/26 failing, **same 12 row
  identities by name** (CASE A, verified twice: P2 and P4), root cause
  unchanged (legacy test composition without registry metadata wiring;
  production unreachable).
- `DurableProtocolInvocationCharacterizationTest`: 1/8, row `a1-4
  lifecycle spine...` proven identical at base cbd0db1a via detached
  worktree run.
- All other previously-failing suites: unchanged identities.
- Gate details: BUILD FAILED (non-zero) within derived budget; every
  failing suite matches the E-EM-11/PAR-D baseline ledger.

## Architecture counters

```text
mutable context authorities        = 0   (F1)
ambient context authorities        = 0   (F2)
context restore paths              = 0   (F5)
branch explicit-context path       = 1   (F3)
ContextTransition pure data        = 1   (F4)
ExecutionContext durable authority = NO  (F6)
```

## Explicit open items (NOT CTX-P gaps)

- BranchScope block-step widening — OPEN (now safe by construction)
- dir/withEnv concurrent branch UAT — OPEN future
- credentials concurrent branch UAT — OPEN future
- Kotlin context parameters — OPEN (syntax decision, semantics settled)
- FailFast — OPEN (designed, not exposed)
- Flow output — OPEN
- fingerprint/context-sensitivity review — OPEN, separate durable decision

## REAL EXECUTABLE EXAMPLES (P4-EX)

Additional gate requested after CTX-P closure: every documented capability
must have a real `.pipeline.kts` example executed by the REAL installed CLI
(`:pipeline-application:installDist`), with expected outcomes asserted by
`examples/run.sh` and event-level contracts for 07–10. Compilation alone is
not acceptance.

### Evidence matrix

| Example | Compiles | Real CLI run | Exit / outcome | Contract |
|---|---|---|---|---|
| 01-hello | ✓ | ✓ | 0 / success | — |
| 02-multi-stage | ✓ | ✓ | 0 / success | — |
| 03-shell | ✓ | ✓ | 0 / success | — |
| 04-kotlin-control-flow | ✓ | ✓ | 0 / success | — |
| 05-failing-step | ✓ | ✓ | 1 / failure | StepFailed(kind=SCRIPT), exit 3 |
| 06-durable | ✓ | ✓ (×2, same `--db`) | 0 / success | rerun reuses prior run (ReusePriorRun default); `--rerun` forces fresh |
| 07-catch-error | ✓ | ✓ | 0 / unstable | exactly 2 `CatchErrorTriggered`, inner FAILURE → outer UNSTABLE (ERR-S-007 innermost-first) + post-catch echo executes |
| 08-parallel | ✓ | ✓ (×2, same `--db`) | 0 / success | 2nd run reuses terminal aggregate: 0 `ParallelBranchStarted`, 0 `StepStarted` |
| 09-retry | ✓ | ✓ (×2, same `--db`) | 0 / success | exactly 2 `RetryAttemptFinished`: failed → succeeded |
| 10-timeout | ✓ | ✓ | 1 / failure | `TimeoutScheduled` + `StepFailed("durable shell timed out")` |

Gate invocation: `examples/run.sh` — full run GREEN, exit 0, all 10 examples
(fresh XML-equivalent evidence: full gate log, 2026-09-10, installDist from
current HEAD).

### Findings captured while building the gate (no production changes)

1. **CLI flag order is positional**: flags are parsed only while they lead the
   argument list (`run --db X script`); `run script --db X` silently ignores
   `--db` (no durable journal, fresh runId). Examples and README now use the
   leading-flag form. CLI-side strictness (reject trailing flags) is
   **OPEN, separate item, not a CTX-P gap**.
2. **Durable rerun semantics clarified**: default policy is ReusePriorRun
   (second run with the same `--db` reuses/resumes; zero re-execution for 08);
   `--rerun` = StartFreshRun; `--resume` = ResumePriorRun. Cross-run reuse of a
   COMPLETED run is parallel terminal-aggregate reuse (P6); effectful `sh`
   steps re-execute on resume by recoverable policy. README updated to state
   exactly this (previous text overclaimed "skips already-completed effects").
3. **Journal replay on durable reruns**: the CLI reprints prior journal events
   with their ORIGINAL timestamps; contract checks must scope "new events" by
   `occurredAt > max(previous run)` — done in `run.sh` (this is the INC-021d
   merged-stream debt, documented, not fixed here).
4. `timeout` terminal outcome is `failure` (not UNSTABLE as earlier example
   comment claimed); example comment corrected. UNSTABLE semantics remain the
   catchError domain (07).
