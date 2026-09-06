---
type: spike
id: SPIKE-016
title: "Prove deterministic durable replay for executable Kotlin scripted blocks"
status: proposed
date: 2026-09-05
related:
  - ADR-0065
  - docs/v2/03-specifications/DURABLE_KOTLIN_EXECUTION.md
---

# SPIKE-016 — Durable Scripted Replay

## Hypothesis

Pipeline Kotlin can execute normal `suspend` Kotlin scripted code with runtime-returning
steps and survive runtime restart by replaying from the same compiled artifact and
reusing journaled step results, without serializing Kotlin continuations.

## Why this spike is mandatory

This is the highest-risk assumption in ADR-0065. If it fails, broad changes to DSL,
IR and block steps should not proceed.

## Scope

Build the smallest possible isolated vertical prototype.

Allowed:

- one scripted entry point;
- test-only `sh`/fake durable step;
- test journal;
- source/call-site ID prototype;
- restart simulation;
- loop and nested scope identity tests.

Out of scope:

- production plugin migration;
- credentials;
- remote worker protocol;
- UI;
- full Declarative compiler rewrite.

## Prototype API

```kotlin
suspend fun scripted(scope: ScriptedScope) = with(scope) {
    val branch = sh(
        script = "printf 'main\n'",
        returnStdout = true,
    ).trim()

    if (branch == "main") {
        sh("printf deploy")
    }
}
```

## Experiments

### S16-E1 — Runtime value

Prove first `sh` returns `"main\n"` to Kotlin and controls the branch.

### S16-E2 — Replay completed result

After first operation is journaled, abort the runner before second operation finishes.

Restart.

Assert:

- first child process launch count remains 1;
- first result is decoded from journal;
- branch remains `main`.

### S16-E3 — Crash cut matrix

Crash at:

- before schedule persistence;
- after schedule/before launch;
- after launch;
- while process running;
- after terminal result/before return to Kotlin;
- after return/before next call.

Define expected recovery for each cut.

### S16-E4 — Source mismatch

Change source digest. Recovery must fail closed.

### S16-E5 — Loop identity

```kotlin
repeat(3) { i ->
    step(i)
}
```

Keys must be stable/distinct after restart.

### S16-E6 — Nested block identity

Prototype:

```kotlin
retryLike(2) {
    scoped("x") {
        step()
    }
}
```

Attempt/scope paths must be deterministic.

### S16-E7 — Recorded failure

First call records a failure.

On recovery it must throw an equivalent typed runtime exception at the same logical
call without rerunning the effect.

## Required instrumentation

Record:

```text
entry point id
call-site id
dynamic scope path
invocation ordinal
attempt id
input digest
launch count
journal transition
returned/throw result
```

## Pass criteria

All experiments pass under repeated randomized crash points.

At least one test must prove no continuation/coroutine object is serialized into the
journal.

## Fail criteria

Spike fails if any is true:

- stable identity depends on object identity or mutable JVM addresses;
- a completed non-idempotent effect must rerun to rebuild control flow;
- source mismatch silently continues;
- loops cannot disambiguate call instances deterministically;
- normal Kotlin return values require converting the body to a static AST;
- continuation serialization becomes required.

## Decision after spike

### PASS

Proceed EM-1 onward and mark ADR-0065 accepted/implementation-ready as appropriate.

### FAIL

Stop production migration. Document findings and consider alternatives:

- narrower scripted subset;
- explicit durable value APIs;
- compiler-generated state machine with a stable public ABI;
- a consciously designed CPS/state-machine approach.

Do not drift into one of these alternatives implicitly.
