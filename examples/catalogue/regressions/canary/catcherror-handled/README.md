# Catcherror Handled

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `process`, `shell`, `cross_stage`

**Expected outcome:** `unstable`

## What it exercises

The life of a run: how it starts, how it ends, and what it concludes

*The example says this about itself:*

> The subject is a CAUGHT failure: the run continues, and the run's
> outcome says so.

## The stages and the steps

### 1. `handled`

- `catchError` — a block of recovery steps
  - `sh` — `exit 1`
- `sh` — `mkdir -p /workspace/out && printf 'caught\\n' > /workspace/out/canary-caught.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `unstable` |
| files | `out/canary-caught.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/catcherror-handled/catcherror-handled.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/catcherror-handled/catcherror-handled.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `catcherror-handled.pipeline.kts`
- **Origin path:** `scenarios/canary/catcherror-handled.pipeline.kts`
- **Body digest:** `sha256:473b5721efd7e67905ea7b0fb2d536fdcbe5ab611c7293fff5ac38437be58864`
