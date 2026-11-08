# Writefile Step

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `file_io`, `cross_stage`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> The subject is the WRITE step of the DSL, and its guarantee is that what
> it writes is there to be read BACK, in a DIFFERENT stage.

## The stages and the steps

### 1. `write`

- `writeFile` — `file = "out/canary-writefile.txt", text = "CANARY-WRITEFILE"`

### 2. `read-back`

- `sh` — `grep -qx CANARY-WRITEFILE out/canary-writefile.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/canary-writefile.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/writefile-step/writefile-step.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/writefile-step/writefile-step.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `writefile-step.pipeline.kts`
- **Origin path:** `scenarios/canary/writefile-step.pipeline.kts`
- **Body digest:** `sha256:acfc614dd8182d1ae4bd334697f734c7a3ab8ca2db45fddee68a2a14ad65d514`
