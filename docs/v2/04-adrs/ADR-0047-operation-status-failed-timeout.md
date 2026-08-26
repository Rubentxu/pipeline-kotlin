# ADR-0047: OperationStatus — FAILED_TIMEOUT 7th Terminal State

- **Status:** accepted
- **Date:** 2026-08-25
- **Deciders:** Rubentxu (product owner), orchestrator
- **Authority:** binds at apply phase T1; required before T1.1 so enum addition has documented provenance
- **Related:** [[ADR-0046-local-ecosystem-first-reprioritization]] §D2 (kill ≠ LOST precedent), REQ-Durable-Shell-Timeout TMO-S-011, TMO-S-006, UAT-REC-002

## Context

ML-R1 (ADR-0046) established the durable `sh` pattern with 6 terminal states for `OperationStatus`: SUCCEEDED, FAILED, ABORTED, DIVERGENT, LOST.

ML-R2 introduces **real timeouts** (W5 fold): a watchdog thread kills the process tree when `deadlineMs` is exceeded. This creates a distinct terminal outcome:

- **FAILED**: subprocess exited with non-zero exit code
- **FAILED_TIMEOUT**: subprocess was **killed by deadline** (SIGKILL via `setsid session-level kill -9`)

The distinction matters for replay semantics:
- `FAILED`: the script **ran to completion** but returned non-zero — idempotent, safe to skip on resume
- `FAILED_TIMEOUT`: the script was **killed mid-execution** — the outcome is unknown (like LOST), but unlike LOST we **know** it was killed by deadline, not worker crash

## Decision

Add `FAILED_TIMEOUT` as the **7th terminal state** to `OperationStatus`:

```kotlin
enum class OperationStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    ABORTED,
    DIVERGENT,
    LOST,
    FAILED_TIMEOUT;  // NEW
}
```

- `terminalStates` expands to include `FAILED_TIMEOUT`
- `transition(RUNNING, FAILED_TIMEOUT)` is valid (same rule as other terminals)
- Journal TEXT column accepts arbitrary terminal string (no DDL change)
- Resume: fingerprint unchanged + terminal → no re-execute (TMO-S-007)

## Why enum vs TEXT-only status?

A TEXT-only status ("FAILED_TIMEOUT" stored as string) would work, but:

1. **Consumer exhaustiveness**: Kotlin `when` over enum requires all cases — consumers (StepReconcilerL1, PipelineRun) already `when` over status and would silently accept arbitrary strings
2. **LSP KL ≈ 0**: Adding a new enum value is a compile-time guarantee; string-only requires runtime validation
3. **Design intent clarity**: The journal records the **semantic distinction** (killed-by-deadline ≠ non-zero-exit); an enum makes this explicit in the type system

## Consequences

- **Positive**: TimedOut 5th classify branch in StepReconcilerL1 has a distinct terminal status; consumers are compile-time protected; journal fingerprint preserved
- **Negative**: One more enum in the state machine — mitigated by clear naming and documentation
- **Traceability**: TMO-S-011, TMO-S-006, TMO-S-007, DSE-S-024, DSE-S-025, U6LH-S-009

## Alternatives Considered

| Alternative | Rejected because |
|---|---|
| TEXT-only ("FAILED_TIMEOUT" string) | No compile-time exhaustiveness; consumers silently accept arbitrary strings; LSP KL > 0 |
| Reuse FAILED | Semantic distinction is real (killed vs non-zero exit); replay policy differs (LOST-like vs skip) |
| Separate `TimedOut` enum | Duplicates the need for a 7th terminal; adds complexity without benefit |

## Changelog

- 2026-08-25 | created | status=accepted | valid_from=2026-08-25 | valid_to=∞
