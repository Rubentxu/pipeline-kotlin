# Design: RETRY-D — durable reconciliation for canonical retry blocks

## Status and decision boundary

**PROPOSED, not implemented.** This is a spine-level recovery design for `E-EM-11 T1`.
It supersedes only the retry `dispatchRetryBlock` direction in the older E-EM-11
design. It does not reopen timeout, parallel, events, or grammar-full.

Before production code, record ADR-0075 as **proposed** and add the matching recovery
invariant to `RECOVERY_DURABILITY.md`. This document is the implementation contract
for the bounded slice, not evidence that RETRY-D is closed.

## Grounded current path

At HEAD `e4cca233`:

```text
BlockStepNode(core.retry)
  → CanonicalDurableRunCoordinator.dispatchBody(..., parentBodyPath)
  → attempt segment BlockSegment(attempt, "retry-attempt")
  → child OpId(parentBodyPath + attempt segment + child segment)
  → canonical dispatch(child, ..., childOpId.bodyPath)
  → child journal rows
```

The child identity is deterministic, but `dispatchBody` has no retry-control journal
row and initializes `attempt = 1` on every coordinator invocation. This is why child
completion alone does not prevent a duplicate retry body effect.

`OpId.format()` already carries the complete `bodyPath`; a parallel branch is itself
represented in that path. RETRY-D therefore must never derive a retry identity from
only `(runId, stageIndex, stepIndex)`.

## Durable authority

The authority is the `OperationJournal`, never a retry event or an in-memory counter.
The design uses two classes of durable fact:

1. **Retry control attempt**: exactly one journal operation for each logical retry
   attempt, addressed by the retry control `OpId` plus journal attempt ordinal.
2. **Child effect**: the existing canonical child operation at its deterministic
   attempt-body `OpId` and child ordinal path.

The retry control `OpId` is exactly:

```text
OpId(runId, stageIndex, stepIndex, inherited parentBodyPath).format()
```

The retry attempt ordinal is the existing journal `attempt` key. It is derived from
that retry's logical identity plus the one-based ordinal. It must not use a UUID,
clock, journal length, or an in-memory counter as durable authority.

### Control input and fingerprint

Each control attempt must record a total, canonical input projection containing:

- `core.retry` key and schema-versioned encoded retry payload;
- an order-preserving canonical projection/digest of the compiled child `StepNode`s;
- the logical run identity and attempt ordinal.

The projection must be a dedicated total encoder over the closed canonical node ADT,
not raw `toString()` or a `StepSpec` encoding. Its fingerprint is compared before any
child dispatch. This gives retry its own fail-closed divergence gate even though a
changed CLI script normally maps to a new `RunId`.

`MemoizedOperation` is the candidate existing durable record type for control rows:
its identity plus attempt field fits the journal key and its completion is reusable.
The implementation must introduce a small typed `RetryReconciliationState` algebra,
rather than treating replay policy or nullable output as retry state.

## Reconciliation algebra

The pure reconstruction result is conceptually:

```text
NotStarted(nextAttempt)
AttemptRunning(attempt)
AttemptFailed(attempt)
Succeeded(attempt)
Exhausted(attempt, failure)
Diverged(operationId)
```

No value combines a `completed` boolean, nullable failure, and mutable current count.
The coordinator derives it before it schedules a child effect:

1. Read control facts in ascending attempt order.
2. A fingerprint mismatch is `Diverged`: return the canonical infrastructure
   divergence failure, with zero child dispatches.
3. A terminal control success is `Succeeded`: return success, with zero children.
4. A terminal control failure is `AttemptFailed` when another ordinal remains, or
   `Exhausted` at `maxAttempts`.
5. For a missing or RUNNING control fact, reconcile the exact child `OpId`s for that
   attempt in body order. Do not search by string prefixes or infer child count.
   - all terminal child successes: persist control success and return `Succeeded`;
   - terminal child failure: persist control failure and choose the next legal
     ordinal, or `Exhausted` at the maximum;
   - a missing child: it is the next child eligible for canonical `dispatch()`;
   - a RUNNING child: hand it to its existing canonical recovery path. Retry does
     not execute a child handler itself.
6. Only an attempt with no recoverable durable fact is begun before dispatching its
   first eligible child. On a normal outcome, append the terminal control record
   immediately after the child-derived attempt outcome.

A crash after a child operation becomes terminal but before the control record is
terminal is Window C. Step 5 reconstructs that terminal control success/failure from
the exact child facts and persists it. It never repeats a terminal child.

A control RUNNING record is not automatically an infrastructure failure. Its child
facts are consulted first. It becomes fail-closed only when those facts cannot
reconstruct or safely recover the in-progress child under the existing child recovery
policy. This distinction is required for Window C.

## Execution direction

RETRY-D does not add `dispatchRetryBlock`, `RetryShExecutor`, a second coordinator,
or a `when(childStepKey)`. The retry-control transition selects the next canonical
child node only. All eligible children continue through:

```text
canonical child StepNode → dispatch() → registry/capability admission →
CommonExecutionBoundary → journal/events
```

The current `dispatchBody` is the temporary common body machinery. ADR-0073's
`BodyInvoker` is proposed and must not be invented solely for this repair.

## Window C test seam

Production receives **no fault port**. In the test source set, wrap the existing
`OperationJournal` with a deterministic decorator:

```text
append(child terminal success)
  → delegate.append(...) successfully
  → throw InjectedCrash exactly once
```

The test configures the exact canonical child `OpId` and status, so it cannot trigger
on arbitrary rows. It then builds a fresh coordinator with the same delegated journal
and runs the same compiled pipeline. The assertions are:

```text
child success persisted = yes
retry control success before crash = no
fresh coordinator child executions added = 0
fresh coordinator terminal retry result = success
retry control success persisted after recovery = yes
```

This validates Window C without a production testing hook and without pretending a
journal transaction exists.

## Required evidence

| Law | Required evidence |
| --- | --- |
| R1 | immediate success then rerun has exactly one total child execution |
| R2 | actual `installDist` executable: fail → success → same DB/control rerun; both retry-ok and body execution count stay unchanged |
| R3 | exhaustion then rerun creates neither a further attempt nor new child work |
| R4 | durable attempt-1 failure, fresh coordinator, only attempt 2 executes |
| R5 | deterministic Window C decorator proof as described above |
| R6 | same logical retry control identity with incompatible fingerprint returns canonical divergence failure and dispatch count zero |

The public R2 script must print the resolved `installDist` binary path. R1/R3-R6 may
be focused coordinator tests, but their journal evidence must use the real canonical
operation identities. Architecture fitness and existing timeout/parallel installed
UATs remain regression checks after focused retry evidence is green.
