# Execution Model Design Summary

## North star

Pipeline Kotlin should feel like Jenkins Pipeline to a Jenkins user while remaining a
Kotlin-native, local-first, durable engine.

The key architectural sentence is:

> **Declarative structure is data; scripted flow is executable Kotlin; durability is
> deterministic replay of recorded step effects.**

## Layers

```text
.pipeline.kts
   │
   ├─ Declarative builder ───────────► static manifest/IR
   │
   └─ scripted Kotlin entry point
              │
              ▼
        generated step façade
              │
              ▼
          StepInvoker
              │
      StepExecutionBoundary
              │
        Replay / Journal
          │          │
       reuse       execute
          │          ▼
          │      StepHandler
          │          │
          └──────────┤
                     ▼
              capabilities
                     │
                     ▼
               DurableTask
```

## The shell slice

```text
sh(...)
  ↓
ShellCommand
  ↓
StepExecutionBoundary
  ↓
journal lookup
  ↓
DurableShellHandler
  ↓
DurableTaskTerminal
  ↓
Shell semantic classifier
  ├─ Unit
  ├─ String
  ├─ Int
  └─ typed exception
```

## Critical semantic example

```kotlin
val rc = sh(script = "exit 7", returnStatus = true)
```

Child process failure? No: the process exited 7.

Step contract failure? Also no: the caller explicitly requested the status.

Therefore:

```text
ProcessExited(7) = low-level fact
StepSucceeded(return=7) = Pipeline semantic result
```

This distinction is foundational.

## The restart slice

First run:

```text
script@L10 starts
sh@L11 → schedule → launch → exit 0/stdout=main → persist
Kotlin receives "main"
if main → sh@L15 launch
runtime dies
```

Recovery:

```text
load identical artifact
script@L10 starts
sh@L11 → journal hit → return "main" (no launch)
if main → sh@L15 → reconcile/replay according to journal/task state
```

No continuation serialized.

## The body-step slice

```text
timeout
  body
    retry
      attempt 1
        withEnv
          sh
      attempt 2
        withEnv
          sh
```

Each scope is first-class and contributes to:

- context;
- cancellation;
- operation identity;
- lifecycle;
- replay.

No block is flattened into shell text.

## What can remain stable

- local durable process mechanics;
- event/journal persistence;
- workspace model;
- credentials store/redaction;
- plugin descriptors;
- source/plugin digests;
- local-first deployment.

## What should no longer constrain the design

- `runShellCommand(): String` as internal authority;
- StepSpec-only runtime;
- marker/shell rewrites for control flow;
- timeout as a shell option;
- fabricated SCHEMA classification for running states;
- inability to change pipeline-step-sdk when it drops required semantics.

## Implementation philosophy

Prefer types that make illegal states unrepresentable.

Prefer one semantic owner per responsibility.

Prefer behavioral compatibility tests over prose claims.

Prefer additive persistence migration.

Prefer a failed recovery over duplicated external side effects.
