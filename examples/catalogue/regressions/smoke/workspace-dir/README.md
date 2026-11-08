# Workspace Dir

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `dir`, `nested`, `shell`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> dir() subdirectory semantics + effective cwd coherence.
> Enters a subdir via dir(), writes through sh, verifies fileExists there.

## The stages and the steps

### 1. `dir-contract`

- `dir(nested/deep)` — a directory block
  - `sh` — `mkdir -p out && pwd > out/pwd.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `nested/deep/out/pwd.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/workspace-dir/workspace-dir.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/workspace-dir/workspace-dir.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `workspace-dir.pipeline.kts`
- **Origin path:** `scenarios/smoke/workspace-dir.pipeline.kts`
- **Body digest:** `sha256:962026f808eb752ba8f416fff4c239238cf46925a3bad4ad5a36ad3e5913438c`
