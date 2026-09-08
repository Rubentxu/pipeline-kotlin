# ADR-LFC-020 — Examples are executable specifications

**Status:** proposed

## Context

Los examples actuales son útiles pero no expresan de forma homogénea:

- IR esperado;
- eventos;
- stdout/stderr;
- efectos de filesystem;
- replay;
- fallos;
- requirements.

La documentación puede divergir si el test reimplementa el ejemplo.

## Decision

Todo ejemplo no trivial se representa como Scenario ejecutable.

La regla principal:

> El `.pipeline.kts` mostrado/documentado es exactamente el fichero que ejecuta el harness.

Layout objetivo:

```text
examples/20-nested-env/
  pipeline.pipeline.kts
  scenario.yaml
  expected/
    ir.json
    events.json
    stdout.txt
    stderr.txt
    filesystem.json
    replay.json
```

## Inventories

No colapsar responsabilidades:

- `examples/` — ejemplos pedagógicos.
- `v2/compatibility/` — corpus Jenkins/DSL positivo y negativo.
- `test-fixtures/` — escenarios internos adversariales.
- `plugin-fixtures/` — builds externos reales.

Todos pueden compartir ScenarioRunner.

## Negative corpus

Los programas inválidos son first-class fixtures:

- receiver ilegal;
- runtime-valued API en declarative;
- body shape inválido;
- retry/timeout no positivos;
- plugin desconocido;
- capability ausente;
- schema incompatible.

## Consequence

Los examples pasan de documentación ilustrativa a especificación ejecutable de producto.
