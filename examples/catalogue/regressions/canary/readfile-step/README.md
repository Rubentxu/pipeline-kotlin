# Readfile Step

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `file_io`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> The subject is the READ side of the file steps: 'readFile' and
> 'fileExists', in BOTH polarities.

## The stages and the steps

### 1. `roundtrip`

- `sh` — `mkdir -p out && printf 'CANARY-READFILE\\n' > out/canary-readfile.txt`
- `readFile` — `out/canary-readfile.txt`
- `fileExists` — `out/canary-readfile.txt`
- `fileExists` — `out/canary-absent.txt`
- `sh` — `grep -qx CANARY-READFILE out/canary-readfile.txt && test ! -e out/canary-absent.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/canary-readfile.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/readfile-step/readfile-step.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/readfile-step/readfile-step.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `readfile-step.pipeline.kts`
- **Origin path:** `scenarios/canary/readfile-step.pipeline.kts`
- **Body digest:** `sha256:39aed92096215e2cf85719da18b75e7d13814ac1c6104278c260828c561fbf7d`
