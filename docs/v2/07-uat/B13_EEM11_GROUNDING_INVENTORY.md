# B13 / E-EM-11 — Grounding inventory: retry / timeout / parallel

Date: 2026-09-09. Scope: canonical spine only
(`DSL → StepSpec → compiled IR → CanonicalDurableRunCoordinator`).
No legacy direct-exec path is restored; every new behavior re-enters the
coordinator (ADR-0073 direction), never interprets `StepSpec` at runtime.

## Inventory table

| Concern | Current representation | Current execution path | Durable support | Missing evidence |
| --- | --- | --- | --- | --- |
| **retry** (block) | `StepSpec.RetryBlock(count, conditions, steps)` lowered by `DslCompiledPipelineCompiler.blockStepNode` to `BlockStepNode(pluginStepId="core.retry", body=[children])`; payload `"{}"` — count is NOT projected into the payload today | Coordinator `dispatchBody`: treated as `BlockShellScope.None` → body children dispatched ONCE, first failure aborts (identical to a plain block). No attempt loop exists anywhere on the canonical path | `RetryPolicy` (domain.durable: maxAttempts/baseMs/jitterMs, exponential backoff) exists but has ZERO execution-path consumers (only DSL constructs it for step-level `retry` on the last step, which the canonical coordinator also ignores). Journal `attempt` field is hardcoded to `1` in coordinator dispatch; `StepReconcilerL1` already supports attempt-aware fingerprints (`OperationInput.attempt`) | Whole laws set: attempt-1 fail → recorded → attempt-2 executes; success → no extra attempt; restart/replay does not duplicate completed attempts (needs attempt-indexed `OpId`/journal rows and a journal-reconstructed attempt counter) |
| **timeout** (block) | `StepSpec.TimeoutBlock(time, unit, activity, steps)` → `BlockStepNode(pluginStepId="core.timeout", body=[children])`; payload `"{}"` — deadline NOT projected | Coordinator: `BlockShellScope.None` → body dispatched with inherited `shOptions.timeoutMs`. Separate mechanism: stage-level `options { timeout(seconds) }` DOES project into `ShOptions.timeoutMs` (`projectShellOptions`/`timeoutProjection`), consumed by `DurableShellExecutor` watchdog (flag-then-kill, FAILED_TIMEOUT, TMO-S-004/005/009 certified) | The Sh-level timeout/cancellation seam is CERTIFIED (watchdog, cookie-scan kill, FAILED_TIMEOUT status, grace poll). Missing: block-scope deadline projection from the `core.timeout` payload to child `shOptions` (with remaining-time budgeting for nested deadlines) | `timeout { sh(...) }` durable UAT: block deadline → child process cancellation → terminal timeout semantics; restart after timeout shows FAILED_TIMEOUT replay decision (not silent re-execution) |
| **parallel** | DSL `parallel { branch("a"){...} }` → `StepSpec.Parallel(branches)` → compiler: sole-step stage lowers to `StageBody.Parallel(List<StageNode>)` (domain IR, structural — allowed seam); branches are full `StageNode`s | Coordinator `run()` REJECTS `StageBody.Parallel`: `"Canonical durable coordinator supports only linear stage steps"` (IllegalArgumentException). `OpId.branchIndex` and `-b{N}` journal-key format exist and are tested, but nothing on the canonical path dispatches with `branchIndex != null`. Legacy `ParallelFrameExecutor`/`runBranch` deleted (LEG-1.3); `ParallelFrame`/`JoinPolicy` domain types survive with zero prod consumers | Branch-indexed journal keys pre-exist in `OpId` format contract; per-branch `StageNode` identity (stable tokens `stage/branch-{token}`) already produced by the compiler | Everything: concurrent branch dispatch through the SAME `dispatch` spine with deterministic branch indices, independent durable identities, declared join/failure policy (ground Jenkins-equivalent semantics before choosing — do not import deleted `JoinPolicy` semantics wholesale), sibling continuation on branch failure |

## Key grounding facts

1. **`core.retry` and `core.timeout` are already canonical-admitted** (`canonicalBodyStepIds`
   includes them; they compile to `BlockStepNode`s) but are **executed as empty shells**:
   `projectShellScope` returns `BlockShellScope.None` for both, so their distinguishing
   payload is literally `"{}"` and their semantics collapse to a plain sequence. This is
   the actual E-EM-11 gap — not re-adding an engine, but making two already-admitted block
   Steps actually project their contract.
2. **`BodyInvoker`/`BranchInvoker` do not exist in code** (ADR-0073 `status: proposed`).
   The coordinator's existing `dispatchBody`/`dispatch` pair IS the de-facto body
   machinery: `BlockStepNode → dispatchBody → per-child OpId → dispatch → journal`.
   Decision: either (a) introduce the typed `BodyInvoker` port per ADR-0073 as the
   re-entry seam, or (b) extend `dispatchBody` with scope-specific projections (retry
   loop, deadline scope, parallel fan-out) following the proven withCredentials
   special-scope pattern. Must not grow a `dispatchRetryBlock()` collection — the
   projection stays a sealed `BlockShellScope`-style ADT; the LOOP/SCOPE semantics live
   in one generic body-dispatch path.
3. **Retry durability**: `OperationInput.attempt` + `Fingerprint.compute(input, key,
   replayPolicy, attempt)` + `journal.get(operationId, attempt)` are attempt-aware
   ALREADY; the coordinator hardcodes `attempt = 1`. Deterministic attempt identity can
   be `OpId` + attempt counter reconstructed from journal rows (not memory). Backoff:
   `RetryPolicy.backoffDelay(attempt)` exists (uses `Math.random()` jitter — acceptable,
   jitter is not journaled and does not affect fingerprints).
4. **Timeout**: the certified Sh watchdog is per-invocation (`shOptions.timeoutMs`),
   blocking-poll based, NOT coroutine-structured. Block timeout = project payload
   deadline → child options (min(parent remaining, own)) is the compatible mechanism;
   no coroutine interruption needed. The subprocess kill path is the Sh seam, which is
   exactly what `timeout { sh(...) }` exercises.
5. **Parallel**: `StageBody.Parallel` is domain IR (structural, EP-F2.5 C1-adjacent).
   The coordinator's `run()` needs a parallel branch loop that dispatches each branch's
   steps under `OpId(branchIndex = i, ...)` — journal identity semantics ALREADY define
   `-b{N}` keys, so no journal schema change is a stop-condition. Join policy must be
   grounded in the current contract first (check `BLOCK_STEP_EXECUTION.md` / Jenkins
   baseline) before implementing failure semantics.
6. **Fixture routes**: executable `.pipeline.kts` fixtures follow the
   `UatDurableDefaultReuseCliTest` CLI pattern (`run --db --control-root script.kts`,
   canonical route, fail-closed exit 2 otherwise). Retry/timeout/parallel fixtures will
   be new UAT classes in `:pipeline-application` tests + corpus entries where valid.

## Order (confirmed)

1. **retry**: attempt loop in one generic body-dispatch path; attempt identity from
   journal; UAT `retry(2) { sh(fail-then-succeed) }` + restart/replay non-duplication.
2. **timeout**: `core.timeout` payload projection → child `shOptions.timeoutMs`;
   UAT `timeout { sh("sleep ...") }` kill semantics + FAILED_TIMEOUT replay.
3. **parallel**: coordinator parallel-stage loop with `branchIndex`; UAT
   `parallel { branch a; branch b }` both-execute + independent identities + failure
   semantics (policy grounded first).

## Stop-condition check (initial pass)

None triggered: block payloads can carry their contract without re-interpreting
StepSpec; re-entry exists (`dispatchBody`→`dispatch`); no second coordinator needed for
parallel (branchIndex keys exist); Sh cancellation seam is certified; branch journal
identity needs no schema change.

## Open questions to resolve before each slice's implementation commit

- retry: does the retry loop re-journal per attempt with distinct `attempt` numbers on
  the SAME operationId (attempt param), or derive child operationIds? (Journal contract
  `journal.get(operationId, attempt)` suggests same-id/attempt-param.)
- timeout: FAIL-fast semantics on block entry when deadline already expired.
- parallel: what does the current spec say about a failing branch — abort siblings,
  wait-all? Ground in `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md` +
  `BLOCK_STEP_EXECUTION.md` before implementing.
