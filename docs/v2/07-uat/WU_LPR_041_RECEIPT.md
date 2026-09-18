# WU-LPR-041 Receipt — SQLite Durable Sequence Repair

**Status:** DONE
**Date:** 2026-09-18
**Scope:** in-place evolution of `SqliteEventStore`. No
`EventSequenceAssigner` extraction. No new store class.

## Property repaired

Per-run monotonic sequence MUST survive store instance reopen. Durable
truth comes from SQLite, not from per-instance memory.

**Pre-fix (LPR-040 D3 evidence):** a fresh `SqliteEventStore` instance
on an existing DB restarted sequences at 1 for every run already in the
events table → duplicate sequences across the reopen boundary
(maxSeq 500 → 500, 500 dups for a 500+500 append sequence).

**Fix:** `SqliteEventStore` init now seeds `sequenceCounters` from
`SELECT run_id, MAX(sequence) FROM events GROUP BY run_id` once at
construction (new private `seedSequenceCounters()`). Appends advance the
counters monotonically as before. `InMemoryEventStore` untouched
(keeps its local counter per WU directive).

## TDD evidence

- RED: `Lpr041DurableSequenceRepairTest.sequence survives store
  instance reopen` — IllegalStateException "expected maxSeq=51, got 50"
  (line 61), fresh run.
- GREEN: same test passes; concurrent cross-thread gapless test passes
  (400+400 appends from 2 threads → sequences 1..800, no dups, no gaps).

## Verification

- L2/L4: full `:pipeline-events:test` — 136 tests, failures=0 errors=0.
- L3 consumer: `ReconciliationStatusOnlyTest` (pipeline-application) — green.
- Re-run of LPR-040 harness after fix:
  - D3: maxBefore=500 **maxAfter=1000** count=1000 **dups=0** (was 500/500 dups)
  - D1: 100k events, per_event=513µs, dups=0 (no perf regression from seeding; seeding is one GROUP BY at construction)

## Contract notes

- Concurrent append correctness (two instances sharing one DB file) is
  addressed at the durability/perf layer in WU-LPR-042 (persistent
  connection + single writer). This WU repairs the single-instance
  reopen property, which is the trunk-directive target.
- Cursor compatibility: `eventsFor` and replay cursor paths unchanged.

## Next

WU-LPR-042: SqliteEventStore in-place perf evolution (persistent
connection + prepared statement + batch transaction + flush barrier),
targeting per_event 476µs → <50µs, then console hot path (042) and
observation gate (043).
