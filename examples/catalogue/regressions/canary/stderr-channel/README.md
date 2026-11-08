# Stderr Channel

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `process`, `shell`

**Expected outcome:** `success`

## What it exercises

The life of a run: how it starts, how it ends, and what it concludes

*The example says this about itself:*

> The subject is the OTHER channel: the engine's own launch event has to
> reach stderr.

## The stages and the steps

### 1. `launch`

- `sh` — `printf 'CANARY-STEP-TEXT\\n'`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `CANARY-STEP-TEXT` |
| stderr contains | `pipelinek.launch/1` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/stderr-channel/stderr-channel.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/stderr-channel/stderr-channel.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `stderr-channel.pipeline.kts`
- **Origin path:** `scenarios/canary/stderr-channel.pipeline.kts`
- **Body digest:** `sha256:223a07251be3e7a83506a46dcad45637453479e60ba0ba48a28dcc7ae7cb653a`
