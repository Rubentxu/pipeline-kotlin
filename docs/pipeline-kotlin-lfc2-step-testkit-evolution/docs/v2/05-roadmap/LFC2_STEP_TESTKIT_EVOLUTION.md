# LFC-2 Step/TestKit Evolution — execution guide

## Why this is necessary

Step semantics atraviesan:

```text
DSL
 -> compiler
 -> canonical IR
 -> plugin metadata
 -> registry
 -> capability admission
 -> runtime handler
 -> body execution
 -> events/output
 -> journal/replay
 -> examples/UAT
```

El problema recurrente es permitir una feature en una capa cuando otra capa no puede representarla.

## Slice 0 — Freeze semantic expansion

Antes de añadir más Steps:

- no nuevos one-off dispatcher cases;
- unsupported semantics fail closed;
- preservar subset lineal que funciona.

## Slice 1 — Harness first

ScenarioRunner + PipelineExtension antes de grandes migraciones.

Así cada cambio arquitectónico tiene user-level executable proof.

## Slice 2 — Atomic generic seam

Sólo echo + sh.

- echo prueba caso simple;
- sh prueba process/output/failure/replay/capabilities.

## Slice 3 — External proof

No migrar decenas de Steps hasta demostrar plugin externo.

## Slice 4 — Certification

Convertir criterios en suite reusable.

## Slice 5 — Strict DSL

Con execution target estable:

- DslMarker;
- scopes;
- smart constructors;
- git;
- fake returns;
- source fidelity.

## Slice 6 — Body algebra

Primero context-only blocks:

- dir;
- withEnv;
- timestamps.

No empezar por parallel.

## Slice 7 — Retry/timeout

Añadir control-flow sobre BodyInvoker.

## Slice 8 — Parallel

Named bodies + BranchInvoker + durable branch machinery actual.

## Slice 9 — Scripted values / conditions / post

Una vez exista durable operation result genérico.

## Slice 10 — Formal scripting + final corpus

Cerrar @KotlinScript, source mapping y examples.

## Re-analysis triggers

Abrir spike/ADR si ocurre cualquiera:

1. external plugin necesita core edit;
2. handler necesita servicio global no declarado;
3. block handler necesita child handler directo;
4. IR necesita Step-specific structural node;
5. KSP necesita known-name semantics;
6. golden sólo pasa ocultando campos semánticos;
7. retry/timeout/parallel necesita bypass de journal/events;
8. TestKit necesita imports de production internals no públicos.
