---
type: adr
id: ADR-0067
title: "Persisted schema versioning: monotonic, forward-only, fail-closed readers"
status: proposed
date: 2026-09-06
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0065
  - docs/v2/03-specifications/DURABLE_KOTLIN_EXECUTION.md
  - docs/v2/03-specifications/RECOVERY_DURABILITY.md
---

# ADR-0067 — Persisted schema versioning: monotonic, forward-only, fail-closed readers

## Status

Proposed. Partially implemented in the incorporated EM tree; this ADR
documents the implemented subset and the remaining gap.

## Context

The execution-model work introduced durable persisted records
(`FailureRecord`, `InterruptionRecord`, `DurableTaskSnapshot`,
`DurableTaskTerminal`) and SPIKE-016 proved a versioned wire codec
(`writeInt(1)` header). EM-1 through EM-8 will evolve these records and the
SQLite journal. Without a versioning contract, a newer writer can silently
corrupt an older reader and vice versa — the exact failure class that made
INC-039 dangerous.

## Implemented subset (evidence, 2026-09-06)

- `FailureRecord.schemaVersion: Int = SCHEMA_VERSION (1)` with
  `init { require(schemaVersion == SCHEMA_VERSION) }` — fail-closed on unknown
  versions (`v2/pipeline-domain/.../durable/DurableTaskTerminal.kt`).
- SPIKE-016 test codec writes a leading `version=1` header and
  `require(i.readInt()==1)` on read; negative scenario N3 proves a v2 stream
  is refused by a v1 reader.
- `InterruptionRecord`, `DurableTaskSnapshot`, `DurableTaskTerminal` are
  `@Serializable` data contracts without version fields yet — gap G1.

## Decision

1. **Every persisted record carries `schemaVersion: Int` (default 1)**:
   journal operation rows, `FailureRecord`, `InterruptionRecord`,
   `DurableTaskSnapshot`/`Terminal` envelopes, replay cursor, and any JSON
   envelope crossing process boundaries.
2. **SQLite**: `ALTER TABLE … ADD COLUMN schema_version INTEGER NOT NULL
   DEFAULT 1` per record-kind table, indexed for fail-closed lookups.
   Migration is additive; no column reuse, no in-place semantic flips.
3. **Monotonic, per-record-kind, forward-only, idempotent migrations.** A
   migration maps version N → N+1 only; re-running is a no-op; downgrades are
   never executed automatically.
4. **Readers declare a maximum supported version and fail closed**: a row
   whose `schemaVersion` exceeds the reader's declared maximum MUST throw
   `IncompatibleJournalSchema` (new typed error, `pipeline-domain`) — no
   synthetic defaults, no best-effort reinterpretation. SPIKE-016 N3 is the
   behavioral anchor for the wire codec; the SQLite reader adds the same
   contract (gap G2).
5. **Writer version bump discipline**: bumping a record kind's version
   requires (a) a migration, (b) a compatibility test proving old-reader
   refusal, and (c) a TRACEABILITY row update.

## Consequences

- Old journals remain readable by new runtimes (forward compatibility of
  readers); new journals on old builds fail loudly instead of corrupting.
- `IncompatibleJournalSchema` is a programmer/operator error surface, not a
  domain failure — it must surface at load/reconcile boundaries, never be
  classified as a step failure.
