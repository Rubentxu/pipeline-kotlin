# ADR-LFC-019 — Core and external Steps share one execution path

**Status:** proposed

## Context

Una arquitectura de plugins no está demostrada si los Steps core siguen usando un camino privilegiado y sólo los plugins externos usan el registro genérico.

Ese diseño produce deriva en:

- replay;
- failure mapping;
- events;
- capabilities;
- cancellation;
- block semantics;
- output.

## Decision

Los Steps core se consideran un **standard bundled plugin set** desde el punto de vista de ejecución.

Ruta única:

```text
typed DSL façade
 -> canonical Invoke
 -> StepRegistry
 -> erased adapter
 -> typed StepHandler<Input, Output>
 -> context capabilities
 -> durable engine
 -> typed StepResult
 -> events/output/journal
```

El empaquetado core puede ser especial por distribución, pero NO por semántica.

## Required proof slice

Antes de migrar todo el catálogo:

1. migrar `echo`;
2. migrar `sh`;
3. borrar sus casos concretos del dispatcher;
4. crear plugin externo independiente `example.uppercase`;
5. construir su JAR contra artefactos SDK públicos;
6. cargarlo en distribución real;
7. compilar `.pipeline.kts`;
8. ejecutar y verificar result/events/replay.

Si el plugin externo necesita tocar core, el gate falla.
