# ADR-LFC-007 — Single suspend execution spine

**Status:** proposed

## Context

Legacy walker, coordinator/dispatcher seams and local runBlocking boundaries allow behavior to diverge.

## Decision

All execution goes through `suspend RunCoordinator -> StepDispatcher -> StepHandler`. Blocking bridges exist only at process entry/test boundaries. Retry, parallel and resume reuse the same dispatcher.

## Consequences

Cancellation and structured concurrency become coherent; deleting the walker is mandatory for closure.
