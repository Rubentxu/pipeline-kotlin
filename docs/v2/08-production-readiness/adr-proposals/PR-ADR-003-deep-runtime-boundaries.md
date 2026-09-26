# PR-ADR-003 — Deep Runtime Boundaries for Durable Execution

**Status:** PROPOSED

## Context

`CanonicalDurableRunCoordinator` remains approximately 1857 lines and 28 functions despite useful RP-031 extractions. It owns too many policy and lifecycle concerns.

## Decision

Converge on four principal deep runtime boundaries:

```kotlin
interface RunLifecycleEngine
interface BodyExecutionEngine
interface InvocationEngine
interface RecoveryEngine
```

Do not create one interface per helper operation.

Internally use typed ADTs and pure decision logic where possible.

## Responsibility split

### RunLifecycleEngine
- run/stage lifecycle;
- top-level outcome;
- lifecycle events.

### BodyExecutionEngine
- body traversal;
- body policies;
- scoped continuation.

### InvocationEngine
- Step preparation/invocation boundary;
- typed capabilities.

### RecoveryEngine
- replay;
- reconciliation;
- divergence;
- running/lost recovery.

## Compatibility

Legacy compatibility adapters are allowed only at the application composition root. They must not remain distributed across the coordinator constructor.

## Success criteria

- coordinator becomes composition-focused;
- no concrete Step branching;
- observable journal/events/replay unchanged;
- constructor dependency count materially reduced;
- target source size <600 LOC unless a stronger complexity metric proves equivalent depth.
