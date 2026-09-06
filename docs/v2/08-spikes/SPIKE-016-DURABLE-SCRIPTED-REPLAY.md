---
type: spike
id: SPIKE-016
title: "Prove deterministic durable replay for executable Kotlin scripted blocks"
status: passed
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

## Widened scenarios (audit extensions + negative, cycle em-0 2026-09-06)

### S16-E2a — Replayed value identity

After crash-cut AFTER_RETURN + restart: replayed value equals the original,
launch count stays 1, and the replay is flagged as journal-sourced.

### S16-E2b — Decode idempotence

Decoding the same journal bytes twice yields identical snapshots (readers are
pure; no hidden reader state).

### S16-E3a — Crash-cut atomicity

At every cut of the E3 matrix the persisted bytes decode without error — no
torn or partially-written state is observable.

### S16-E4a — Fail-closed with an in-flight RUNNING entry

Source mismatch is refused while an operation is RUNNING, with zero
additional launches (mismatch is not healed by recovery).

### S16-E5a — Nested loop identities

`loop(o, i) { loop(n, j) { step } }` yields 4 distinct, stable identities.

### S16-E5b — Ordinal disambiguation in one scope

Two identical calls in one scope take ordinals 0 and 1; identities stable
after restart; no aliasing.

### S16-E5c — Retry attempts inside loop iterations

`loop(L, i) { retry(R, 2) { step } }` composes `attempt:1|2` segments with
the loop segment; 4 distinct stable identities.

### S16-N1 — Source mismatch on a SCHEDULED entry

Mismatch is refused even against a non-terminal SCHEDULED entry, before any
launch.

### S16-N2 — Adversarial scope-name collision

Distinct nestings whose unescaped segments compose the same identity string
are refused through the input digest when inputs differ; the equal-input
aliasing case is a documented production requirement for ADR-0066
(length-prefixed/hashed components; raw concatenation forbidden).

### S16-N3 — Schema-version downgrade

A stream written with a newer schema header is refused by an older reader
(fail closed, no partial interpretation) while untouched streams read
normally (ADR-0067 anchor).

### S16-N4 — INTERRUPTED ordering

Cancel-recovery of a RUNNING entry journals the ordered chain
`SCHEDULED → RUNNING → RECONCILING → INTERRUPTED`, a terminal that replays
as a typed interruption without relaunch.

### S16-N5 — Forked cancellation propagation

Parent cancellation during recovery interrupts the in-flight child and
prevents every subsequent operation from launching.

### S16-N6 — Worker crash in the timeout window

Timeout-recovery of a RUNNING entry journals a `TIMEOUT` terminal without
relaunch, preserving the single-launch evidence (ADR-0068 anchor; deadline
record persistence is production work).

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

## Execution receipt

**Result:** PASS — test-only feasibility evidence; it is not evidence that the
production runtime already implements this model.

The isolated harness proves a serialized/deserialized primitive journal can be
read by a new runtime and executor, replay recorded values/failures, fail closed
before launching an effect when source or input digests differ, and reconcile a
live test-only `setsid` child through `RUNNING → RECONCILING → SUCCEEDED` without
relaunching it. It also exercises the complete cut matrix plus 18 deterministic
seeded repetitions, stable loop/nested-scope identities, and append-only launch
evidence.

**Evidence:** `Spike016DurableScriptedReplayTest` — 11 tests, 0 failures, 0
errors; targeted run on 2026-09-05. Command-output SHA-256:
`9033e3dc6852527f84d1c60d9f4e842969f791132ad3d5c22428281ccb3db455`.

**Remaining production work:** compiler-derived call-site IDs, durable journal
storage/codec, durable-task reattachment, and public SDK/runtime integration are
the subsequent EM slices, not properties established by this spike.

## Widened execution receipt (cycle em-0, 2026-09-06)

**Result:** PASS (widened) — the base E1..E7 evidence is now extended with the
audited extensions E2a..E5c and negative scenarios N1..N6, including
interruption/timeout recovery terminals that anchor ADR-0066 (identity
constraints), ADR-0067 (schema downgrade refusal) and ADR-0068 (INTERRUPTED /
TIMEOUT ordering).

**Evidence:** `Spike016DurableScriptedReplayTest` — 24 tests, 0 failures,
0 errors; fresh XML generated 2026-09-06T10:12:17Z (canary: prior XML deleted
before run). Command:
`timeout 600 ./gradlew -p v2 :pipeline-application:test --tests 'Spike016DurableScriptedReplayTest'`
exit 0. XML SHA-256:
`79a245f390977de87dbf470980bb13295d4492fdf1c144403e9646b36576fdd9`.

**ADR-0065 ratification:** with the widened suite green, the acceptance of
ADR-0065 satisfies the gate demanded by the blocked `lfc4-000` specification;
ratification is recorded at verify of cycle
`p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze`.

**Production limits proven by negatives:** raw identity concatenation can
alias under adversarial scope names (N2 — input digest catches differing
inputs; ADR-0066 forbids raw concatenation); schema downgrades are refused
(N3); interrupted/timeout terminals are ordered, terminal, and replay without
relaunch (N4/N5/N6).
