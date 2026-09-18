# Arquitectura Local Production Ready

Status: PROPOSED

## 1. Target shape

```text
.pipeline.kts
    │
    ▼
Typed Declarative DSL ──────────────┐
    │                               │ scripted runtime values
    ▼                               ▼
Typed IR / ExecutionNode        ScriptRuntimeScope
    │                               │
    └──────────────┬────────────────┘
                   ▼
        CanonicalDurableRunCoordinator
                   │
        ┌──────────┴───────────┐
        ▼                      ▼
 InvocationEngine       BodyExecutionEngine
        │                      │
        ▼             ┌────────┼─────────┬──────────┐
 StepRegistry          ▼        ▼         ▼          ▼
 StepDefinition    Sequential Scoped   Retrying   Parallel
 Handler/Codec                             │
 Capabilities                         BodyInvoker
                                         │
                                 future RepeatUntil
```

El coordinator queda como dueño de run/stage lifecycle y delegación; no como implementation home de semantics concretas.

## 2. Closed structure / open semantics

La estructura de ejecución es un ADT cerrado y exhaustivo. La variación por Step se declara mediante `StepDefinition`, descriptor, contract, capability y BodyExecution policy.

Prohibido en coordinators/central dispatch:

```kotlin
when (stepKey.value) {
    "core.retry" -> ...
    "core.waitUntil" -> ...
}
```

Los plugins core y externos usan el mismo camino.

## 3. Generic body carrier

Para que la extensibilidad de bloques sea real, la invocación estructural debe poder portar bodies sin crear subtipos por plugin:

```kotlin
sealed interface StepBodies {
    data object None : StepBodies
    data class Single(val body: BodyRef) : StepBodies
    data class Named(val bodies: Map<BodyName, BodyRef>) : StepBodies
}
```

La representación concreta puede evolucionar alrededor del `BodyRef` de ADR-0081, pero las leyes son:

- body identity es durable/serializable;
- plugin handler no posee `List<StepNode>` ni lambdas no serializables;
- body re-entry solo ocurre mediante BodyInvoker/BranchInvoker;
- external body plugin no exige cambios en coordinator/compiler/core registry.

## 4. BodyExecutionEngine

La siguiente extracción arquitectónica prioritaria es un engine que interprete policies, no StepKeys:

```text
BodyExecutionPolicy
    Sequential
    Scoped(projection)
    Retrying(policy)
    Parallel(policy)
    [RepeatUntil(policy) cuando waitUntil se admita]
             │
             ▼
      BodyExecutionPlan
             │
             ▼
      BodyExecutionEngine
```

`RepeatUntil` se introduce cuando `waitUntil` sea consumidor real; no se obliga a cerrar waitUntil antes del primer LPR si no aporta al profile inicial.

## 5. InvocationEngine

Extracción 80/20 posterior/incremental:

- metadata/fingerprint;
- journal/replay/reconciliation;
- capability admission;
- common execution boundary;
- handler invocation;
- typed outcome.

No se requiere descomponer el coordinator en una docena de services antes del primer release. El gate es eliminar knowledge concreta y concentrar durable invocation en una seam testeable.

## 6. DSL architecture

Separación obligatoria:

```text
Declarative DSL             Scripted Runtime
---------------             ----------------
build typed IR              perform runtime invocation
no effects                  may return typed runtime values
no global state             explicit runtime capabilities
no fake values              durable/replay aware
```

`pwd()`/`isUnix()` y cualquier futura función runtime-returning solo puede producir un valor real dentro de una runtime continuation; la façade declarativa debe producir IR o rechazar la combinación.

## 7. Composition root

`Main.kt` puede conocer adapters concretos porque es composition root, pero no semantics de Step concretas. Debe construir/injectar:

- registry;
- event writer/history;
- journal;
- credential providers/redactors;
- process runtime;
- invocation/body engines;
- CLI projection/renderer.

`Main.kt` no debe contener `if core.foo` para ejecutar semantics.

## 8. Evolution rule

Cada extracción debe demostrar al menos una de estas mejoras medibles:

- elimina Step-specific knowledge;
- reduce connascence-of-change;
- mejora replay/determinism;
- reduce hot-path overhead;
- habilita un plugin externo sin core edit;
- elimina un fake runtime value;
- convierte una failure implícita en ADT/fail-closed.

No se introduce una abstraction solo por “limpieza” si no mejora uno de esos ejes.
