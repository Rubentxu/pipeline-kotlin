# ADR-0037: OperationJournal.beginOperation Contract — Option A (Caller-Passes-Formatted)

- **Status:** Accepted for M3-R4.3; tightened for M3-R5
- **Date:** 2026-08-24 (M3-R4.3); 2026-08-25 (M3-R5 tightening)
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.3 Implementation:** T-02 (C2)
- **M3-R5 Implementation:** B2 (branchIndex API removal)

## Context

The `OperationJournal.beginOperation` method is the entry point for recording that a durable operation has started. It receives an `opId` string. Two encoding strategies exist for attaching branch information to the operation ID:

- **Option A (caller-passes-formatted)**: The caller is responsible for formatting `opId` to include the branch suffix (e.g., `"run-s0-1-b2"`) using `OpId.format()`. The journal uses `opId` as-is — no derivation, no suffix appending.
- **Option B (validate-at-boundary)**: The caller passes the root `opId` without branch suffix, and `beginOperation` always formats it, appending `-b$branchIndex` when `branchIndex != null`.

The original design used a hybrid that caused a **double-suffix bug**: when a pre-formatted `opId` (already containing `-b$branchIndex`) was passed along with `branchIndex != null`, the implementation would append another `-b$branchIndex`, producing `"run-s0-1-b2-b2"`.

**M3-R5 change**: The `branchIndex` parameter was completely removed from the `OperationJournal.beginOperation` interface and implementation. The journal now uses `opId` exactly as provided — no consistency check, no derivation. Callers are solely responsible for pre-formatting via `OpId.format()`. This eliminates the dual-encoding surface entirely (C-013/C-026).

## Decision

We adopt **Option A (strict)**: The caller is solely responsible for formatting the `opId` string. The journal uses `opId` exactly as provided.

### Contract Rules

The `beginOperation` signature is:

```kotlin
fun beginOperation(
    opId: String,
    attempt: Int,
    fingerprint: String,
    inputJson: String,
    deadlineMs: Long? = null,
)
```

The `opId` parameter MUST be pre-formatted by the caller using `OpId.format()`. The journal uses `opId` as-is — no validation, no suffix appending.

| Condition | Behavior |
|-----------|----------|
| `opId` formatted with branch suffix | Used as-is; journal stores exactly what caller provided |
| `opId` root (no branch) | Used as-is; root operation |

### Persisted Form

The `opId` is persisted to `operation_journal.op_id` exactly as provided by the caller. No transformation occurs.

### Rationale

Strict Option A was chosen because:

1. **Single source of truth**: `OpId.format()` is the canonical formatter; `beginOperation` does not encode or decode.
2. **Eliminated dual-encoding surface**: Removing `branchIndex` entirely closes C-013/C-026 permanently.
3. **Minimal interface**: The interface has no knowledge of branch semantics; it merely records what the caller tells it.
4. **Testability**: Fake implementations only need to store the string; no branch-index parsing required.

## Alternatives Considered

1. **Option A with consistency check (M3-R4.3)**: Retained `branchIndex` parameter as a consistency-check hint. Rejected in M3-R5 because it created a dual-encoding surface where the journal could detect (but not correct) caller errors. The check was advisory, not corrective.

2. **Option B (validate-at-boundary)**: `beginOperation` always formats the `opId` by appending `-b$branchIndex`. Rejected because it creates dual encoding paths: if the caller accidentally passes an already-formatted `opId`, no error is raised and the double-suffix bug reappears.

## Consequences

- The double-suffix bug is permanently eliminated (no `branchIndex` parameter to cause confusion).
- Callers that pre-format `opId` using `OpId.format()` work correctly; callers that don't have no runtime detection (but the `OpIdContractTest` catches this at test time).
- The `OpId` data class becomes the sole source of truth for formatting.
- Fake implementations in tests no longer need to implement a `branchIndex` parameter.

## Evidence and Provenance

- M3-R4.3 design decision from `design.md` §C1
- M3-R5 implementation: `SqliteOperationJournalImpl.beginOperation` simplified (branchIndex param removed, 30+ lines of derivation code deleted)
- `OpIdContractTest` (C-031) provides 16 passing test cases covering all `OpId` formatting branches
