# Proposal: E-EM-11 — canonical M2-R1 runtime parity (retry/timeout/parallel)

## Intent
Wire the M2-R1 parallel/retry/timeout surface into the canonical durable coordinator so it is real,
observable and re-opens the quarantined UATs. Evidence (2026-09-08, HEAD c085f8be): `dispatchBody`
treats `core.retry`/`core.timeout` as generic block bodies (iterated ONCE, no retry loop, no deadline,
no events), cannot run a `StageBody.Parallel` stage (throws "supports only linear stage steps", L276),
and emits no `ParallelBranch*`/`RetryAttempt*`/`TimeoutScheduled`. Only the superseded legacy
`PipelineRun` implements these; Main no longer routes to it. Root cause is the LF-0208 spine-migration
gap (same family as the closed ERR-S-004 bookends), NOT a DSL-surface defect. Design-gated milestone:
needs its own design + ADR decisions before implementation.

## Ground truth (fresh, 2026-09-08)
- `CanonicalDurableRunCoordinator.dispatchBody` (L650-797): generic body iteration; no retry/timeout
  semantics; `RetryOverlay`/`TimeoutOverlay` domain types exist but are never pushed by the coordinator.
- `run()` (L275-276): `(stage.body as? StageBody.Steps)?.steps ?: throw "…supports only linear stage steps"`.
- `Main` routes to the coordinator via `supportsCanonicalDurableExecution()`; legacy `PipelineRun`
  (sole emitter of the M2-R1 events) is unreachable.
- Quarantined (BLOCKED-ON-EM, LFC-2 a7a16c2c): `UatDsl003ParallelTest` + three
  `UatDsl001JenkinsFamiliarityTest` full-grammar methods.

## Scope
### In scope
- Real retry semantics in the canonical coordinator (loop + retry-on-failure + `RetryAttempt*` events).
- Real timeout semantics (deadline enforcement + `TimeoutScheduled`/`TimeoutTriggered` observability).
- Canonical parallel: ADR decision (composable step vs stage-terminal) then execution + `ParallelBranch*`.
- Per-step observability (AGENTS step-semantics mandate) for retry/timeout/parallel.
- Re-open the quarantined UATs green on the canonical path.

### Out of scope
- Plugin API (LFC-3), release (LFC-9). CatchError/unstable already closed (EM-5/6). DSL-surface debt
  rows (pwd/isUnix fake return, waitUntil, git/scmGit duplicate, shell dollar) belong to separate LFC-2
  follow-up items, not this milestone.

## Design gate
This milestone is design-gated. Design decisions (D1 retry, D2 timeout, D3 parallel ADR fork) are in
`design.md`; no implementation change lands before the fork is chosen and ADR-recorded.

## Success criteria
- [ ] `UatDsl003ParallelTest` green on the canonical path (parallel executes, `ParallelBranch*` emitted).
- [ ] Three `UatDsl001JenkinsFamiliarityTest` full-grammar methods green (parallel + retry + timeout
      events on the canonical path).
- [ ] Real retry: N attempts, stops on success, `RetryAttemptStarted/Finished` per attempt, fail-closed.
- [ ] Real timeout: deadline aborts the body as a typed TIMEOUT failure; `TimeoutScheduled` observed.
- [ ] Per-step observability mandate satisfied for retry/timeout/parallel.
- [ ] No regression in coordinator/EM/DSL suites (stage bookends, ERR-S, UatEvt001 preserved).
