# Design: E-EM-11 — canonical M2-R1 runtime parity (retry/timeout/parallel)

Anchored in fresh ground truth + code trace at HEAD `c085f8be` (2026-09-08).

## Evidence anchors
- `CanonicalDurableRunCoordinator.dispatchBody` (L650-797): for `core.retry`/`core.timeout` it runs the
  body children once via the generic loop. There is no retry count, no retry-on-failure, no deadline,
  no `RetryAttempt*`/`TimeoutScheduled`/`TimeoutTriggered`. `RetryOverlay`/`TimeoutOverlay`/
  `CancellationScope` exist in the domain `ContextOverlay` but are not pushed here.
- `dispatch()` (L427-441): BlockStepNode → `dispatchBody`; OpaqueStepNode → canonical decoder.
- `projectShellScope` maps `core.dir`/`core.timestamps`/`core.withEnv` to `BlockShellScope`; retry/
  timeout have no projection (→ `BlockShellScope.None`), confirming pass-through.
- `run()` (L275-276): non-`StageBody.Steps` stage body throws.
- M2-R1 events emitted ONLY by legacy `PipelineRun` (ParallelBranch*/RetryAttempt*/TimeoutScheduled).

## Design decision D1 — real retry semantics + projection (typed dispatch path)
Introduce a dedicated `dispatchRetryBlock` in the coordinator, routed from `dispatch` when
`pluginStepId == "core.retry"`. It:
1. decodes the retry payload (count; fail-closed on malformed → schema Failure),
2. emits `RetryAttemptStarted` before each attempt,
3. runs the body children (reusing the existing child-dispatch machinery),
4. on `StepOutcome.Success` emits `RetryAttemptFinished` and returns Success; on Failure/Unstable and
   `attempt < count` emits `RetryAttemptFinished(attempt, status=failed)` and retries; on final failure
   returns the failure (fail-closed, no silent swallow).
5. Constraint: emit only existing `RetryAttempt*` event types (no new `DomainEvent` subtypes).
Open (verify at apply): the exact `RetryAttempt*` payload/`attempt` field contract the legacy emitter
used, so the re-opened UatDsl001 assertions match.

## Design decision D2 — real timeout semantics + projection
Introduce a dedicated timeout path: on `pluginStepId == "core.timeout"`, decode time/unit, compute the
deadline, emit `TimeoutScheduled`, dispatch the body under a deadline, and on expiry abort the running
child as a typed `FailureKind.TIMEOUT` failure and emit `TimeoutTriggered` (existing types). This
interacts with the durable journal/replay: expiry must be a first-class recorded outcome, not a hang.
Open (verify at apply): how the coordinator's effect-replay/operation-boundary treats a timed-out child
row (replay of a TIMEOUT must not re-run the side effect) — reconcile with `OperationStatus.FAILED_TIMEOUT`
already present in `toOperationStatus`.

## Design decision D3 — parallel ADR fork (composable step vs stage-terminal)
The DSL models `parallel` as `StepSpec.Parallel` mixed into `stage.steps` (composable), and the
fixtures put `parallel{}` among siblings (`grammar-full` Deploy, `parallel.pipeline.kts`). The domain
`StageBody` only models whole-body `StageBody.Parallel` (declarative, matching the legacy substrate).
Fork, to be ADR-recorded before implementation:
- (A) **stage-terminal (declarative-faithful)**: keep `StageBody.Parallel` as whole stage body; wire the
  coordinator to execute it and emit `ParallelBranch*`. The `parallel`+sibling fixtures are invalid and
  must be reformulated (sibling echo moved into branches/post). Smaller IR change (no new StepNode kind),
  but diverges from the DSL's composable `StepSpec.Parallel` and requires fixture edits.
- (B) **composable step (scripted-faithful)**: introduce a parallel-capable canonical node / projection
  so `parallel{}` is a sibling step. Larger: needs concurrency for one step among siblings and a new IR
  shape, but honors the DSL and the existing fixtures as-is.
Recommendation: (A) first if the durable engine already runs whole-body branches elsewhere; (B) if the
honest-Jenkins-scripted surface is the target. This is the ADR decision — see proposal design gate.
Open: whether the closed `ParallelFrame`/`BranchReconciler` executor can be reused inside the
coordinator for either fork (spine is "closed" only in legacy `PipelineRun`; the coordinator never got
it), and how concurrency composes with the single journal/`OpId` replay model (branch-indexed rows —
`branchIndex` is already a field on `OpId`).

## Migration path
1. D1 retry alone. Verify: retry fixture/unit tests green; coordinator suites unchanged; no regression.
2. D2 timeout alone. Verify: timeout unit tests green (FAILED_TIMEOUT path), no hang (rule-7/10).
3. D3 parallel: choose fork (ADR), then wire + execute. Verify: `UatDsl003ParallelTest` green.
4. Re-open `UatDsl001JenkinsFamiliarityTest` full-grammar; run full DSL/EM/coordinator suites.
Each step keeps the already-green ERR-S/stage-bookend/UatEvt001 suites before moving on.

## Constraints
No new `DomainEvent` subtypes (emit existing M2-R1 types). No `ContextOverlay.Credentials` changes. No
IR secret leaks. Fail-closed: never silently convert retry/timeout/parallel into a single pass-through
or a no-op. Respect the exact payload/field contracts the legacy emitters + UatDsl assertions expect.
