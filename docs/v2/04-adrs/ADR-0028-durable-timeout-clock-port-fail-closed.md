# ADR-0028: Durable timeout + Clock port + FAIL-CLOSED model

- **Status:** Accepted for V2 design
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R3 Implementation:** M3-R3 (A-lite, durable process task/reattach model)

## Context

M3-R2 introduced `deadline_ms` column and a Clock port for deterministic timeout testing. Three implementation gaps were discovered post-apply:

1. **GAP-001** (M3-R2 over-003): `executeDurableStep` always returned `"success"` for Shell steps — `ShellResult.exitCode` was captured but ignored. Retry loops never continued for Shell steps because `stepOutcome` was always `"success"`.

2. **GAP-002** (M3-R2 over-003): `deadlineMs` was recomputed fresh on every `emitDurableStepEvents` call (`clock.now().toEpochMilli() + timeoutMillis`) instead of being read from the journaled `deadline_ms`. On resume after deadline expiry, the fresh deadline was always in the future — no DivergenceException was thrown.

3. **GAP-003** (M3-R2 over-003): `OperationJournal.get()` never retrieved `deadline_ms` from the database. `DurableOperation` sealed class had no `deadlineMs` field.

All three gaps stem from one root cause: the Clock seam was not consistently used throughout the durable execution path, and the deadline column was write-only.

## Decision

1. **Clock port injection**: Route all wall-clock access in `:pipeline-events` through the `Clock` port. Replace `System.currentTimeMillis()` calls in `SqliteOperationJournalImpl.append()` and `SqliteReplayCursorStoreImpl.advance()` with `clock.now().toEpochMilli()`. The `Clock` interface is in `:pipeline-domain` (F-ARCH-001 compliant — uses only `java.time.Instant`).

2. **Shell exit code honored**: In `executeDurableStep` (C-028), check `ShellResult.exitCode`: non-zero exit codes produce `"failure"` step outcome. This unblocks C-023 (retry survives restart) for Shell steps.

3. **Deadline read path**: Add `getDeadlineMs(opId, attempt)` to `OperationJournal` interface. Implement in `SqliteOperationJournalImpl` using a direct SQL query. The deadline check in `emitDurableStepEvents` reads from the journal on resume rather than recomputing.

4. **FAIL-CLOSED invariant** (C-021 + C-027): When resuming, if a `RUNNING` row exists with `deadline_ms` exceeded, throw `DivergenceException` immediately. Do not re-execute.

5. **`started_at` + `ended_at` columns**: Add to `operation_journal` schema (forward-only, idempotent migration via `ALTER TABLE`). `started_at = clock.now()` written by `beginOperation()`. `ended_at = clock.now()` written by `append()` when transitioning to terminal state.

## Alternatives Considered

1. **No Clock port, test clocks only** — rejected because production still needs real time; without a port there is no way to inject `SystemClock` in production and `MutableClock` in tests.

2. **Store deadline as Duration rather than Instant** — rejected because SQLite INTEGER epoch ms is simpler and consistent with existing `created_at`/`updated_at` convention.

3. **Fail-open on deadline expiry** — rejected per spec C-021: "MUST NOT re-execute."

## Consequences

- `SqliteOperationJournalImpl` and `SqliteReplayCursorStoreImpl` now require `Clock` as a constructor parameter.
- `OperationJournal` interface gains `getDeadlineMs()`, `getEndedAt()`, `getStartedAt()`, `beginOperation()` methods.
- Schema migration adds `started_at INTEGER` and `ended_at INTEGER` columns (nullable).
- Shell steps with non-zero exit codes now produce `"failure"` outcomes, enabling retry policy to work correctly.

## Evidence and Provenance

- M3-R2 over-003 discovered during `UatDurable004RetrySurvivesRestartTest` and `UatDurable005TimeoutPersistsAcrossRestartTest` development.
- GAP-001 fix confirmed by `UatDurable004RetrySurvivesRestartTest` regression passing.
- GAP-002 fix confirmed by `UatDurable005TimeoutPersistsAcrossRestartTest` case 2 passing.
- Clock port partial fix (coup-002) routes 3 `System.currentTimeMillis()` calls in `:pipeline-events`. The 23 `:pipeline-application` event-timestamp sites are deferred to M3-R4 "Clock-port cohesion."

## Revisit When

- The Clock port needs to support timezone or offset concepts (currently UTC only).
- A distributed clock (NTP-synced) is needed for multi-worker scenarios.
