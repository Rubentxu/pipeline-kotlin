# ADR-LFC-021 — Layered Test Harness fidelity

**Status:** proposed

## Context

Los tests in-process son rápidos, pero pueden esconder errores reales de:

- classpath;
- ServiceLoader;
- plugin discovery;
- proceso;
- señales;
- restart;
- sandbox.

Ejecutar todo en contenedores sería demasiado caro.

## Decision

`pipeline-testkit` define niveles explícitos.

| Level | Name | Boundary | Purpose |
|---|---|---|---|
| T0 | Pure Contract | sin proceso | ADTs, codecs, validation |
| T1 | PipelineExtension | in-process | DSL/IR/handler integration |
| T2 | RealPipelineExtension | distribución forked | CLI, classpath, plugin loading |
| T3 | PipelineSessionExtension | kill/restart | journal, replay, resume |
| T4 | SandboxPipelineExtension | Podman/hardened | aislamiento/security |
| T5 | Service Sandbox | T4 + servicios | Git/HTTP/DB/artifacts |
| T6 | Online Smoke | OSS/red | compatibilidad ecosistema |

Usar siempre el nivel mínimo que pruebe fielmente la propiedad.

## Isolation

Cada ejecución recibe:

- workspace único;
- control root;
- output/event/journal stores;
- credential store;
- plugin dir;
- run ID;
- process group.

Teardown mata descendientes y conserva diagnósticos antes de limpiar.

## Determinism

Clock, IDs, randomness y failure injection deben poder sustituirse en T0/T1 cuando el tiempo real no es objeto del test.
