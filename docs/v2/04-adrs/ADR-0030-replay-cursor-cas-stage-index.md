# ADR-0030: ReplayCursor CAS using stage_index

- **Status:** Accepted for M3-R4.1
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.1 Implementation:** T-08 (E4-12)

## Context

The replay cursor store uses an UPSERT pattern to advance the cursor position after each durable operation. The original WHERE clause compared timestamps (`saved_at`) to determine whether the new cursor position should replace the existing one. This caused a race condition when two advances occurred within the same millisecond — the second advance would be rejected because the timestamps were equal, leading to cursor stagnation and potential replay of already-completed steps.

The bug manifested as:
- 60% flake rate in same-millisecond overwrite scenarios
- Cursor not advancing when rapid stage transitions occurred
- Pre-existing issue since M3-R1 (DEBT-2026-08-24-REPLAY-CURSOR-RACE)

## Decision

Replace the timestamp-based WHERE clause with a stage_index-based comparison:

**Before:**
```sql
WHERE excluded.saved_at > replay_cursor.saved_at
```

**After:**
```sql
WHERE excluded.stage_index >= replay_cursor.stage_index
```

### Semantics

- Advancing to a **later** stage index always wins (forward progress is guaranteed)
- Advancing to the **same** stage index updates the `last_op_id` (idempotent within stage)
- Advancing to an **earlier** stage index is blocked (regression protection)

This ensures:
1. Forward progress is always recorded
2. Same-stage advances are idempotent
3. No cursor regression to earlier stages

## Alternatives Considered

1. **Compare both stage_index AND timestamp** — rejected as unnecessary complexity; stage_index alone provides sufficient ordering for replay purposes.

2. **Use `stage_index >` (strict)** — rejected because same-stage advances (e.g., moving from `op-5` to `op-6` within stage 2) would be blocked. The `>=` operator correctly allows within-stage advances.

3. **UUID-based tiebreaker** — rejected; adds no value since the stage_index ordering is sufficient.

## Consequences

- Replay cursor advances are now deterministic regardless of clock resolution
- Same-stage advances are properly recorded
- Earlier-stage regression attempts are silently ignored (idempotent safety)

## Evidence and Provenance

- E4-12 criterion from ROADMAP.md §E4-12
- DEBT-2026-08-24-REPLAY-CURSOR-RACE (CRITICAL)
- SqliteReplayCursorStoreImpl.advance() SQL updated
- ReplayCursorStoreContractTest validates idempotent advance behavior
