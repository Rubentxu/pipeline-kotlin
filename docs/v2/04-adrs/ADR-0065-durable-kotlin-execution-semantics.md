---
type: adr
id: ADR-0065
title: "Durable Kotlin execution uses runtime step invocation plus deterministic replay"
status: accepted
date: 2026-09-05
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0046
  - ADR-0047
  - ADR-0064
  - docs/v2/02-architecture/RUNTIME_MODEL.md
  - docs/v2/03-specifications/DSL_SPEC.md
  - docs/v2/03-specifications/STEP_PLUGIN_SDK.md
  - docs/v2/05-roadmap/EXECUTION_MODEL_MIGRATION.md
  - docs/v2/07-uat/UAT_JENKINS_EXECUTION_PARITY.md
---

# ADR-0065 — Durable Kotlin execution uses runtime step invocation plus deterministic replay

## Context

Pipeline Kotlin aims to preserve the useful mental model and observable behavior of
Jenkins Pipeline while using Kotlin as the authoring language and avoiding a Jenkins
controller/CPS dependency.

The original V2 architecture already identified the key constraint: a fully static
plan cannot represent control flow that depends on runtime step values such as:

```kotlin
script {
    val branch = sh(
        script = "git branch --show-current",
        returnStdout = true,
    ).trim()

    if (branch == "main") {
        deploy()
    }
}
```

The intended solution was deterministic durable replay: on recovery the same compiled
program starts again, previously completed durable operations return their recorded
results, and the same control-flow decisions are reconstructed.

Subsequent implementation introduced a stronger static `StepSpec` builder model.
That model is useful for Declarative structure, but it has become responsible for
runtime semantics too. Symptoms include:

- `sh(returnStdout=true)` in the builder cannot return the runtime value;
- `pwd`, `readFile`, `fileExists`, `isUnix`, etc. cannot be truthful runtime APIs;
- `script {}` may be reduced to shell text;
- `catchError`/`warnError` are rewritten to shell composition;
- retry may be attached to a previously built node rather than executing a body;
- durable task states and terminal results are conflated;
- `runShellCommandTyped` loses durable information through a legacy string projection;
- failure provenance is discarded below the application layer;
- shell-local timeout semantics conflict with Jenkins block-level interruption.

INC-039 surfaced this mismatch because application code is asked to preserve a
failure cause that the SDK has already discarded.

## Decision

### D1 — Separate Declarative discovery from Scripted execution

The Declarative skeleton remains statically discoverable and serializable:

- pipeline/stage names;
- agents;
- environment declarations;
- options;
- static `when` clauses;
- post conditions;
- statically declared atomic/block steps where analysis requires them.

Dynamic `script {}` is executable Kotlin:

```kotlin
fun script(block: suspend ScriptedScope.() -> Unit)
```

The block is compiled as part of an immutable pipeline artifact and executed by the
runtime. It is not converted to shell text and its continuation is not serialized.

### D2 — Durability is deterministic replay, not Kotlin continuation persistence

Recovery reloads the exact compatible pipeline artifact and evaluates from a stable
entry point.

Every effectful step invocation computes a deterministic operation identity. Before
executing an effect, the runtime consults the journal:

- compatible completed operation → return recorded value/failure;
- compatible running durable task → reconcile/reattach;
- new operation → persist schedule/inputs, then execute;
- incompatible history/source/plugin digest → fail closed.

Kotlin local variables and control structures are reconstructed by re-execution.

### D3 — Public step façades are runtime calls

A scripted step returns its Jenkins-compatible value or throws a typed Pipeline
exception.

`StepOutcome` and `RunOutcome` remain useful as persisted/external representations,
but are not the primary control-flow mechanism between nested runtime steps.

### D4 — Preserve Jenkins observable surface; improve internals with Kotlin types

Jenkins parameter names/defaults and observable behavior are the compatibility
contract when a step is declared Jenkins-compatible.

Kotlin may use overloads, value classes, enums and generated façades to avoid `Any`
without inventing new Jenkins parameters.

Non-Jenkins richer APIs must use a distinct name/namespace and be documented as a
Pipeline Kotlin extension.

### D5 — `sh` is not the owner of timeout or environment scopes

Jenkins-compatible `sh` owns:

- script;
- encoding;
- label;
- return status behavior;
- return stdout behavior.

Timeout belongs to `timeout {}` / declarative options. Environment overlays belong to
`withEnv {}` / declarative environment. Credentials belong to `withCredentials {}`.

Legacy `timeoutMs`/`env` shell parameters may survive temporarily as deprecated
adapters but must not define the canonical model.

### D6 — Separate durable task snapshots from terminal task results

Intermediate task lifecycle (`launching`, `running`, `disconnected/reconciling`) is
not a step result.

The API must distinguish:

```text
DurableTaskSnapshot       DurableTaskTerminal
------------------        -------------------
Launching                 Exited
Running                   LaunchFailed
Disconnected              Lost
Reconciling               Cancelled
```

An await/execute API returning `DurableTaskTerminal` cannot return `RUNNING` by
construction.

### D7 — Structured durable failures cross module boundaries

The durable SDK must not discard failure provenance required by application semantics.

Persisted/wire-safe failure information uses a serializable `FailureRecord`.
The original in-process `Throwable`, when present, is retained only as diagnostic
context and is not the durable protocol.

This ADR therefore permits a narrow, versioned change to `:pipeline-step-sdk`.
The earlier "INC-039 must not touch pipeline-step-sdk" constraint is superseded for
the execution-model migration.

### D8 — Block steps are first-class

Steps that execute a body are modeled explicitly rather than shell rewrites or marker
sequences:

- timeout
- retry
- catchError
- warnError
- withEnv
- withCredentials
- dir
- timestamps
- ansiColor
- node
- future plugin block steps.

Their execution receives a body handle/scope and can invoke it zero, one or multiple
times.

### D9 — Interruptions are distinct from ordinary failures

Timeout and user abort are flow interruptions with structured causes.

They are not ordinary `SCRIPT`/`INFRASTRUCTURE` failures and must be catchable or
rethrowable according to the enclosing block-step contract.

### D10 — One boundary owns step lifecycle events

A central `StepExecutionBoundary` is responsible for:

- `StepStarted`;
- invocation;
- success/failure/interruption classification;
- `StepFailed` when the step contract fails;
- `StepFinished`;
- duration/operation metadata.

Individual executors do not independently emit conflicting step lifecycle events.

A non-zero shell exit under `returnStatus=true` is **not** a step failure.

### D11 — Engine invariants are not schema errors

`SCHEMA` is reserved for invalid/incompatible input payload/schema.

Impossible engine states use a dedicated invariant exception and `ENGINE`/`INTERNAL`
classification. Scope-stack leak exceptions retain their existing meaning until
migrated deliberately.

### D12 — ADR-0046 durable shell mechanics remain authoritative unless explicitly amended

This ADR preserves ADR-0046 D2/P1/P2: script file, fixed wrapper, result/log/output
files, heartbeat, cookie, detach and reattach.

What changes is the semantic API above that substrate.

## Rejected alternatives

### A — Patch only `runShellCommandTyped`

Rejected as final architecture. It can repair a symptom but preserves lossy legacy
projection and cannot solve scripted return values, block steps or interruption.

### B — Serialize Kotlin continuations/CPS

Rejected. High compiler/runtime coupling, difficult upgrade path and unnecessary given
the replay model.

### C — Build the entire pipeline as a static AST

Rejected as the only execution model. It is incompatible with normal Kotlin control
flow driven by runtime values unless a second interpreter/CPS is introduced.

### D — Use `Any?` to exactly mimic Groovy dynamic return types

Rejected for the public Kotlin API. Generated/handwritten overloads can preserve
Jenkins calling style while keeping static types.

### E — Keep exceptions out of runtime control flow

Rejected. Nested `retry`/`catchError`/`timeout` composition maps naturally to typed
exceptions/interruption, while outcomes remain suitable at persistence/API
boundaries.

## Consequences

### Positive

- Jenkins-like scripted behavior becomes implementable without CPS.
- Runtime-returning steps become truthful Kotlin APIs.
- restart/replay has one explicit model;
- block-step semantics become composable;
- durable failure provenance stops being fabricated or lost;
- future remote workers can transport `FailureRecord` without Java exception
  serialization;
- `sh`, `bat`, `powershell`, `pwsh` can share a generic durable process substrate;
- testability improves because state/result classifiers become pure and typed.

### Negative

- changes several contracts currently considered stable;
- requires a controlled change to `pipeline-step-sdk`;
- canonical IR needs a first-class block/script representation;
- some green tests encode transitional behavior and must be replaced, not weakened;
- persisted schema compatibility needs explicit handling;
- deterministic replay places constraints on arbitrary external effects inside
  `script {}`.

## Compatibility policy

No existing behavior is preserved merely because it is implemented.

A behavior is preserved if at least one is true:

1. it is part of the accepted V2 product contract;
2. it is Jenkins observable behavior at the declared compatibility level;
3. a migration/compatibility adapter is required by an accepted ADR;
4. persisted history requires an explicit compatibility period.

## Acceptance gate

ADR-0065 may be accepted before implementation, but broad production refactoring must
not begin until SPIKE-016 passes all mandatory exit criteria.

## Rollback

Before each EM migration gate, retain an adapter allowing the old dispatcher path to
be selected for existing static pipelines. Do not reinterpret journal history.

If deterministic scripted replay cannot prove stable operation identity, stop at the
last accepted EM gate and keep scripted runtime experimental.
