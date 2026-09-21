# Execution Model Migration Roadmap

**Programme:** EM — Durable Kotlin Execution Model  
**Authority:** ADR-0065 after acceptance  
**Rule:** progressive vertical slices, fresh evidence, no test weakening.

## EM-0 — Contract freeze and baseline

### Goal

Make the architectural reset explicit before production changes.

### Work

- accept ADR-0065;
- merge new specifications/UAT;
- update Jenkins familiarity contract;
- identify persisted schemas touched by EM;
- inventory tests that encode transitional behavior;
- record baseline commit and fresh test evidence.

### Exit criteria

- ADR accepted;
- no unresolved contradiction between RUNTIME_MODEL, DSL_SPEC, STEP_PLUGIN_SDK and
  ADR-0065;
- Jenkins reference baseline pinned;
- SPIKE-016 test plan runnable;
- current base test failures recorded from a fresh run, not memory.

### Rollback

Documentation-only; revert proposal.

---

## EM-S0 — SPIKE-016 durable scripted replay

### Goal

Prove the hardest architectural assumption before refactoring broad production code.

### Mandatory proof

A compiled Kotlin pipeline body:

```kotlin
val value = sh(..., returnStdout = true)
if (value.trim() == "A") {
    sh("echo branch-a")
}
```

must:

1. execute and return the real value;
2. persist the first operation result;
3. simulate runtime death;
4. restart from the same artifact;
5. reuse the completed value without relaunch;
6. take the same branch;
7. preserve stable operation identities;
8. reject a source digest change.

### Exit criteria

All mandatory spike UAT pass with fresh XML/log evidence.

### Stop condition

If operation identity cannot be made deterministic through loops/nesting without
serializing continuations, stop the broader migration and revisit ADR-0065.

---

## EM-1 — Durable terminal-result contract

### Goal

Fix the root contract behind INC-039.

### Work

- introduce `DurableTaskSnapshot`;
- introduce `DurableTaskTerminal`;
- introduce `FailureRecord`;
- change SDK catch paths to preserve structured failure;
- keep old `DurableShellResult` adapter temporarily;
- pure terminal classifier tests.

### Must not change yet

- public DSL;
- catchError;
- retry;
- compiler scripted model.

### Exit criteria

- `awaitTerminal()` cannot return launching/running by type;
- COMPLETE/exit semantics preserved;
- real launch failure has failure record;
- real lost route has failure record;
- old callers pass through adapter;
- UAT-REC durable shell anchors remain green.

### Rollback

Use legacy adapter implementation; persisted additions remain additive.

---

## EM-2 — Central StepExecutionBoundary

### Goal

One owner for step lifecycle and typed exceptions.

### Work

- introduce exception hierarchy;
- centralize StepStarted/StepFailed/StepFinished;
- map handler errors at one boundary;
- preserve scope-stack invariant behavior;
- retain `StepOutcome` at coordinator/public boundary.

### Exit criteria

- exactly one StepFailed for a failed step;
- `returnStatus` future path can represent non-zero success;
- no generic catch swallows engine invariant/scope leak;
- replayed failure emits lifecycle according to defined replay event policy.

---

## EM-3 — Jenkins-faithful `sh`

### Goal

Make shell semantics correct before adding more wrappers.

### Work

- typed `ShellCommand`;
- `ShellReturnMode`;
- Kotlin façade overloads/generated surface;
- `encoding`;
- `label`;
- `returnStatus`;
- `returnStdout`;
- remove typed-on-string implementation;
- deprecate shell-local `timeoutMs`/`env`;
- preserve legacy call adapters.

### Exit criteria

UAT-JEP shell matrix passes, including:

- default nonzero failure;
- returnStatus nonzero success;
- returnStdout + stderr;
- multiline/shebang;
- durable restart;
- lost/launch failure.

---

## EM-4 — First-class body execution + IR

### Goal

Create generic body semantics before implementing each wrapper.

### Work

- `PipelineBody`;
- `BlockStepNode`;
- context overlay stack;
- nested body operation identity;
- plugin descriptor `takesBody`;
- static declarative compiler support.

### Exit criteria

A test-only block plugin can:

- invoke body once;
- invoke body twice;
- add context;
- catch body exception;
- restore context on exception.

No shell rewrite.

---

## EM-5 — Timeout/cancellation

### Goal

Move timeout out of shell semantics.

### Work

- persistent deadline;
- Boolean `activity`;
- cancellation scopes;
- durable process-tree cancellation;
- interruption records;
- recovery with active timeout.

### Exit criteria

- absolute timeout survives coordinator/runtime restart;
- activity timeout has tested activity semantics;
- nested shell is terminated;
- catchInterruptions false rethrows;
- no `timeoutMs` needed by canonical sh.

After this gate, deprecate/remove canonical shell timeout field.

---

## EM-6 — retry / catchError / warnError / unstable

### Goal

Replace workflow-control shell rewrites.

### Work

- real retry body;
- attempt identities/events;
- typed retry conditions;
- real catchError;
- real warnError;
- direct unstable result mutation/event;
- delete rewriteWorkflowControl from canonical path.

### Exit criteria

- nested arbitrary plugin/file/credential steps work inside catchError;
- user abort not retried by vanilla retry;
- catchError interruption flag verified;
- warnError parity verified;
- no inner step flattened into a shell script.

---

## EM-7 — Context block migration

### Goal

Unify contextual Jenkins steps.

### Work

- withEnv;
- withCredentials;
- dir;
- timestamps;
- ansiColor;
- local node context.

### Exit criteria

- context nesting/restoration tests;
- PATH+ semantics;
- secret cleanup/redaction;
- cwd restoration after timeout;
- output decorators do not corrupt replay/output offsets.

---

## EM-8 — Real scripted runtime

### Goal

Promote SPIKE-016 architecture to production `script {}`.

### Work

- `suspend ScriptedScope.() -> Unit`;
- compiled artifact entry points;
- generated step façades;
- source call-site IDs;
- deterministic dynamic scope identities;
- guarded/linted nondeterministic API policy.

### Exit criteria

- runtime-returning `sh`, `readFile`, `fileExists`, `pwd`, `isUnix` usable in Kotlin
  conditions;
- loops and branching replay deterministically;
- runtime dies at multiple cut points and recovers;
- source/plugin mismatch fails closed.

---

## EM-9 — Jenkins differential compatibility gate

### Goal

Make "Jenkins familiar" executable rather than descriptive.

### Work

- Jenkins baseline container/harness;
- paired Groovy/Kotlin scenarios;
- normalized observable result comparison;
- compatibility report artifact.

### Compared observables

- step return;
- build/stage result;
- exception/interruption category;
- stdout/stderr;
- attempt count;
- timeout behavior;
- environment/cwd;
- restart semantics where equivalent.

### Exit criteria

All F2/F3 steps declare:

- PASS;
- documented intentional deviation; or
- unsupported with explicit compatibility level.

---

## EM-10 — Legacy removal

### Goal

Delete transitional architecture only after replacement proves stable.

Candidates:

- `runShellCommand(): String` as internal authority;
- typed-on-string shell mapping;
- workflow-control shell rewrites;
- scripted shell-text accumulator;
- fake `pwd`/`isUnix` placeholders;
- old running/terminal mixed shell result;
- deprecated `timeoutMs`/`env` shell parameters.

### Exit criteria

- no active production caller;
- compatibility fixtures use adapter layer or migrated format;
- full V2 gate passes from fresh evidence;
- no required persisted history becomes unreadable.

---

# Validation ladder per EM implementation PR

Use repository testing policy, with equivalent levels:

1. compile changed module;
2. one new/changed test;
3. whole changed test class;
4. related package/suite;
5. module tests;
6. V2 check/final gate when appropriate.

Before a test run used as evidence, delete/canary the relevant JUnit XML and verify it
is newly generated.

Never classify a failure as pre-existing without fresh base-vs-head evidence.

# Delivery discipline

Each PR records:

- exact commit;
- exact command argv;
- exit code;
- generated XML path/timestamp;
- failing/passing test counts;
- contract/UAT IDs covered;
- rollback switch/adapter;
- persisted schema impact.
