# Workspace Contract

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `dir`, `process`, `shell`, `cross_stage`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> Regression canary for the workspace contract (the fix shipped in 0.39.1-rc1).
> Verifies that sh() runs with cwd == /workspace (the workspace contract).

## The stages and the steps

### 1. `workspace-contract`

- `sh` — `"mkdir -p out && (pwd > out/pwd.txt; grep -qx /workspace out/pwd.txt && printf 'cwd-ok\...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/pwd.txt`, `out/cwd-marker.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/workspace-contract/workspace-contract.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/workspace-contract/workspace-contract.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

**Covers:** `HAR-004`

## Its lineage

- **Historical name:** `workspace-contract.pipeline.kts`
- **Origin path:** `scenarios/smoke/workspace-contract.pipeline.kts`
- **Body digest:** `sha256:df522da9447e8adc72569d58f22c2d250fe923831c8176cb7ec5e3f7eeed054f`
