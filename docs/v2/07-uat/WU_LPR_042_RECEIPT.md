# WU-LPR-042 Receipt — SqliteEventStore In-Place Perf Evolution

**Status:** DONE
**Date:** 2026-09-18
**Scope:** in-place evolution of `SqliteEventStore`. NO
`FastSqliteEventStore`. NO new store class. Single write path.

## What changed (in place)

- **Persistent connection + prepared statement:** one writer connection,
  insert prepared once at init (after schema creation).
- **Single writer:** one daemon=false writer thread drains a bounded
  `ArrayBlockingQueue` (capacity 10_000) of a closed `PendingWrite` ADT
  (`Event` / `FlushBarrier` / `Stop`). No nullable sentinels, no flag bag.
- **Batch transaction:** up to 512 inserts per COMMIT in one transaction;
  the SQLite COMMIT is the durable unit (WAL + synchronous=NORMAL:
  durable under process crash).
- **flush() barrier:** public; blocks until every enqueue-before-flush
  event is committed. Used by close() (idempotent) and by readers that
  need visibility.
- **Honest overload policy (documented in code):** queue full =>
  append() BLOCKS. Bounded + lossless; no silent drop; producers may
  couple to writer throughput beyond queue capacity.
- **append() semantics preserved:** sequence assignment unchanged
  (monotonic per run; LPR-041 durable seeding at construction).
- **eventsFor() unchanged** (fresh reader connections; WAL readers
  concurrent with the writer).

## TDD evidence

- RED: `flush` unresolved reference (contract missing) — compile RED.
- Second RED after implementation start: SQLITE_ERROR no such table
  (prepared statement created before schema; fixed by deferring
  prepareStatement into init).
- GREEN: `Lpr042EventWritePerfTest` 3/3 — 50k appends per_event=11-16µs
  (gate <50µs), committed data survives close, flush barrier works.

## Observed performance (fresh XML, canary verified)

| Metric | Before (fresh conn/insert) | After (single writer batch) |
|---|---|---|
| per_event append (50k warm) | 476-513µs | **11-16µs** (30-40x) |
| LPR-040 D1 100k events | 47.7s total | **1.4s total** |
| D4 slow drain consumer + appends | 520µs/event | **10µs/event** |

## Verification

- L4 `:pipeline-events:test`: 139/139 green (includes LPR-000, LPR-040
  harness, LPR-041, LPR-042).
- L3 consumer: `ReconciliationStatusOnlyTest` (pipeline-application) green.
- One harness defect found and fixed during integration: D3 flushed a
  store that the harness had already closed (barrier timeout on a dead
  writer). Fixed the harness ordering; this also proved the writer-death
  -> flush-timeout failure mode is loud, not silent.

## Known follow-ups (not this WU)

- D4-P4 runtime output-plane backpressure coupling (sink on pump thread)
  belongs to the console hot-path slice (042-FOLLOWUP in the observation
  gate WU-LPR-043 scope).
