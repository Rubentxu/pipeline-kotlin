# WU-LPR-105 — Event Append Acknowledgement / Sequence Publication Receipt

Date: 2026-09-18 · Base: `12088c54` (LOCAL-CORE-V1-CERTIFICATION) · Status: **DONE**

## Objective

Envelope projection must never discover store-assigned write metadata by racing the
read model.

## Root cause (confirmed by code inspection + deterministic reproduction)

`EnvelopeProjectingEventSink.append` re-read the read model
(`inner.eventsFor(runId).lastOrNull { it.eventId == ... }?.sequence ?: event.sequence`)
immediately after `inner.append`. Since WU-LPR-042 the SQLite writer is async/batched,
so the SELECT could run BEFORE the COMMIT. When the row was not yet visible, the
`?: event.sequence` fallback published **sequence = 0** — observed intermittently as
`[0,0,0,0,5,6,0]` in `EventHistoryContractTest`. A write-side/read-side visibility
race, not simple flakiness (and distinct from the WU-LPR-041 restart seeding defect).

## Fix (minimal, local, law-abiding)

1. **`EventStore.appendAssigned(event): DomainEvent`** — explicit write-side
   acknowledgement. Default implementation: `append(event); return event` (legacy
   stores unchanged). Stores that assign sequences MUST override.
2. **`SqliteEventStore.appendAssigned`** override: returns the assigned event after
   enqueueing (single counter, single authority). `append` delegates to it.
3. **`InMemoryEventStore.appendAssigned`** override: same contract (parity).
4. **`EnvelopeProjectingEventSink.appendAssigned`**: publishes the projection from the
   ASSIGNED event returned by the store. The read-side fallback is deleted — the race
   is structurally impossible now. `append` delegates.

### Acknowledgement semantics (decision)

`appendAssigned` returns **ASSIGNED** (sequence decided by the authority), not
DURABLY_COMMITTED. Rationale: in-process projection must not gate on COMMIT
(no sync-commit-per-event; the 30-40x WU-LPR-042 batching improvement is preserved).
Durability observers keep the existing machinery: `flush()` barrier (COMMIT is the
durable unit) and cursor reads (read-after-commit).

### Laws honoured

- Store remains the sole sequence authority — no second counter anywhere.
- EnvelopeProjector does NOT assign sequence — it consumes the store's return value.
- No flush-per-event (one flush per test batch only, where the assertion target is
  the DURABLE read model — that is the documented contract, not a hot-path change).
- No sleep/retry polling (the old `eventsFor` re-read was exactly that race; gone).
- No synchronous COMMIT-per-event.
- No performance regression: append hot path is unchanged (same bounded queue, same
  batched single writer); the only addition is the return value.

### Test seam

`SqliteEventStore.writerDelayMillis` (`@Volatile var`, default 0 = production
behaviour untouched): artificial per-batch writer delay that widens the old race
window deterministically, converting the flaky failure into a reproducible RED.

## Mandatory pins (all fresh, this SHA)

| Pin | Result |
|---|---|
| RED first: race reproduced deterministically (writerDelay + read-side fallback) | YES — pins compiled RED, and with the old decorator the slow-writer scenario zeroed sequences (pre-fix failures observed in runs) |
| `appendAssigned returns store assigned event with nonzero sequence` | GREEN |
| `slow writer projection never publishes zero and stays monotonic` (1000 events, writerDelay=2ms) | GREEN — sequences exactly 1..1000, never 0 |
| `concurrent appends produce unique dense assigned sequences` (500 events, 16 threads) | GREEN — dense 1..500 |
| `appendAssigned parity between InMemory and SQLite` | GREEN |
| `EventHistoryContractTest` repeated 5x | **6 tests × 5 runs, 0 failures** (previously flaky at every base SHA) |
| `:pipeline-events` full module | BUILD SUCCESSFUL, 0 failures |
| `CompatibilityCorpusTest` (installed binary incl. durable path) | 23/23 |
| Durable E2E probe (`--db` SQLite, installed dist) | exit 0; sequences 1..20 strictly increasing, zero zeros |
| Zero-fabrication | XML canaries checked for every cited count |

Test-design note: the durable-read assertions in `EventHistoryContractTest` now call
`flush()` once per batch before reading the read model — the documented async-store
contract since WU-LPR-042, not a weakening (the assertion targets durable state; the
projection assertions target ASSIGNED state and need no flush).

## Counters

New domain surface: `EventStore.appendAssigned` (1 method), `writerDelayMillis` test
seam. Coordinator/dispatcher/compiler: untouched.
