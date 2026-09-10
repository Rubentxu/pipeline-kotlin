# UAT-EVT — real examples, history, verification and live relay

Status: PROPOSED

## Test philosophy

A real example is not decorative documentation. It is the final executable specification of a supported
user-facing behavior:

```text
.pipeline.kts -> Kotlin host -> compiler -> canonical coordinator -> real effects -> EventLog -> verifier
```

All tests use the installDist binary unless marked HF0/HF1.

## Existing examples baseline

| Example | Expected execution contract |
|---|---|
| `01-hello.pipeline.kts` | SUCCESS |
| `02-multi-stage.pipeline.kts` | SUCCESS; declaration order visible |
| `03-shell.pipeline.kts` | SUCCESS; real OS process |
| `04-kotlin-control-flow.pipeline.kts` | SUCCESS; real scripting path |
| `05-failing-step.pipeline.kts` | EXPECTED FAILURE; typed script failure |
| `06-durable.pipeline.kts` | SUCCESS; durable/replay behavior |

The harness must treat expected non-zero as an expected outcome, not a harness crash.

## New example scenarios

### EX-EVT-07 — nested catchError

Proposed file: `07-catch-error.pipeline.kts` + `07-catch-error.events.yaml`.

Required assertions:
- inner and outer catch events exactly once each;
- failure observation order inner -> outer;
- final expected run/stage outcome preserved;
- step after both scopes executes when semantics permit;
- no stale catch frame affects a later unrelated failure;
- exit markers do not fabricate duplicate CatchErrorTriggered events.

### EX-EVT-08 — parallel

Proposed file: `08-parallel.pipeline.kts` + contract.

Required assertions:
- each branch Started exactly once and Finished exactly once on fresh run;
- Started(branch X) happens-before Finished(branch X);
- StageStarted happens-before branch lifecycle;
- required branch terminals happen-before StageFinished;
- no global A-before-B order assumed;
- durable replay: zero new branch lifecycle and zero new StepStarted for reused aggregate/children.

### EX-EVT-09 — retry fail→success

Proposed file: `09-retry.pipeline.kts` + deterministic counter fixture + contract.

Required assertions:
- attempt 1 FAILED, attempt 2 SUCCEEDED;
- retry events have exact ordinal identity;
- rerun with same durable facts produces no attempt 3;
- final outcome equivalent under replay.

### EX-EVT-10 — timeout

Proposed file: `10-timeout.pipeline.kts` + contract.

Required assertions:
- exactly one effective TimeoutScheduled when admitted;
- event precedes governed child execution;
- typed timeout terminal semantics match runtime contract;
- invalid timeout/admission produces no fabricated scheduled event/child;
- expected timeout is not reported as harness infrastructure failure.

## Event Harness protocol canaries

Run valid traces plus deliberate mutations:

- remove required start;
- duplicate terminal event;
- move parent finish before child terminal;
- corrupt branch/resource reference;
- insert fresh StepStarted in a replay-only trace.

Each mutation must be rejected with a minimal counterexample.

## EVT-2 local history UAT

1. execute real installDist example;
2. list event history by run;
3. restart CLI/process;
4. query same run again;
5. filter by event type and ResourceRef;
6. verify sequence/cursor continuity;
7. verify console stdout/stderr is not duplicated into DomainEvent payload history.

## EVT-4 live relay UAT

Use a real example long enough to observe intermediate state deterministically (barrier/process fixture,
not sleeps as correctness synchronization).

1. start detached subscriber/relay;
2. run pipeline;
3. assert subscriber sees StageStarted before RunFinished;
4. terminate subscriber deterministically;
5. assert pipeline continues and reaches expected outcome;
6. restart subscriber from last acknowledged cursor;
7. assert remaining events arrive and dedupe/semantic counts remain valid;
8. inject subscriber exception/OOM-like process termination and prove no pipeline cancellation propagation.

## Resource isolation benchmark gate

Measure baseline vs live relay enabled:

- pipeline wall-clock delta for representative examples;
- runner CPU and RSS;
- relay CPU/RSS;
- event append latency p50/p95/p99;
- relay lag;
- reconnect catch-up time.

No hard SLO number is frozen before measurement. Regression budget is accepted only after baseline data.

## Rule-16 / existing debt

A non-zero global check caused exclusively by known baseline debt must remain reported as NON-ZERO. EVT
closure reports a delta gate separately: new failures=0, baseline widened=0, exact known failing identities unchanged.
