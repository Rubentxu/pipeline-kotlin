# TRAIN H4 / PR-020 — Coordinator Collapse receipt

> WorkItem: 2c910ea8-1967-4c89-8312-83fc1a29199d
> Cycle: p-1f3622e11c093341/h4-coordinator-collapse
> Branch: h4-coordinator-collapse
> Base: 9ed0a788 (H3 / PR-019 merged on main)
> Certified SHA: a531dd9588392ba0f11b2fa9c6e28d9233f75c4d
> Gate: ./gradlew check --rerun-tasks — BUILD SUCCESSFUL in 21m 34s, 277 tasks executed
> Evidence: 604 test classes, 3665 tests, 0 failures, 0 errors, 15 modules, results 22 s old at read time

## Exit criterion, and how it is met

PR-020 asked for two things: collapse the coordinator below 600 LOC, and gate the
shared-model composition.

```text
CanonicalDurableRunCoordinator.kt
  2032 lines at the H3 close
   741 after slice 2b
   552 at the certified SHA          <- below the 600 target
```

The second half is `SharedModelCompositionFitnessTest` (slice 0), a ledger of which
modules consume each shared-model type. It was verified by mutation before being
trusted: declaring a consumer that does not exist puts it red.

## Commits

| Commit | Slice | Content |
| --- | --- | --- |
| 98f65363 | 0 | the shared-model composition ledger |
| 3db3820e | 1 | BeforeStageDirectiveEngine; the admission fail-closed bug, fixed |
| 07985e78 | 2a | dead executeWaitUntilBodyInline removed |
| 5fcaf2f1 | 2b | StepDispatchEngine + ParallelStageEngine; 62 dead imports; 12 dead baseline entries |
| 573ad1ee | 3 | 14 architecture guards follow the run path; ratchet 2514 -> 741 |
| a531dd95 | 4 | StageExecutionEngine; coordinator 741 -> 552; ratchet re-pinned to 552 |

## Defects found while certifying, not after

### 1. Admission fail-closed was broken in silence (3db3820e)

`admit()` returned `null` to mean "denied" while `interpret()` read `null` as
"admitted". A denied directive therefore emitted its `DirectiveDenied` event and
then let the run continue. The symptom is an emitted event, not an exception, so
nothing downstream could notice.

Fixed with a closed `Admission` ADT: `Denied(reason)` and `Permitted(seam)`.

The guard is proven non-vacuous by mutation. Reverting the denial to
`Permitted(emptyList())` reproduces exactly four failures:

```text
S1B_StageDirectiveAdmissionTest > a registry without the declared key also denies
S1B_StageDirectiveAdmissionTest > an unknown directive denies the stage before any step dispatches
S1C_DirectiveEventObservabilityTest > a denied stage emits DirectiveDenied before the run aborts
DirectivePluginContractSuiteTest > without plugin - same pipeline denies fail-closed
```

### 2. The fitness suite was measuring file location, not behaviour (573ad1ee)

Moving the step spine out of the coordinator broke **14** architecture guards. Not
one was a regression: each scanned only `CanonicalDurableRunCoordinator.kt`, and
the code it polices had moved.

This suite had already paid for that mistake. H2's W1d guard reported zero loops
after `dispatchBody` moved, because an empty inventory is indistinguishable from a
clean coordinator.

`DurableRunPathSources` is now the single union every affected guard reads, and its
`require()` fails loudly rather than degrading into an empty scan. The repairs are
property repairs, not expectation edits:

- the parallel decision still has **exactly one** effect executor — now
  `ParallelStageEngine`, and a second consumer still fails;
- the branch path still threads `ExecutionContext` explicitly — its regex is
  whitespace-tolerant but still requires all six arguments **in order**, so a
  reformat cannot fail it and a dropped argument still can;
- the workspace allowlist names `ParallelStageEngine` exactly, with its reason, as
  that guard's own comment already prescribed.

Non-vacuity is demonstrated by the routing-debt guard itself:
`loopDefinitions=1, credentialAcquisitions=1` can only hold when
`invokeBodyChildren` and `executeCredentialLeasedBody` are both in scope, and both
now live in `StepDispatchEngine`. A union missing that file would report 0 and fail.

### 3. The ratchet was not guarding anything (573ad1ee, a531dd95)

The ceiling sat at 2514 while the file was 741 and then 552. It would have taken
1773 lines of regression to trip, which is the entire class of growth the guard
exists to stop. Pinned to the real value at each step; now 552.

### 4. Debt that no tool was watching (5fcaf2f1, a531dd95)

- `UnusedImports` is not configured in detekt at all, so **62** stale imports had
  accumulated in the coordinator unnoticed. Removed.
- **12** dead `detekt-baseline.xml` entries, plus 3 pre-existing violations that the
  move resurfaced (the baseline is keyed by `File:Class$signature`): a
  cyclomatic-35 method, two row builders carrying a dead `ParallelAggregateId`, and
  an emitter carrying a dead `stageIndex`. Fixed by decomposing, not by
  re-baselining.
- **4** orphan KDoc blocks describing `DecodedBeforeStage`,
  `seamDecodeFailureReason`, `decideStageContinuation` and `dispatch`/`dispatchBody`
  — all moved in slices 1 and 2. A file documenting code it does not contain sends
  the next reader hunting for a symbol that is not there.
- `executeWaitUntilBodyInline` had **zero** call sites (07985e78).

## Two decisions that became pure

`AggregateAction` (5fcaf2f1) is the whole parallel law — fail-closed on divergence,
no duplicate row on reuse, RUNNING written before any branch launches — expressed as
a pure mapping from reconciliation decision to obligation. The row travels as a
value, so the engine still performs the single write but no longer decides what to
write.

`DispatchPreparation` (5fcaf2f1) is the pure pre-execution prologue: durable
identity, structural verdict, overlay projection, metadata, fingerprint. `Rejected`
carries the `operationId` and `input`, because a rejection that lost its identity
could not be journaled fail-closed — it would simply vanish.

## Ordering laws preserved verbatim

- directive admission before decode; denial event before the run folds; negative
  verdict observable **before** the skip it causes; Unverifiable observable before its
  fail-closed denial;
- `StageStarted < steps < PostConditionSelected < post steps < StageFinished`;
- a failing finalizer fails the run but never rolls back the remaining finalizers;
- exactly one `StageStarted` for every admitted stage, parallel bodies included.

The context a stage leaves behind travels on the `Abort` verdict as well as
`Completed`: a stage can open a scope frame the next stage inherits, so returning it
only on the happy path would drop a frame exactly when a run is aborting.

## A blocker that was not a blocker

The cycle sat in `phase: explore` with `phase.explore.complete` reporting
`requires_met: false`, recorded as a tooling limitation. It is not. Two requirements
were being conflated: the gate evaluation, whose evidence must carry `argv`,
`exit_code` and `output_digest`; and an `exploration-report` artifact declared
**inline** on the transition:

```text
sddk cycle transition --cycle <c> --transition phase.explore.complete \
  --gate-receipt <id> --artifact exploration-report=<path>
```

Storing the artifact alone leaves `artifacts: 0` and the transition unsatisfiable.
H2 wrote its report and never advanced, which is the direct proof. Both h2 and h4
were unstuck this way and are now in `specify`.

## What this receipt does NOT claim

- The cycle is in `specify`, not closed. `phase.specify.complete` still declares a
  `specification` artifact that has not been written; the work ledger is stored as
  `art-fd1bee164f08-f09ce16d` (exploration) and `art-2f1535e11d52-ae21b465` (design).
- 132 tests are skipped repo-wide. They are environment-gated and were skipped before
  this work; this receipt does not claim they pass.
- Certification covers this SHA on this machine only.
