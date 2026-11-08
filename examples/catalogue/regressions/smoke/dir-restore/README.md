# Dir Restore

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `dir`, `process`, `shell`

**Expected outcome:** `failure`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> dir restaura cwd tras error. El primer dir() falla (exit 1),
> el segundo dir() debe partir del workspace raiz (no del subdirectorio
> fallido) y verificarlo escribiendo pwd.

## The stages and the steps

### 1. `dir-restore`

- `dir(errdir)` — a directory block
  - `sh` — `exit 1`
- `dir(chk)` — a directory block
  - `sh` — `pwd | grep -qx /workspace && printf 'cwd-restored\\n' > /workspace/out/marker.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/dir-restore/dir-restore.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/dir-restore/dir-restore.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `har007-dir-restore.pipeline.kts`
- **Origin path:** `scenarios/smoke/har007-dir-restore.pipeline.kts`
- **Body digest:** `sha256:9ce90d610d6727110d687b5d6049a72dddf0b0e56696092ee4139b5938ef501c`
