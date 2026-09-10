# Proposal: RETRY-D — durable reconciliation for canonical retry blocks

## Status

**PROPOSED.** This change records the design gate for the retry-only repair requested
under `E-EM-11 T1`. It authorizes no production implementation until its design and
recovery tests are reviewed.

## Problem

The canonical retry body currently gives every child attempt a deterministic child
`OpId`, but does not persist the retry control construct's own state. On a second run,
`CanonicalDurableRunCoordinator.dispatchBody` starts its in-memory loop at attempt 1.
A completed retry body can therefore execute again even though a child terminal fact
already exists.

The installed-distribution reproduction is `fail → success → rerun same DB/control
state`, which appended a second `retry-ok`. This is an E-EM-11 T1 durable-recovery
defect, not a reason to restore direct `StepSpec` execution.

## Scope

### In scope

- A typed, retry-control reconciliation algebra derived exclusively from journaled
  retry-control and child-operation facts.
- A deterministic retry control identity that preserves the complete inherited
  canonical `bodyPath` and branch identity.
- Recovery for the child-success / retry-control-not-terminal Window C.
- R1 through R6, including the installed-distribution R2 acceptance route.
- A test-only journal decorator for deterministic Window C fault injection.
- A proposed ADR-0075 and recovery-spec delta, if the design is accepted.

### Out of scope

- `RetryAttemptFinished`, `TimeoutScheduled`, and re-enabling grammar-full UATs.
- Timeout or parallel semantics.
- New `StepSpec` types, direct `StepSpec` execution, a nested engine, or
  step-specific child execution.
- A production fault-injection hook or a mutable testing flag in the coordinator.
- Retagging or restating B13 / E-EM-11 as closed.

## Existing authority and conflict

`docs/v2/05-roadmap/IMPLEMENTATION_BACKLOG.md` names `E-EM-11 T1` as real bounded
retry with attempt identity, persisted outcomes, coordinator/journal work, and a real
restart UAT. That row supplies the backlog link, but calls the work design-gated.

The older `em11-canonical-m2r1-runtime` proposal/design predates ADR-0073 and directs
a dedicated `dispatchRetryBlock` path. RETRY-D does **not** accept that route. The
current coordinator's generic body machinery may be refined, but retry children must
continue to re-enter canonical `dispatch()` and must not interpret `StepSpec`.

## Acceptance criteria

1. Retry reconciliation runs before any new child effect.
2. One logical retry invocation has a canonical, parent-body-path-aware control
   identity; its attempt ordinal is deterministic and durable.
3. A terminal retry success or exhausted failure is reused with zero new children.
4. Window C is exercised with a real persisted child success and no persisted retry
   terminal record. A fresh coordinator reconstructs terminal success with zero new
   child executions.
5. A mismatched retry-control fingerprint fails closed with zero child executions.
6. R2 invokes the executable produced by `:pipeline-application:installDist` against
   the same durable DB/control state twice.
7. `FArchLeg1ExecutionAuthorityTest` remains green and direct `StepSpec` execution
   remains zero.
