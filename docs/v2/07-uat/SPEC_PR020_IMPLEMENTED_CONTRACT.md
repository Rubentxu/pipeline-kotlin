# PR-020 — implemented contract (as-built)

> Cycle: p-1f3622e11c093341/h4-coordinator-collapse
> Original WorkItem: 2c910ea8-1967-4c89-8312-83fc1a29199d (Done)
> Base: 9ed0a788 (H3 close on main)
> Certified SHA: a531dd9588392ba0f11b2fa9c6e28d9233f75c4d
> Merged as: 633e597d (receipt), then 7200b95b (orphan-KDoc guard)
> Recertified: 7200b95b — full gate green, 605 classes, 3670 tests, 0 failures, 21m20s
> Nature: **specification-as-built**

This is not the specification that was planned. It is a record of what actually
shipped and was actually certified. Where the plan changed during the work, the
change is stated rather than smoothed over — a document that pretends the plan was
followed is a document nobody can use to find out what happened.

## SHA provenance, proven rather than assumed

The full gate ran on `a531dd95`. `main` is now `7200b95b`. The transfer of that
verdict is a proof, not a hope:

```text
commits between the gate SHA and the receipt merge:  1
  633e597d  docs(uat): TRAIN H4 / PR-020 receipt

git diff --name-only a531dd95 633e597d -- v2/     (empty)

v2/ subtree digest at a531dd95 : 9a80cfa4e8aee8d694a57d09e1780389
v2/ subtree digest at 633e597d : 9a80cfa4e8aee8d694a57d09e1780389
```

Identical production and test trees; the only difference is one markdown file. The
gate's verdict therefore covers `633e597d` exactly. `7200b95b` adds one test file
and is certified by its own gate run.

## What shipped

```text
Coordinator                     552 lines   (was 2032 at the H3 close; target <600)
Ratchet                         552         (was 2514)
```

The ratchet is pinned to the exact current size, not to a round number, because a
ceiling left slack above reality is not a ratchet. At 2514 with a 741-line file it
would have taken 1773 lines of regression to trip.

### The extracted authorities

| Authority | Owns |
| --- | --- |
| `RunLifecycleEngine` | the run's bookends; the RunStarted/RunFinished correlation invariant |
| `BodyExecutionEngine` | scoped-body execution: reentry binding, retry/waitUntil loops, bookends |
| `RecoveryInterpretationEngine` | the four recovery resolutions, interpreted |
| `RunningSubprocessRecovery` | reattaching a running subprocess (port + adapter) |
| `BeforeStageDirectiveEngine` | directive admission, decode, composition, evaluation, observation |
| `StepDispatchEngine` | durable identity, structural gate, typed prepare, execute, fold |
| `ParallelStageEngine` | the aggregate: reconcile, single-writer rows, join, fold |
| `StageExecutionEngine` | what a stage does once the run has started it |

The coordinator keeps only what it alone can do: the run bookends, the stage loop,
the stage-shape dispatch, and the StepRegistry view.

### Why `StageExecutionEngine` is separate, and why Stage != Step

A stage is not a Step. Its identity is the stage; its `post` finalizers are a
second dispatch surface with their own durable key space (`post:<CONDITION>:<i>` at
step indices past the DSL cap); and its outcome is chosen by a planner rather than a
handler. Folding it into the Step spine would have produced one large class again.

Every engine returns a **closed verdict**, because a collaborator may not write
`return@run` or `continue@stagesLoop`. Only the owner of the run loop decides what
the run does next.

The context a stage leaves behind travels on the `Abort` verdict as well as
`Completed`. A stage can open a scope frame that the next stage inherits, so
returning it only on the happy path would drop a frame exactly when a run aborts.

## Invariants that remain bit-equivalent

These were carried across verbatim and are the reason the refactor is safe:

- directive admission events precede the decode; the denial event precedes the run
  folding; the negative verdict is observable **before** the skip it causes;
  `Unverifiable` is observable before its fail-closed denial;
- `StageStarted < steps < PostConditionSelected < post steps < StageFinished`;
- a failing finalizer fails the run but never rolls back the remaining finalizers;
- exactly one `StageStarted` for every admitted stage, parallel bodies included;
- a parallel aggregate is RUNNING in the journal before any branch launches, is
  written exactly once, and the branch join runs inside a structured `supervisorScope`;
- a body-bearing Step has exactly one child loop and one credential acquisition site;
- the branch path threads `ExecutionContext` explicitly and never reads coordinator
  state;
- the parallel decision has exactly one effect executor.

## Deviations from the initial plan

1. **Slices 2b and 3 should have been one commit.** Moving the step spine broke 14
   architecture guards. Between the two slices the tree compiled, detekt passed, and
   14 fitness tests failed — a state where the fitness suite was reporting on code it
   could no longer see. A slice boundary that leaves the suite lying is not a
   boundary.
2. **The cycle stall was never a tooling limitation.** It was recorded as one across
   two cycles. The missing step is `--artifact kind=path` **inline on the transition**;
   storing the artifact alone leaves `artifacts: 0`.
3. **A fourth blocker appeared at close:** `sddk debt report` is not implemented in
   this build, so `debt-severity-assigned` and `debt-priority-assigned` cannot pass
   and the verify phase is structurally blocked. The tool says so rather than
   fabricating a report, which is the correct behaviour and the reason the cycle
   cannot be closed through `Release` today.
4. **The orphan-KDoc probe needed a narrower rule than expected.** The general rule
   was measured and rejected; see `OrphanKdocFitnessTest` for the numbers.

## Limits of this document

- 132 repository tests are skipped, environment-gated, and pre-existing. Nothing
  here claims they pass.
- Certification is local, on one machine, for the SHAs named above.
- The cycle is in `verify`, not closed, for the reason in deviation 3.
