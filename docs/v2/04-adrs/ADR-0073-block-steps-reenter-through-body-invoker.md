---
type: adr
id: ADR-0073
title: "Block Steps re-enter the engine through BodyInvoker / BranchInvoker"
status: proposed
date: 2026-09-08
deciders: "Rubentxu (product owner)"
supersedes: "ADR-0054 (block-step re-entry model only)"
superseded_by: null
related:
  - ADR-0070  # uniform Invoke → Registry → handler path
  - ADR-0054  # block-step nesting taxonomy (flattening retained; re-entry superseded here)
  - docs/v2/03-specifications/BLOCK_STEP_EXECUTION.md
  - openspec/changes/em11-canonical-m2r1-runtime  # E-EM-11 D1/D2 steered onto shared body machinery
---

# ADR-0073 — Block Steps re-enter the engine through BodyInvoker / BranchInvoker

> Reconciled from the reference package `ADR-LFC-022` (input only, not authoritative).

## Context

`dir`, `withEnv`, `withCredentials`, `retry`, `timeout`, `catchError`, `warnError`, `timestamps`
and `parallel` execute child programs. Creating one dispatch method per block
(`dispatchRetryBlock`, `dispatchTimeoutBlock`, ...) merely moves the concrete Step switch to another
layer and creates a new collection of architectural exceptions.

## Decision

A block Step never executes child handlers directly. The engine injects two capabilities; every
child re-enters the same `Invoke → Registry → capability admission → handler → journal/events` path.

```kotlin
interface BodyInvoker {
  suspend fun invoke(body: BodyRef, patch: ExecutionContextPatch = None): BodyOutcome
}
interface BranchInvoker {
  suspend fun invokeAll(branches: List<NamedBodyRef>, policy: JoinPolicy,
                        patch: ExecutionContextPatch = None): ParallelOutcome
}
```

Mapping: `dir`→workspace patch; `withEnv`→environment patch; `withCredentials`→credential lease
patch; `retry`→N invocations with AttemptId; `timeout`→deadline/cancellation scope; `catchError`→
interpret typed `BodyOutcome`; `parallel`→Named Bodies + `BranchInvoker`.

## Parallel

`parallel` is composable, not permanently stage-terminal. It is expressed as `StepBodies.Named`
dispatched through `BranchInvoker`, reusing the durable branch machinery (branch-indexed rows on
`OpId`), and may compose with serial siblings when the grammar allows.

## Consequences

- No new `dispatch*Block` method collection; control-flow Steps are routes into shared body
  machinery.
- E-EM-11's retry/timeout implementation is steered onto BodyInvoker rather than per-block
  dispatcher methods.
- The ADR-0054 flattening taxonomy may remain; its child re-entry model is superseded here.
