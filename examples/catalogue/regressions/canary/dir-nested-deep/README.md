# Dir Nested Deep

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `dir`, `nested`, `cross_stage`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> The subject is the DEPTH of the working-directory contract: five levels
> of 'dir()', the file written at the deepest one, and the working
> directory RESTORED afterwards.

## The stages and the steps

### 1. `nested`

- `dir(a/b/c/d/e)` — a directory block
  - `sh` — `printf 'CANARY-DEEP\\n' > payload.txt`
- `dir(a/b/c/d/e)` — a directory block
  - `sh` — `cat payload.txt | grep -qx CANARY-DEEP`
- `sh` — `"pwd | grep -qx /workspace && mkdir -p /workspace/out && printf 'restored\\n' > /worksp...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `a/b/c/d/e/payload.txt`, `out/canary-dir-restore.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/dir-nested-deep/dir-nested-deep.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/dir-nested-deep/dir-nested-deep.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `dir-nested-deep.pipeline.kts`
- **Origin path:** `scenarios/canary/dir-nested-deep.pipeline.kts`
- **Body digest:** `sha256:ddfaf2fb19ed3334de4c60f927e88e7f3bcf20a2aec96934ddad5292c4fb49a5`
