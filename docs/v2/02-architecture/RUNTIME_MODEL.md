# Runtime Model — Durable Kotlin without CPS

## Problema

Queremos conservar Kotlin normal dentro de `script {}`:

```kotlin
val branch = sh(script = "git branch --show-current", returnStdout = true).stdout.trim()
if (branch == "main") deploy()
```

Un plan 100% estático no puede conocer el resultado de `sh` y serializar continuations Kotlin nos llevaría a reconstruir un CPS propio.

## Decisión

El runtime usa **deterministic durable replay**.

### Primera ejecución

```text
1 PipelineStarted
2 DurableOperationScheduled(op=sh#1)
3 DurableOperationStarted(sh#1)
4 DurableOperationCompleted(sh#1, stdout="main")
5 DurableOperationScheduled(op=deploy#2)
6 ...
```

### Recovery

El script vuelve a empezar. Al llegar a `sh#1`, el runtime calcula su logical operation key, encuentra un resultado confirmado compatible y lo devuelve sin relanzar el proceso. La ejecución vuelve a tomar la misma rama.

## Determinism contract

El replay sólo es correcto si:
- el orden lógico de durable operations es estable para la misma historia;
- inputs que afectan control flow quedan representados en la historia;
- resultados no deterministas externos se registran como durable results;
- cambios de source/runtime/plugin incompatibles invalidan o fuerzan policy explícita.

## Effect taxonomy

```kotlin
enum class Effect {
    PURE,
    READ,
    PROCESS,
    FILESYSTEM,
    NETWORK,
    ARTIFACT_WRITE,
    EXTERNAL_MUTATION,
    DEPLOYMENT
}

enum class ReplayPolicy {
    REUSE_RESULT,
    REEXECUTE,
    SIMULATE,
    REQUIRE_APPROVAL,
    FORBIDDEN
}
```

## Frames

`parallel` crea frames run-local. Cada frame posee logical operation sequence/cursor y cancellation scope. Al converger, el run continúa con un join explícito.

## Forks

Un fork crea un run nuevo a partir de un prefix del event log. V2.0 limita fork seguro a:
- operaciones reutilizables;
- builds sin mutaciones externas irreversibles;
- o steps con simulación/aprobación explícita.

## Cambio de código durante recovery

No se permite recovery transparente con un pipeline digest distinto. Opciones:
1. continuar con artifact compilado original;
2. crear fork/migration explícita;
3. fallar con `ReplayCompatibilityError`.

## Durable task

Para procesos largos:

```text
TaskId
Process/ContainerId
WorkerId
WorkspaceRef
StartedAt
StdoutOffset
StderrOffset
State
```

Si el worker reinicia pero conserva runtime local/pod/container, intenta reattach. Si desapareció el Pod, la policy decide retry en un nuevo Attempt; no se finge que el proceso anterior continúa.
