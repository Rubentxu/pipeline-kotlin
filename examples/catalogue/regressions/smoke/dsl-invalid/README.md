# Dsl Invalid

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** 

**Expected outcome:** `failure`

## What it exercises

An example the catalogue declares without a category

*The example says this about itself:*

> Fixture: DSL malformado a propósito. El pipeline debe fallar con
> diagnostics tipados y sin efectos en el workspace.
> Este fichero NO es DSL válido: usa un step inexistente y un bloque sin cerrar.

## The stages and the steps

### 1. `broken-stage`

- `stepInexistente` — `esto no existe en ninguna version` — a step this example declares

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/dsl-invalid/dsl-invalid.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/dsl-invalid/dsl-invalid.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

**Covers:** `HAR-015`

## Its lineage

- **Historical name:** `dsl-invalid.pipeline.kts`
- **Origin path:** `scenarios/smoke/dsl-invalid.pipeline.kts`
- **Body digest:** `sha256:45aa1b3d031a09605507487947fa7414bbc8a7843be73c3e8c09608f84fa25cc`
