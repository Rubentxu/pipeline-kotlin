# Stdout Channel

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `process`, `shell`

**Expected outcome:** `success`

## What it exercises

The life of a run: how it starts, how it ends, and what it concludes

*The example says this about itself:*

> The subject is the CHANNEL: what a step prints has to reach the run's
> stdout, and the oracle has to be able to see it there.

## The stages and the steps

### 1. `stdout`

- `sh` — `printf 'CANARY-STDOUT-MARKER\\n'`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `CANARY-STDOUT-MARKER` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/stdout-channel/stdout-channel.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/stdout-channel/stdout-channel.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `stdout-channel.pipeline.kts`
- **Origin path:** `scenarios/canary/stdout-channel.pipeline.kts`
- **Body digest:** `sha256:162b32c4e9d3e916e2c89e932ac6defc8226cdc3433b63a365500491b40fca2f`
