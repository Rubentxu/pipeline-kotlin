# UAT-EVT — real examples, history, verification and live relay

Status: PROPOSED EVT UAT; **P4-EX baseline already CLOSED at d0ccf4b5**

## Test philosophy

A real example is not decorative documentation. It is the final executable specification of a supported
user-facing behavior:

```text
.pipeline.kts -> Kotlin host -> compiler -> canonical coordinator -> real effects -> EventLog -> verifier
```

All tests use the installDist binary unless marked HF0/HF1.

## Authoritative P4-EX baseline (already closed)

Do **not** create examples 07–10 in EVT: they are already on trunk and validated by the real CLI at `d0ccf4b5`. The starting oracle is:

| Example | Real-CLI baseline contract |
|---|---|
| `01-hello.pipeline.kts` | SUCCESS |
| `02-multi-stage.pipeline.kts` | SUCCESS; declaration order visible |
| `03-shell.pipeline.kts` | SUCCESS; real OS process |
| `04-kotlin-control-flow.pipeline.kts` | SUCCESS; real scripting path |
| `05-failing-step.pipeline.kts` | EXPECTED FAILURE; typed SCRIPT failure |
| `06-durable.pipeline.kts` | two runs with same `--db`; current durable policy semantics |
| `07-catch-error.pipeline.kts` | exactly 2 `CatchErrorTriggered`: inner FAILURE → outer UNSTABLE; echo afterwards |
| `08-parallel.pipeline.kts` | second run same `--db`: 0 new branch lifecycle + 0 `StepStarted` |
| `09-retry.pipeline.kts` | `RetryAttemptFinished` failed → succeeded; deterministic |
| `10-timeout.pipeline.kts` | `TimeoutScheduled`; timed-out shell; expected terminal FAILURE |

`examples/run.sh` already owns expected exit/outcome semantics and returns 0 for the complete 10-example gate. Expected non-zero examples are successful acceptance cases when their declared outcome matches.

### EVT-3 migration target

EVT-3 does not add these behaviors. It moves their verification from scenario-specific shell parsing toward reusable typed contracts:

```text
existing run.sh assertion
        |
        +--> characterize law
        |
        +--> EventConstraint ADT
        |
        +--> sidecar contract codec
        |
        +--> same captured trace
                 |
          old verdict == new verdict
```

For 07–10 the supplied `examples/contracts/*.events.yaml` files in this proposal are **candidate normalized representations of laws that are already proven**, not evidence that the `.pipeline.kts` files are missing.

Special baseline debt to preserve while migrating:

- flags after the script path are currently ignored by the CLI; use leading durable flags;
- durable replay output may contain prior events with original timestamps (`INC-021d`); until first-class cursor/run identity replaces it, parity tests must account for the existing scoping behavior;
- 10-timeout expects FAILURE, not UNSTABLE.

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
