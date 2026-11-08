# Branch Failure Reported

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `process`, `shell`, `cross_stage`

**Expected outcome:** `failure`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> The subject is what a FAILED branch leaves behind: the side effect it
> produced before failing stays, and the failure is named.

## The stages and the steps

### 1. `branches`

- `parallel` — a parallel block
  - `branch(left)` — a branch of a parallel block
    - `sh` — `mkdir -p /workspace/out && printf 'left\\n' > /workspace/out/canary-branch-left.txt`
  - `branch(right)` — a branch of a parallel block
    - `sh` — `"mkdir -p /workspace/out && printf 'right\\n' > /workspace/out/canary-branch-right.txt...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| files | `out/canary-branch-right.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/branch-failure-reported/branch-failure-reported.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/branch-failure-reported/branch-failure-reported.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `branch-failure-reported.pipeline.kts`
- **Origin path:** `scenarios/canary/branch-failure-reported.pipeline.kts`
- **Body digest:** `sha256:302da316e6c6fb4a33bb2d86910279d64357d62cd0f988782a13c0426ba2739f`
