---
type: adr
id: ADR-0075
title: "Retry control reconciles durable state before scheduling child effects"
status: proposed
date: 2026-09-10
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0066
  - ADR-0069
  - ADR-0073
  - docs/v2/03-specifications/RECOVERY_DURABILITY.md
  - openspec/changes/retry-d-durable-reconciliation
---

# ADR-0075 — Retry control reconciles durable state before scheduling child effects

## Status

**Proposed.** Promoted to `accepted` only after R1–R6 (with R5 Window C and R2
installed-distribution acceptance) are green with fresh evidence. The ADR stays
`proposed` while the implementation compiles — acceptance is a verification
event, not a build event.

## Context

Canonical retry children have deterministic attempt-specific `OpId`s, but retry
itself has no persisted control fact. A new coordinator invocation starts the
retry loop at attempt one, which duplicates a previously successful child
effect. A completed child operation alone cannot tell the coordinator that the
enclosing retry is terminal, and the child-success / control-terminal crash
window cannot be recovered from a retry event.

## Decision

A retry logical invocation owns a durable control record for each one-based
attempt. The control operation identity uses the complete canonical identity:

```text
OpId(runId, stageIndex, stepIndex, inheritedBodyPath)
```

Its journal attempt is the retry attempt ordinal. Retry control input includes
the schema-versioned retry payload and a total canonical projection of its
compiled child body. The durable record, not events or in-memory counters, is
the authority.

Before scheduling an eligible child effect, retry reconstructs a typed
reconciliation state from the retry-control record and the exact deterministic
child operation facts. A child terminal success with a missing or running
retry-control terminal record closes that control record without running the
child again. Retry exhaustion is terminal only after the final failed ordinal.
Fingerprint divergence fails closed with zero child dispatches.

Child execution remains canonical re-entry. This ADR does not add
`dispatchRetryBlock`, a second engine, a new `StepSpec`, concrete child Step
routing, or a `BodyInvoker` implementation.

## Law 1 — Retry control row is durable state, not observability

```text
Retry control durable state
    = authority for retry aggregate lifecycle

RetryAttemptFinished / other events
    = observable projections of that state
```

The control row must answer, before any new effect:

```text
terminal?
resume?
next attempt?
fail closed?
```

It MUST NOT be designed as an ornamental event.

## Law 2 — Deterministic identity

```text
RetryControlIdentity
    = logical retry node/invocation identity
    = OpId(runId, stageIndex, stepIndex, inherited parentBodyPath)

AttemptIdentity
    = RetryControlIdentity + 1-based attempt ordinal
```

Children of an attempt MUST be namespaced by the attempt identity. Invariant:

```text
same retry
+ same attempt ordinal
+ same canonical child
→ same durable child identity
```

across restart / replay. No timestamp, no random UUID, no journal length, no
in-memory counter as durable authority.

## Law 3 — Pure ADT first (STATE)

Reconciliation state is a closed ADT with no nullable booleans and no
inconsistent combinations:

```text
NotStarted
AttemptPending(attempt: Int)                              // W1
AttemptInProgress(attempt: Int)                          // W2
ChildTerminalSuccessAttempt(attempt: Int)                // W4 / Window C
ChildTerminalFailureAttempt(attempt: Int)                // W3
AggregateTerminalSuccess(attempt: Int)                   // W5 (terminal)
AggregateTerminalExhausted(attempt: Int)                 // terminal
Divergent(operationId: String)                           // fingerprint mismatch
```

No `completed: Boolean?`, `failed: Boolean?`, or `attempt: Int?` combinations.
Every variant carries the evidence a downstream caller actually needs.

## Law 4 — Separate STATE from DECISION

```text
RetryReconciliationDecision
    = ReuseSuccess(attempt: Int)
    | ReuseFailure(attempt: Int)
    | ScheduleAttempt(attempt: Int)
    | ResumeAttempt(attempt: Int)
    | CloseSuccessFromChild(attempt: Int)
    | AdvanceAfterFailure(from: Int, to: Int)
    | RejectDivergence(operationId: String)
```

The reconciliation function is pure:

```text
durable control facts
+ durable child facts
+ retry contract
→ RetryReconciliationDecision
```

The caller MUST NOT re-derive behavior from booleans.

## Law 5 — Persist before effects

```text
persist retry intent/state
BEFORE
schedule child effect
```

The first persisted row alone is not enough: it MUST bind the attempt to its
children deterministically. After any crash, there is never a child durable
belonging to a retry that the retry aggregate is unaware of.

## Law 6 — Crash-window matrix

```text
W0  no control row, no child                          → safe to initialise
W1  control persisted, no child evidence (AttemptPending)
                                                       → schedule N exactly once
W2  Attempt N durable/in-flight (AttemptInProgress)
                                                       → delegate / reconcile
                                                       → no duplicate child
W3  child N = terminal failure, aggregate stale       → derive failure
                                                       → never execute N again
                                                       → advance or Exhausted
W4  child N = terminal success, aggregate stale       → reconstruct success
    (Window C, the mandatory row)                      → close aggregate
                                                       → child executions = 0
W5  aggregate terminal, observability gap              → no re-execution
                                                       → projections may complete
```

W4 is the law the recovery spec must demonstrate with a real
`OperationJournal` decorator, not by argument.

## Law 7 — Window C fault injection

Window C is proven with a test-only `OperationJournal` decorator that:

```text
append(child terminal fact)  → delegates successfully
                              → THEN throws exactly once
```

Conditions the decorator MUST satisfy:

- No production code paths take the decorator.
- No `sleep` or probabilistic race; the failure is exactly on the intended
  boundary.
- The decorator is configured against the exact canonical child `OpId` and
  status, so it cannot trigger on arbitrary rows.
- After the crash, a NEW coordinator / runtime is built and pointed at the
  same durable store.
- The test FIRST asserts that the precondition is real:

```text
child terminal success = present
retry aggregate terminal success = absent
```

If this precondition is not asserted, Window C does not count as PASS.

## Law 8 — Compatibility with pre-ADR-0075 journals

A retry control row is new durable information. Pre-ADR-0075 journals may
contain child retry history without the new control row. The recovery spec
MUST specify:

```text
A — Safe reconstruction
    legacy child facts  →  RetryReconciliationState
    when identities and child facts unambiguously reconstruct the aggregate.

B — Fail closed
    retry durable history exists
    + retry control state absent
    + reconstruction ambiguous
    → explicit incompatible durable state
    → RejectDivergence with reason
```

NEVER:

```text
control row absent → assume fresh retry → execute body again
```

That would reproduce the exact bug this ADR closes.

No migration framework is required; the policy above is the migration.

## Law 9 — Fingerprint / divergence before effects

R6 is bound to the control aggregate. Before any:

```text
ScheduleAttempt
ResumeAttempt
```

the coordinator MUST validate compatibility:

```text
existing retry durable state
+ incompatible fingerprint / structure
→ RejectDivergence
→ children executed = 0
```

Fail closed.

## Law 10 — Terminality law

```text
AggregateTerminalSuccess(attempt)
OR
AggregateTerminalExhausted(attempt)
=
terminal Retry state
```

Therefore:

```text
terminal Retry + compatible replay
→ zero child scheduling
```

This property is independent of whether `RetryAttemptFinished` was projected.
Observability gaps MUST NOT cause re-execution.

## Law 11 — Single aggregate writer

The retry reconciliation / control-flow component is the SOLE authority that
updates the retry control row. The following MUST NOT write retry aggregate
state directly:

- child executors
- `ShExecution`
- step handlers
- event projectors
- the legacy engine

Children produce their own durable facts. Retry observes and reconciles the
aggregate. This prevents the "two writers, one durable fact" failure mode.

## Window C testability

The production journal API remains unchanged. A test-only `OperationJournal`
decorator delegates a configured child terminal append, then throws exactly
once. A fresh coordinator reuses the same delegate. This proves child
persistence without retry control completion and verifies reconstruction
without adding a production fault port.

## Consequences

- Retry events can later project durable transitions, but MUST NOT become a
  source of recovery truth.
- Retry control reconstruction MUST preserve inherited body-path and branch
  identity.
- `RECOVERY_DURABILITY.md` MUST add the retry control / child fact invariant
  and the W0–W5 crash-window matrix.
- Timeout and parallel are not changed by this ADR. They SHOULD later be
  checked for the same composite-control reconciliation law before it becomes
  a general rule. AGENTS.md may be updated to record the general law after
  RETRY-D is fully closed with evidence.
