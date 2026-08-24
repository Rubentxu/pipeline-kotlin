# ADR-0034: OpId branchIndex Extension

- **Status:** Accepted for M3-R4.2
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.2 Implementation:** T-02 (C2)

## Context

When parallel branches execute concurrently, each branch needs a unique operation identifier that distinguishes it from sibling branches running in the same pipeline step. The existing `OpId` format `^(.+)-s(\d+)-(\d+)$` (where groups are `op`, `seqIndex`, `runStep`) has no slot for branch identity.

Without branch-aware `OpId`, the `operation_journal` table cannot disambiguate which branch wrote a given log entry, making crash recovery and replay ambiguous when multiple parallel branches share the same step sequence index.

## Decision

Extend `OpId` with an optional 4th field `branchIndex`:

### New OpId format

```
^(.+)-s(\d+)-(\d+)(-b(\d+))?$
```

Groups:
- `op`: operation name (existing)
- `seqIndex`: step sequence index (existing)
- `runStep`: run-specific step counter (existing)
- `branchIndex`: optional branch index (NEW, nullable)

### String representation

When `branchIndex` is non-null, the `OpId.toString()` produces:
```
{op}-s{seqIndex}-{runStep}-b{branchIndex}
```

When `branchIndex` is null (sequential case), the string is unchanged:
```
{op}-s{seqIndex}-{runStep}
```

### Factory method

```kotlin
companion object {
    fun forBranch(op: String, seqIndex: Int, runStep: Int, branchIndex: Int): OpId =
        OpId(op, seqIndex, runStep, branchIndex)
}
```

### Journal schema implications

- `beginOperation(opId: OpId, ...)` persists the full `OpId.toString()` to `operation_journal.op_id`
- The `-b{N}` suffix is stored verbatim; existing queries that parse `op_id` without branch suffix remain valid
- No schema migration required (backward compatible string format)

## Alternatives Considered

1. **Separate `branchOpId` field in `operation_journal`** — rejected; introduces column proliferation and dual-index complexity.

2. **Encode branch in the `op` field** — rejected; breaks human readability and log greppability.

3. **Branch-scoped `seqIndex` offset** — rejected; complex to compute and fragile under branch add/remove.

## Consequences

- `OpId` with `branchIndex` uniquely identifies a branch's execution context
- Log correlation across parallel branches is unambiguous
- Crash recovery can identify which specific branch was running
- Existing sequential pipelines (no branch) are fully backward compatible

## Evidence and Provenance

- M3-R4.2 design decision from `design.md` §C2
- ADR-0033 defines `BranchSpec` which uses `OpId.forBranch(...)` during execution
