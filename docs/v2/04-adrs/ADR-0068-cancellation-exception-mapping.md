---
type: adr
id: ADR-0068
title: "Cancellation exception mapping: canonical INTERRUPTED, verbatim rethrow, legacy FAILED_TIMEOUT adapter"
status: proposed
date: 2026-09-06
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0047
  - ADR-0065
  - docs/v2/03-specifications/FAILURE_INTERRUPTION_MODEL.md
  - docs/v2/03-specifications/RECOVERY_DURABILITY.md
---

# ADR-0068 — Cancellation exception mapping: canonical INTERRUPTED, verbatim rethrow, legacy FAILED_TIMEOUT adapter

## Status

Proposed. Resolves the carry-forward from the blocked `lfc4-000`
specification (OperationStatus.INTERRUPTED vs FAILED_TIMEOUT) and extends the
implemented `InterruptionKind` taxonomy
(`TIMEOUT, USER_ABORT, PARENT_CANCELLED, SUPERSEDED, SHUTDOWN`).

## Context

Kotlin cancellation rides `CancellationException` through normal control
flow. Today two different durable realities are conflated:

1. a cooperative cancellation (user abort, parent scope cancelled, shutdown);
2. a watchdog deadline expiry (`FAILED_TIMEOUT`, ADR-0047/ML-R2).

Recovery and reporting need to distinguish them; structured concurrency
needs cancellation to propagate, not to be swallowed as a step failure.

## Decision

1. **`coroutineScope` ownership**: the runtime owns the `coroutineScope` at
   the script/block-step boundary. User scripts cannot cancel it directly
   (denied by the ADR-0066 deny-list); runtime timeout/cancel policies do.
2. **`CancellationException` is re-thrown verbatim.** No engine component MAY
   catch `CancellationException` for control flow, wrap it into
   `StepOutcome.Failure`, or classify it as a script failure.
3. **Canonical terminal: `OperationStatus.INTERRUPTED`.** When a
   `CancellationException` reaches the durable walker boundary, the walker
   journals `OperationStatus.INTERRUPTED` with
   `FailureRecord(failureKind = SCRIPT_INTERRUPTED, …)` (or an
   `InterruptionRecord` with the matching `InterruptionKind`), preserving the
   cause chain. `INTERRUPTED` is distinct from `ABORTED` and from
   `FAILED_TIMEOUT`; the state machine gains the terminal
   `RUNNING → INTERRUPTED` and `RECONCILING → INTERRUPTED` edges.
4. **`PipelineInterruptedException` is a typed marker that MUST NOT extend
   `CancellationException`** — subclassing would make engine catch-sites
   double-handle the same event and re-enter cancellation machinery.
5. **`withTimeout` keeps journaling `FAILED_TIMEOUT`** per ADR-0028 for the
   watchdog deadline path, with the original `TimeoutCancellationException`
   chain preserved as cause. `FAILED_TIMEOUT` remains an **external
   reporting/legacy adapter state** until EM-5 lifts it; runtime control flow
   treats deadline expiry as `InterruptionKind.TIMEOUT`. No event is double-
   published under both keys.
6. **Replay**: an `INTERRUPTED` terminal replays as a typed interruption at
   the same logical call without relaunching the effect (SPIKE-016 N4/N5
   anchors).

## Consequences

- `OperationStatus` gains a value: persisted journals are additive-extended
  (ADR-0067 governs reader compatibility).
- Reporting adapters may coalesce INTERRUPTED and FAILED_TIMEOUT for UI
  purposes, but journal truth stays separate.
- Engines that previously caught cancellation to emit failure events must
  migrate to the boundary mapping (EM-2 `StepExecutionBoundary` already
  preserves engine invariants by letting unexpected exceptions propagate).
