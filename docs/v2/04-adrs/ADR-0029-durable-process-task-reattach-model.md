# ADR-0029: Durable process task / reattach model

- **Status:** Accepted for V2 design
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R3 Implementation:** M3-R3 (A-lite, E4-11)

## Context

M3 exit criterion (ROADMAP.md §E4-11): kill worker DURING `sh`, recover WITHOUT replaying the side effect. The prior M3-R1/M3-R2 model had no mechanism to distinguish "subprocess completed but journal write was lost" from "subprocess was killed mid-execution."

The challenge: when a worker process is killed by SIGTERM (or crash), the subprocess may or may not have completed. We need to recover without replay only when the subprocess actually completed and we can trust its output.

## Decision

**Journal-first reconciliation** with a two-phase journal write:

### Phase 1: `beginOperation()`
Before executing any side-effecting step, write a `RUNNING` row to the journal:
```sql
INSERT INTO operation_journal (op_id, fingerprint, status, kind, attempt, input, started_at, created_at, updated_at, deadline_ms)
VALUES (?, ?, 'RUNNING', 'RERUN', ?, ?, clock.now(), clock.now(), clock.now(), ?)
ON CONFLICT(op_id, attempt) DO NOTHING
```
`started_at = clock.now()` records when the step began. `deadline_ms` records the absolute deadline.

### Phase 2: `append()` UPSERT
After the subprocess completes (success or failure), transition the row to terminal:
```sql
INSERT INTO operation_journal (..., status, output, ended_at, updated_at, ...)
VALUES (..., 'SUCCEEDED', output_json, clock.now(), clock.now(), ...)
ON CONFLICT(op_id, attempt) DO UPDATE SET
    status = excluded.status,
    output = excluded.output,
    updated_at = excluded.updated_at,
    ended_at = excluded.ended_at
```
`ended_at = clock.now()` records when the step finished. **Not set** for `RUNNING` rows.

### Reconciliation pass (on resume)
At the start of `walkPipelineSpecDurable`, before any stage execution:
1. Query all `RUNNING` rows for the `runId`.
2. For each `RUNNING` row:
   - **Deadline exceeded** (C-021): `nowMs > deadline_ms` → `DivergenceException` (fail-closed).
   - **`ended_at IS NULL`**: subprocess was killed between Phase 1 and Phase 2 → `DivergenceException` (fail-closed).
   - **Fingerprint mismatch**: current fingerprint ≠ journaled fingerprint → `DivergenceException` (fail-closed).
   - **Fingerprint match + `ended_at NOT NULL`**: subprocess completed → mark `SUCCEEDED` with cached output via append UPSERT. **No re-execution.**

### `started_at` and `ended_at` columns
Both nullable `INTEGER` (epoch ms). Added via forward-only idempotent `ALTER TABLE` migration:
```sql
ALTER TABLE operation_journal ADD COLUMN started_at INTEGER
ALTER TABLE operation_journal ADD COLUMN ended_at INTEGER
```
Safe on pre-existing databases. Old rows have `NULL` for both columns.

## Alternatives Considered

1. **ProcessAttacher pid-reattach** — rejected because the subprocess dies with the worker; there is no pid to reattach to. The process tree is gone.

2. **Side table `operation_running`** — rejected because it introduces an extra JOIN cost, split transaction semantics, and breaks WAL locality (RUNNING state would not be in the main `operation_journal` table).

3. **Always trust cached output on fingerprint match** — rejected because it silently lies about completion when the subprocess was killed mid-execution. Fail-closed is the safer default.

4. **Opt-in idempotency key for reconciliation** — deferred to M4+ (multi-worker concern).

## Consequences

- `OperationJournal` interface gains `beginOperation(opId, attempt, fingerprint, inputJson, deadlineMs)` method.
- `append()` semantics change from "throw on duplicate" to "UPSERT". Existing tests that relied on `IllegalStateException` for duplicate append were updated.
- Reconciliation pass adds latency on resume (one extra query per `RUNNING` row), but is bounded by the number of concurrent steps.
- `started_at` enables observability (time spent in RUNNING state).
- `ended_at` enables fail-closed detection (NULL = killed mid-write).

## Evidence and Provenance

- E4-11 criterion from ROADMAP.md §E4-11.
- Journal-first reconciliation (Option A from exploration) chosen over ProcessAttacher (Option B) and polling (Option C).
- UAT-DURABLE-006 validates the kill-during-in-progress recovery path.
- ADR-0028 documents the Clock port + deadline failure path.

## Revisit When

- Multi-worker scenarios require coordination to avoid race conditions on the same `op_id, attempt` key.
- A `--force-replay` flag is needed to override fail-closed for debugging.
