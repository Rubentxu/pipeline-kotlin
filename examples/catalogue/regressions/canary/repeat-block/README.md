# Repeat Block

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `plugin`, `process`, `shell`

**Expected outcome:** `success`

## What it exercises

Steps that come from a plugin, and the capabilities they declare

*The example says this about itself:*

> The subject is a COUNT: the body of a repeated block runs exactly as
> many times as the block was asked for.

## The stages and the steps

### 1. `repeat`

- `repeatBlock(2)` — a repeated block (`example-block-plugin`)
  - `sh` — `echo iteration | tee -a iterations.txt`
- `sh` — `"test \"$(wc -l < iterations.txt)\" -eq 2"`
- `sh` — `mkdir -p /workspace/out && cp iterations.txt /workspace/out/canary-iterations.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/canary-iterations.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- el plugin de bloque repetible (example.repeat) viene en la imagen de la candidata

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/repeat-block/repeat-block.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/repeat-block/repeat-block.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `repeat-block.pipeline.kts`
- **Origin path:** `scenarios/canary/repeat-block.pipeline.kts`
- **Body digest:** `sha256:20d70e707f2d1fa529428df8cc14a28f90f3106157b597aa2ed84523393b7edc`
