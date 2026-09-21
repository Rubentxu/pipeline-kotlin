# ADR-LFC-022 — Block Steps re-enter the engine through BodyInvoker

**Status:** proposed

## Context

`dir`, `withEnv`, `withCredentials`, `retry`, `timeout`, `catchError`, `warnError`, `timestamps` y `parallel` ejecutan programas hijos.

Crear un método específico por bloque sólo desplaza el switch concreto a otra capa.

## Decision

Un block Step nunca ejecuta child handlers directamente.

El engine entrega capabilities:

```kotlin
interface BodyInvoker {
    suspend fun invoke(
        body: BodyRef,
        patch: ExecutionContextPatch = ExecutionContextPatch.None,
    ): BodyOutcome
}

interface BranchInvoker {
    suspend fun invokeAll(
        branches: List<NamedBodyRef>,
        policy: JoinPolicy,
        patch: ExecutionContextPatch = ExecutionContextPatch.None,
    ): ParallelOutcome
}
```

Cada child reentra en:

`Invoke -> Registry -> capability admission -> handler -> journal/events`.

## Body contract

```kotlin
sealed interface BodyContract {
    data object None : BodyContract
    data class Single(val invocation: InvocationPolicy) : BodyContract
    data class Named(val invocation: InvocationPolicy) : BodyContract
}
```

## Mapping

- `dir` -> workspace patch.
- `withEnv` -> environment patch.
- `withCredentials` -> credential lease patch.
- `retry` -> múltiples invocaciones con AttemptId.
- `timeout` -> deadline/cancellation scope.
- `catchError` -> interpreta BodyOutcome tipado.
- `parallel` -> Named bodies + BranchInvoker.

## Parallel

`parallel` es composable, no permanentemente stage-terminal.

Puede existir con siblings seriales antes/después si la gramática lo permite.
