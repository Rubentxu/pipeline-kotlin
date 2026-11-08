# Toolchain Inventory

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `process`, `shell`

**Expected outcome:** `success`

## What it exercises

The life of a run: how it starts, how it ends, and what it concludes

*The example says this about itself:*

> The subject is the TOOLCHAIN the image provides: it has to be there,
> and the version it reports has to be recorded.

## The stages and the steps

### 1. `tools`

- `sh` — `"mkdir -p /workspace/out && python3 --version > /workspace/out/canary-python3.txt 2>&1...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/canary-python3.txt`, `out/canary-cargo.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- la imagen trae python3 y cargo (el canario falla si falta alguna)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/toolchain-inventory/toolchain-inventory.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/toolchain-inventory/toolchain-inventory.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `toolchain-inventory.pipeline.kts`
- **Origin path:** `scenarios/canary/toolchain-inventory.pipeline.kts`
- **Body digest:** `sha256:6d8c1e7c4108e12a19eba2912993ad5df8686f12a083d6173e2149ea80dd91b8`
