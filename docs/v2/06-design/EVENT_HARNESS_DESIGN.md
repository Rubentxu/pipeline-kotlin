# Event Harness — executable protocol verification

Status: PROPOSED  
Primary mode: POST_RUN

## Value statement

The harness exists to catch observable protocol defects that ordinary exit-code tests miss. It turns real
pipeline histories into executable specifications and removes duplicated ad-hoc event assertions.

**Grounded starting point:** CTX-P4-EX at `d0ccf4b5` already has a GREEN 10-example real-CLI gate, including event assertions for catchError/parallel/retry/timeout. The first harness implementation must run *differentially* against that oracle; it is a refactoring/generalization of verification, not a redefinition of expected behavior.

## Layer 1 — universal event protocol grammar

These laws apply independently of a specific example:

- `RunFinished` requires a preceding `RunStarted` and is terminal for that run;
- `StageFinished(S)` requires `StageStarted(S)`;
- an executed Step has at most one terminal lifecycle outcome for one execution attempt;
- branch finish requires branch start;
- retry attempt finish requires retry attempt start;
- replay/reuse must not fabricate fresh Step/Branch lifecycle events;
- structural parents cannot finish before required children have reached their allowed terminal relation;
- event references must resolve to the same run/resource hierarchy unless explicitly cross-run.

Parallelism is verified with **partial ordering**, never a brittle global sequence.

## Layer 2 — scenario contracts

A small typed algebra is sufficient for the first 80%:

```kotlin
sealed interface EventConstraint {
    data class Exactly(val selector: EventSelector, val count: Int) : EventConstraint
    data class Never(val selector: EventSelector) : EventConstraint
    data class Before(val first: EventSelector, val second: EventSelector, val relation: RelationScope) : EventConstraint
    data class After(val first: EventSelector, val second: EventSelector, val relation: RelationScope) : EventConstraint
    data class Outcome(val resource: ResourceSelector, val expected: ExpectedOutcome) : EventConstraint
}
```

YAML/TOML is a codec for this ADT, not an embedded scripting language.

## Failure report

Always report the smallest useful counterexample instead of dumping the complete trace:

```text
EVT-PAR-004
Expected: ParallelBranchFinished(branch=A) < StageFinished(stage=build)
Observed relevant slice:
  #31 ParallelBranchStarted A
  #40 StageFinished build
  #42 ParallelBranchFinished A
```

## Acceptance is separate from execution

```text
PipelineOutcome   = SUCCESS
AcceptanceOutcome = FAILED
Reason            = event contract violation
```

This is mandatory for examples/CI. A verifier bug must not retroactively mutate the execution outcome.

## Historical reverification

A closed run can be checked against a newer contract without rerunning effects:

```text
historical run + contract v1 -> PASS
historical run + contract v2 -> FAIL + counterexample
```

This is a major reason to keep local event history.

## High-value follow-ups after core harness

### Event ↔ journal consistency

Check that observable projection does not contradict durable truth:

- operation terminal success vs StepFailed contradiction;
- terminal reusable operation vs fabricated fresh lifecycle events;
- run/stage outcome projection consistent with durable typed result where a lossless carrier exists.

Journal remains authority; verifier only detects inconsistent projection.

### Replay metamorphic verification

For same durable facts:

- fresh and replay final semantic outcome equal;
- replay performs no forbidden external effect;
- replay emits no fresh lifecycle events for reused work;
- artifact/result equivalence can be checked where deterministic.

### Mutation tests of the harness

Mutate a valid trace by deleting start, duplicating terminal, swapping parent/child order, corrupting resource
identity or inserting a replay lifecycle event. The verifier must reject the mutations. This prevents a
false-green harness.

### Contract coverage

Later track which event types and ordering laws are exercised by real examples/UATs. Do not build this
before the basic verifier has useful contracts.

## DSL/config

```kotlin
pipeline {
    observe(eventsHarness())
    stages { ... }
}
```

For `foo.pipeline.kts`, default discovery is `foo.events.yaml`. Observation config is outside execution
fingerprinting/replay semantics.
