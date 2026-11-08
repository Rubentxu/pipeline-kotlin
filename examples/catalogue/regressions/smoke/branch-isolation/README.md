# Branch Isolation

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `process`, `shell`, `cross_stage`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> Ramas contextuales aisladas + side effects caracterizados.
> parallel con dos branch(): ambos se ejecutan (aislados, identidad
> b0:/b1: en journal) y cada uno deja su side effect marcado en out/.

## The stages and the steps

### 1. `branch-iso`

- `parallel` — a parallel block
  - `branch(left)` — a branch of a parallel block
    - `sh` — `mkdir -p /workspace/out && printf 'left-taken\\n' > /workspace/out/branch-left.txt`
  - `branch(right)` — a branch of a parallel block
    - `sh` — `mkdir -p /workspace/out && printf 'right-taken\\n' > /workspace/out/branch-right.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/branch-left.txt`, `out/branch-right.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/branch-isolation/branch-isolation.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/branch-isolation/branch-isolation.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `har008-branch-isolation.pipeline.kts`
- **Origin path:** `scenarios/smoke/har008-branch-isolation.pipeline.kts`
- **Body digest:** `sha256:0a7206141216ab2e538aa50d0663fbd4a8c5e0159b025a8e0136ebb88f840d97`
