# ADR-0037: OperationJournal.beginOperation Contract — Option A (Caller-Passes-Formatted)

- **Status:** Accepted for M3-R4.3
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.3 Implementation:** T-02 (C2)

## Context

The `OperationJournal.beginOperation` method is the entry point for recording that a durable operation has started. It receives an `opId` string and an optional `branchIndex`. Two encoding strategies exist for attaching branch information to the operation ID:

- **Option A (caller-passes-formatted)**: The caller is responsible for formatting `opId` to include the branch suffix (e.g., `"run-s0-1-b2"`). The `branchIndex` parameter is a nullable consistency-check hint.
- **Option B (validate-at-boundary)**: The caller passes the root `opId` without branch suffix, and `beginOperation` always formats it, appending `-b$branchIndex` when `branchIndex != null`.

The original design used a hybrid that caused a **double-suffix bug**: when a pre-formatted `opId` (already containing `-b$branchIndex`) was passed along with `branchIndex != null`, the implementation would append another `-b$branchIndex`, producing `"run-s0-1-b2-b2"`.

## Decision

We adopt **Option A: caller-passes-formatted**. The caller is responsible for formatting the `opId` string. The `branchIndex` parameter is used only as a consistency check when both `opId.branchIndex` and `branchIndex` are non-null.

### Contract Rules

Given an incoming `opId: OpId` (already formatted) and `branchIndex: Int?`:

| Condition | Behavior |
|-----------|----------|
| `opId.branchIndex != null && branchIndex != null && opId.branchIndex != branchIndex` | Throw `IllegalStateException` — caller inconsistency |
| `opId.branchIndex != null && branchIndex == null` | Use `opId` as-is (caller already formatted) |
| `opId.branchIndex == null && branchIndex != null` | Format `opId` with `branchIndex` (caller passed root) |
| Both `null` | Root opId — existing behavior preserved |

### Persisted Form

The `branchIndex` is persisted to the `operation_journal.op_id` column **without double-suffixing**: the formatted `opId` string is stored directly.

### Rationale

Option A was chosen because:

1. **Single source of truth**: `OpId.format()` is the canonical formatter; `beginOperation` does not re-encode.
2. **Explicit consistency checking**: When a caller passes both a pre-formatted `opId` and a `branchIndex`, any mismatch is detected and reported rather than silently ignored or double-encoded.
3. **Blast radius**: The change is localized to `beginOperation`; callers that already pass root opIds with `branchIndex == null` see no behavior change.
4. **Traceability**: The `OpId` data class (ADR-0031) provides typed `branchIndex` access, making the contract self-documenting.

## Alternatives Considered

1. **Option B (validate-at-boundary)**: `beginOperation` always formats the `opId` by appending `-b$branchIndex`. Rejected because it creates dual encoding paths: if the caller accidentally passes an already-formatted `opId`, no error is raised and the double-suffix bug reappears.

2. **Option C (caller-passes-root, branchIndex required)**: The caller always passes root `opId` and `branchIndex` is mandatory for branch operations. Rejected because it requires a larger caller-side refactor across all branch invocations.

3. **Silent auto-detection**: If `opId` already contains a `-b{N}` suffix, silently use it and ignore `branchIndex`. Rejected because it masks programmer errors where the caller intended a different branch index.

## Consequences

- The double-suffix bug (`"run-s0-1-b2-b2"`) is eliminated.
- Callers that pre-format `opId` are explicitly validated against the `branchIndex` hint.
- The `OpId` data class becomes the single source of truth for formatting.
- Existing callers that pass root opIds with `branchIndex == null` are unaffected.

## Evidence and Provenance

- M3-R4.3 design decision from `design.md` §C1
- ADR-0031 defines the `OpId` data class with `branchIndex` field
- `OpIdContractTest` (C-031) provides 16 passing test cases covering all 4 branches
