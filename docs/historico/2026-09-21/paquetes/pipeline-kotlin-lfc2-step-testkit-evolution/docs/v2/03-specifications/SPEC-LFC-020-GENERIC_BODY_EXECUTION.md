# SPEC-LFC-020 — Generic body execution

**Status:** proposed

## Scope

Aplica a:

- dir
- withEnv
- timestamps
- withCredentials
- retry
- timeout
- catchError
- warnError
- parallel
- futuros block plugins

## Representation

Todos son `ExecutionNode.Invoke`.

Retry:

```kotlin
Invoke(
    step = StepKey("core.retry"),
    input = encode(RetryInput(...)),
    bodies = StepBodies.Single(nodes),
)
```

Parallel:

```kotlin
Invoke(
    step = StepKey("core.parallel"),
    input = encode(ParallelInput(joinPolicy = ALL_COMPLETE)),
    bodies = StepBodies.Named(branches),
)
```

## Context patches

```kotlin
sealed interface ExecutionContextPatch {
    data object None : ExecutionContextPatch
    data class Environment(val overlay: EnvironmentOverlay) : ExecutionContextPatch
    data class Workspace(val path: WorkspacePath) : ExecutionContextPatch
    data class Credentials(val lease: CredentialLease) : ExecutionContextPatch
    data class Deadline(val deadline: DeadlineValue) : ExecutionContextPatch
    data class Composite(val patches: NonEmptyList<ExecutionContextPatch>) : ExecutionContextPatch
}
```

Nunca modificar global user.dir/global env.

## BodyInvoker guarantees

Cada child:

- registry resolution;
- capability admission;
- durable ID;
- normal event/output path;
- normal replay;
- inherited cancellation;
- parent context restored.

## Retry semantics

```kotlin
data class RetryInput(
    val maxAttempts: PositiveInt,
    val delay: RetryDelay = RetryDelay.None,
)
```

- un StepId lógico;
- AttemptId distintos;
- maxAttempts incluye primer intento;
- cancel no se convierte en retry;
- retryability tipada.

## Timeout semantics

Input durable contiene duration/activity.

Deadline usa Clock capability.

Timeout cancela descendientes.

## Parallel semantics

- stable BranchName;
- genuine overlap;
- typed join policy;
- branch-aware IDs;
- typed fail/cancel policy;
- replay/resume correcto;
- siblings seriales permitidos.

## Migration order

1. dir
2. withEnv
3. timestamps
4. withCredentials
5. retry
6. timeout
7. catchError/warnError
8. parallel
