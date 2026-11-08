# Failure

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `process`, `shell`

**Expected outcome:** `failure`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> Negative twin: expected shell failure, PipelineK should exit 1 with SCRIPT failure.

## The stages and the steps

### 1. `intentional-failure`

- `sh` — `exit 7`

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
pipelinek validate examples/catalogue/regressions/smoke/failure/failure.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/failure/failure.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `failure.pipeline.kts`
- **Origin path:** `scenarios/smoke/failure.pipeline.kts`
- **Body digest:** `sha256:5d306a88010684f97843379478b2ce09142212a3560299588b1cd902e8d7e317`
