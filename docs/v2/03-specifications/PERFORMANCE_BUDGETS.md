# Specification — Performance Budgets and Streaming Fitness

Status: PROPOSED

## 1. Rule

Performance is a release property. Functional green without streaming/performance gates cannot close LPR-O.

## 2. Baselines

LPR-0 captures immutable benchmark baselines before implementation changes:

- no observation renderer;
- quiet;
- events;
- normal success;
- full;
- console;
- in-memory and SQLite modes where applicable.

Measurements include wall time, CPU, peak RSS, allocation rate, event commits, output MB/s and observer lag.

## 3. Initial budgets (provisional until LPR-O0 baseline ratification)

These are release targets, not assumptions:

- `quiet/events/normal-success`: median wall-clock overhead target <= 1% vs equivalent execution with same durable recording; p95 <= 2% on stable benchmark hardware.
- `full/console`: child process completion target <= 3% degradation compared with same transcript persistence and no renderer.
- slow renderer/pipe: child process completion degradation target <= 3% even when renderer throughput is intentionally throttled; renderer may lag.
- memory: O(configured buffers), not O(total output); 1 GiB generated output must not lead to proportional heap growth.
- events: batch commits must be far lower than event count under normal load.
- terminal/final flush: bounded and measured; no unbounded wait for a consumer.

If environment variance makes percent gates unstable, CI uses repeated samples and an accepted confidence/variance rule. Absolute numbers must not be copied blindly between machines.

## 4. Required stress scenarios

1. subprocess emits >= 200 MiB stdout rapidly;
2. mixed stdout/stderr;
3. >= 1 GiB soak locally outside per-PR lane;
4. parallel branches each produce output;
5. consumer limited to e.g. 64 KiB/s;
6. consumer process killed/restarted;
7. JSONL piped to slow reader;
8. event burst with thousands of small events;
9. credential secret split across chunk boundary;
10. abrupt runner kill followed by history/replay inspection.

## 5. Hot-path constraints

- no JSON rendering for CLI on producer thread;
- no regex/text filters on producer thread;
- no terminal write on process pump;
- no unbounded collection of chunks/events;
- no DB connection open/close per event after LPR-O1;
- no autocommit per event after LPR-O1;
- remove per-chunk `runBlocking` bridge where coroutine structure permits;
- transcript writes are buffered and redacted streaming;
- observer notifications are hints and may be coalesced; data authority is durable storage.

## 6. Event persistence design target

Single writer + persistent connection + prepared statement + micro-batching. Batch thresholds are benchmark-selected, not API contract. Candidate starting experiments: 32/64/128 events and 1/2/5 ms windows.

Terminal/lifecycle barriers force a flush where semantic completeness matters.

## 7. Degradation policy

- console wakeup loss: recover from offset, no run degradation;
- event notification loss: recover from cursor;
- event durable ingress saturation: typed `DEGRADED/INCOMPLETE` observability state; never silently drop;
- transcript persistence failure: typed operational failure/degradation according to affected Step contract; never leak secrets or pretend success of observation.

## 8. Benchmark evidence

Every optimization that changes event/output storage records:

- baseline commit SHA;
- candidate SHA;
- exact argv;
- hardware/JVM/OS;
- sample count;
- median/p95;
- peak RSS;
- artifact/log hashes;
- correctness tests run alongside benchmark.
