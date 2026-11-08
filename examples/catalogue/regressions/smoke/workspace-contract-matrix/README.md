# Workspace Contract Matrix

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `dir`, `nested`, `cross_stage`, `shell`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> Regresion externa del defecto "workspace contract" en PipelineK.
> Sintomas reportados antes de la rc1:
> sh() cwd no coincide con dir() block, o dir() no persiste cwd entre etapas.

## The stages and the steps

### 1. `dir-nested-3`

- `dir(a/b/c)` — a directory block
  - `sh` — `"mkdir -p /workspace/out && pwd > /workspace/out/wu-rp-053-cwd-a-b-c.txt && grep -q '/w...`

### 2. `read-cross-stage`

- `sh` — `"cat /workspace/a/b/c/file.txt > /workspace/out/wu-rp-053-readback.txt && grep -qx one...`

### 3. `parallel-branches`

- `parallel` — a parallel block
  - `branch(left)` — a branch of a parallel block
    - `sh` — `"mkdir -p /workspace/out && printf 'left-branch-payload\\n' > /workspace/out/wu-rp-053-...`
  - `branch(right)` — a branch of a parallel block
    - `sh` — `"mkdir -p /workspace/out && printf 'right-branch-payload\\n' > /workspace/out/wu-rp-053...`

### 4. `dir-reattach`

- `dir(x/y)` — a directory block
  - `sh` — `"pwd > /workspace/out/wu-rp-053-cwd-x-y.txt && grep -q '/workspace/x/y' /workspace/out/...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/wu-rp-053-cwd-a-b-c.txt`, `out/wu-rp-053-lines.txt`, `out/wu-rp-053-readback.txt`, `out/wu-rp-053-branch-left.txt`, `out/wu-rp-053-branch-right.txt`, `out/wu-rp-053-cwd-x-y.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/workspace-contract-matrix/workspace-contract-matrix.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/workspace-contract-matrix/workspace-contract-matrix.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `wu-rp-053-workspace-contract.pipeline.kts`
- **Origin path:** `scenarios/smoke/wu-rp-053-workspace-contract.pipeline.kts`
- **Body digest:** `sha256:9de75c6ee5931cc3e8f940c49fbb8c17cef733fe506f54535263bfb59fd89541`
