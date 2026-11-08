# Dir Nested

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `dir`, `nested`, `shell`, `cross_stage`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> dir nested + read/write/fileExists con hashes.
> Escribe en subdirectorios anidados via dir(), verifica lectura y existencia.

## The stages and the steps

### 1. `dir-nested`

- `dir(a/b/c)` — a directory block
  - `sh` — `printf 'payload-har006\\n' > payload.txt`
- `dir(a/b/c)` — a directory block
  - `sh` — `"cat payload.txt | grep -q payload-har006 && test -f payload.txt && mkdir -p /workspace...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `a/b/c/payload.txt`, `out/marker.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/dir-nested/dir-nested.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/dir-nested/dir-nested.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

**Covers:** `HAR-006`

## Its lineage

- **Historical name:** `har006-dir-nested.pipeline.kts`
- **Origin path:** `scenarios/smoke/har006-dir-nested.pipeline.kts`
- **Body digest:** `sha256:72d077e66250903c402eb29ae44cc995a9bef359e44edd84d7c172e3d5e72ff7`
