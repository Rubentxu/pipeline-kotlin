# Tasks: E-EM-11 — canonical M2-R1 runtime parity (retry/timeout/parallel)

Evidence: HEAD c085f8be (2026-09-08). Ground truth + design in proposal.md / design.md. Base = clean
canonical coordinator + quarantined UatDsl003 / UatDsl001-full-grammar (BLOCKED-ON-EM E-EM-11).

## T0 — Payload/event contract ground-truth (do first) — ✅ DONE (2026-09-08)
Contracts captured from DomainEvent.kt + legacy PipelineRun emitters:
- `RetryAttemptStarted/Finished(attemptNumber:Int, maxAttempts:Int, stepName, stepType, stageIndex,
  stepIndex[, outcome on Finished])`.
- `TimeoutScheduled(timeoutSeconds:Long, timeoutAction:String, stepName?, stepType?, stageIndex?,
  stepIndex?)`. `TimeoutTriggered` EXISTS (kind present in DomainEvent).
- `ParallelBranchStarted/Finished(branchIndex:Int, branchName:String, parentStageIndex:Int[, outcome
  on Finished])`.
- Compiler encodes retry (`count`) and timeout (`time`/`unit`) into `core.retry`/`core.timeout`
  BlockStepNode payloads via `blockPayload`/`encodePayload`; runtime must decode the same contract.
Follow-up at apply: confirm exact retry count-vs-attempts semantics legacy used so the re-opened
UatDsl001 assertions match (does `maxAttempts == count`, attemptNumber 1-based?).

## T1 — D1 real retry semantics + RetryAttempt projection
Dedicated `dispatchRetryBlock` path in the coordinator (loop + retry-on-failure + events + fail-closed).
Verify: retry unit test (green), coordinator/EM suites unchanged.

## T2 — D2 real timeout semantics + projection
Dedicated timeout path: deadline, `TimeoutScheduled`, typed TIMEOUT abort, replay-safe FAILED_TIMEOUT.
Verify: timeout unit test green, no hang (rules 7/10/29-31).

## T3 — D3 parallel (ADR fork A or B) + ParallelBranch projection
Record the fork ADR, then wire the chosen parallel path in the coordinator + branch-indexed OpId rows +
`ParallelBranch*` events. Verify `UatDsl003ParallelTest` green.

## T4 — re-open quarantined full-grammar + full round
Remove the `@Disabled` from the three `UatDsl001JenkinsFamiliarityTest` full-grammar methods (keep
UatDsl003 re-opened in T3). Run full DSL/EM/coordinator/application affected suites; confirm ERR-S,
stage bookends, UatEvt001 stay green.

## Verify
- T1: retry semantics (attempts stop on success; final failure fail-closed) + `RetryAttempt*` present.
- T2: timeout aborts as TIMEOUT, no hang, `TimeoutScheduled` observed, replay-safe.
- T3: `UatDsl003ParallelTest` green on canonical path.
- T4: `UatDsl001JenkinsFamiliarityTest` full-grammar green; no regression in coordinator/EM/DSL.
- Exit: proposal success criteria all met.
