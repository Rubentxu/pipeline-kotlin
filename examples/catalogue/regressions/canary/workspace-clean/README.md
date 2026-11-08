# Workspace Clean

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `file_io`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> The subject is an ABSENCE: 'cleanWs' removes the paths it matches, and
> what it does NOT match has to survive.

## The stages and the steps

### 1. `clean`

- `sh` — `"mkdir -p scratch && printf 'gone\\n' > scratch/gone.txt && printf 'kept\\n' > scratch/...`
- `cleanWs` — `patterns = listOf("scratch/gone.txt")`
- `sh` — `test ! -e scratch/gone.txt && test -e scratch/kept.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `scratch/kept.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/workspace-clean/workspace-clean.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/workspace-clean/workspace-clean.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `workspace-clean.pipeline.kts`
- **Origin path:** `scenarios/canary/workspace-clean.pipeline.kts`
- **Body digest:** `sha256:ebb70060552de015fe0c314faad21cdf2d1d91c13b1966d0deeed46e0a6ad364`
