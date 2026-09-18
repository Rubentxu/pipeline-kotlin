# WU-LPR-040 Receipt — Observation/Performance Harness

**Status:** DONE (harness only; no production change)
**Commit:** this commit
**Date:** 2026-09-18
**Scope law:** harness-only. No production code touched. Gates land in
WU-LPR-043 after WU-LPR-041/042.

## What was built

Two characterization harnesses extending the LPR-000 baseline with the
observation-gate dimensions:

1. `v2/pipeline-events/src/test/kotlin/.../Lpr040ObservationHarnessTest.kt`
   (event plane, D1..D6)
2. `v2/pipeline-step-sdk/runtime/src/test/kotlin/.../Lpr040OutputObservationHarnessTest.kt`
   (process-output plane, P1..P6)

## Event plane — OBSERVED evidence (fresh run, XML canary verified)

| Dim | Scenario | Observed |
|---|---|---|
| D1 | 100k events, single run | total=47675ms, per_event=476µs; count=100000 maxSeq=100000 dups=0 |
| D2 | 8 parallel runs × 500 | all runs count=500 maxSeq=500 dups=0 |
| D3 | reopen mid-load, same runId | **KNOWN BUG: maxSeq 500 → 500 after reopen; count=1000 dups=500. New store instance restarts sequence at 1 (WU-LPR-041 target)** |
| D4 | slow drain consumer (25ms poll loop) during 2k appends | per_event=520µs (vs 476µs clean); appends NOT throttled by reader |
| D5 | close→reopen, fresh store, same process | count=1000 maxSeq=1000 dups=0 (counter survives close in-process; fails on NEW process/instance — see D3) |
| D6 | payload scaling 64B×5000 vs 64KiB×500 | 2327ms vs 234ms (SQLite handles large payloads fine; per-event connection cost dominates) |

D3+D5 together pin the bug precisely: the in-memory
`sequenceCounters` map is per-instance state, not durable truth.
This is exactly the WU-LPR-041 property (per-run sequence MUST be
read from SQLite at store construction).

## Process-output plane — OBSERVED evidence

| Dim | Scenario | Observed |
|---|---|---|
| P1 | 100 MiB stdout | ok, ms=141, 12800 chunks × 8 KiB |
| P2 | mixed 2 MiB stdout + 2 MiB stderr, 50k lines each | ok, ms=814, 98819 chunks; stream identity preserved (no cross-contamination) |
| P3 | 8 parallel tasks × 10 MiB | all ok, ~285-298ms each |
| P4 | slow sink (20µs busy-spin per chunk) | task completes (ms=10 for 1 MiB = 128 chunks). OBSERVATION: sink runs on the producer pump thread via runBlocking → a slow sink DOES delay drain. Backpressure coupling exists (WU-LPR-042 target) |
| P5 | secret-split probe | OBSERVATION: no streaming secret redaction on the output sink today (WU-LPR-042/043 target per gate: "streaming secret redaction before durable transcript") |
| P6 | chunk metrics, 5 MiB fixed | 640 chunks, all exactly 8192 bytes (CHUNK_SIZE_BYTES) |

## Exit criteria informed (for WU-LPR-043 gate)

- SQLite sequence survives reopen: **FAILS today** (D3) → 041
- Event commits << event count: not yet measured at commit level; per_event=476µs suggests per-event commit (fresh connection per append) → 042
- Persistent connection: absent today (fresh per op) → 042
- Memory O(buffers): PASSES (8 KiB chunks, 100 MiB run ok)
- Completion decoupled from renderer: **PARTIAL** — completes, but slow sink delays drain (P4) → 042
- Slow consumer isolation: event plane PASSES (D4); output plane PARTIAL (P4)
- Streaming secret redaction: ABSENT (P5) → 042/043
- No event/console large-payload duplication: pending 043 measurement

## Verification

- L0: `:pipeline-events:compileTestKotlin` + `:pipeline-step-sdk:runtime:compileTestKotlin` — BUILD SUCCESSFUL
- L1: `:pipeline-events:test --tests '...Lpr040ObservationHarnessTest'` — failures=0 errors=0, XML fresh (canary deleted before run, regenerated)
- L1: `:pipeline-step-sdk:runtime:test --tests '...Lpr040OutputObservationHarnessTest'` — failures=0 errors=0, XML fresh (timestamps verified)

## Next

WU-LPR-041: fix SqliteEventStore durable sequence (read per-run MAX(sequence)
from SQLite at construction; single-writer + durable truth from the DB).
