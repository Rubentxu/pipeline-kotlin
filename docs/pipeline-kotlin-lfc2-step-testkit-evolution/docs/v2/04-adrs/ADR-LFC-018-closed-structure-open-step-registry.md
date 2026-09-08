# ADR-LFC-018 — Closed execution structure, open Step registry

**Status:** proposed

## Context

La implementación actual necesita simultáneamente dos propiedades:

1. el intérprete debe conocer exhaustivamente la **estructura** de ejecución;
2. el catálogo de Steps debe permanecer **abierto** a terceros.

Una jerarquía sealed con cada Step concreto hace cómodo el `when`, pero obliga a editar core para cada plugin. Un modelo completamente dinámico elimina esa restricción pero degrada el tipado.

La solución adoptada sigue el principio funcional:

> closed world for interpreter structure, open world for plugin operations.

## Decision

El IR ejecutable usa un ADT estructural cerrado y un `StepRegistry` abierto.

Forma conceptual:

```kotlin
@JvmInline
value class StepKey(val value: String)

sealed interface ExecutionNode {
    data class Invoke(
        val id: StepId,
        val step: StepKey,
        val input: EncodedInput,
        val bodies: StepBodies,
        val source: SourceLocation?,
    ) : ExecutionNode
}

sealed interface StepBodies {
    data object None : StepBodies
    data class Single(val nodes: List<ExecutionNode>) : StepBodies
    data class Named(val branches: List<NamedBody>) : StepBodies
}
```

Los nombres concretos pueden variar, pero los invariantes no.

## Invariants

- El engine interpreta exhaustivamente un conjunto finito de estructuras.
- El engine NO hace `when` sobre clases/nombres de plugin Steps.
- `StepKey` resuelve una `StepDefinition`.
- El input se decodifica y valida antes de efectos.
- Unknown/incompatible Step falla closed.
- El body shape se valida contra el contrato registrado.

## StepDefinition

```kotlin
interface StepDefinition<I : Any, O : Any> {
    val key: StepKey
    val contract: StepContract
    val inputCodec: StepCodec<I>
    val outputCodec: StepCodec<O>
    val handler: StepHandler<I, O>
}
```

## Prohibido

- añadir un caso por Step a un dispatcher central;
- añadir un tipo sealed central para cada plugin;
- KSP con `when(stepName)` semántico;
- plugin que requiera cambios en domain/application/compiler/dispatcher;
- `Map<String, Any?>` como contrato público del Step.

## Validation

Se considera demostrado sólo cuando:

1. `echo` y `sh` usan `Invoke -> Registry -> Handler`;
2. el dispatcher central ya no conoce `echo`/`sh`;
3. un plugin externo ejecuta sin cambios de core;
4. unknown StepKey/schema mismatch falla antes de side effects.
