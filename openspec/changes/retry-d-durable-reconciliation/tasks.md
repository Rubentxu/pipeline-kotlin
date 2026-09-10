# Tasks: RETRY-D — durable reconciliation for canonical retry blocks

All production tasks are blocked on acceptance of the proposed ADR and this design.
Do not mark E-EM-11 closed from this work alone.

## D0 — authority and source grounding — ✅ COMPLETE

- [x] Map retry control, attempt, and child canonical `OpId` identities.
- [x] Prove the installed-distribution fail → success → rerun duplicate.
- [x] Prove no current retry-control journal fact and no deterministic Window C seam.
- [x] Reject the prior aggregate draft because it omitted inherited body/branch identity,
  did not prove Window C, and did not provide canonical divergence semantics.

## D1 — design gate — IN REVIEW

- [x] Draft `retry-d-durable-reconciliation` proposal and design.
- [x] Draft ADR-0075 as **PROPOSED**.
- [ ] Approve the retry-control durable fact, exact canonical control input encoder, and
  journal-attempt reuse. Owner: product/architecture.
- [ ] Add the recovery invariant to `RECOVERY_DURABILITY.md` after ADR approval.

## D2 — implementation, only after D1 approval

- [ ] Add a pure exhaustive `RetryReconciliationState` transformation over control and
  exact child durable facts.
- [ ] Persist/reconcile retry control facts before scheduling child effects.
- [ ] Preserve inherited parent bodyPath and canonical branch identity when deriving the
  retry control `OpId`.
- [ ] Keep child effects on canonical `dispatch()` only.
- [ ] Add no production fault-injection hook and no event-as-authority behavior.

## D3 — focused proof

- [ ] R1 immediate success then rerun: child executions total = 1.
- [ ] R3 exhaustion then rerun: no new child execution and no attempt N+1.
- [ ] R4 failed first attempt then fresh coordinator: attempt 1 adds zero, attempt 2 executes.
- [ ] R5 Window C with a test-only journal decorator: child success is persisted,
  retry control terminal fact is absent, fresh coordinator reconstructs success with
  zero child executions.
- [ ] R6 injects incompatible retry-control fingerprint under the same logical identity;
  canonical divergence failure and zero child dispatches.

## D4 — public acceptance and regressions

- [ ] R2 executes the exact `:pipeline-application:installDist` binary. First run fails
  then succeeds; second run uses the same DB/control state and adds neither a body
  execution nor `retry-ok` output.
- [ ] Focused retry test class and XML canary green.
- [ ] `FArchLeg1ExecutionAuthorityTest` remains green.
- [ ] Existing installed timeout and parallel UATs remain green after retry is proven.
- [ ] Update B13/E-EM-11 receipt truthfully. E-EM-11 remains OPEN until its separate
  event and grammar obligations are satisfied.

## Commit gate

No RETRY-D production commit before R2 installed distribution, R5 real Window C, and
R6 fail-closed divergence are green. A documented harness gap is not RETRY-D closure.
