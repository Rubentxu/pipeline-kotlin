# Readfile

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `output`

**Expected outcome:** `success`

## What it exercises

The output plane: what a step produced, and how it is read back

*The example says this about itself:*

> Core.readFile live fixture. Writes a file with the certified
> writeFile substrate, reads it back through core.readFile (FileRead event,
> content never enters the event channel), then checks existence via
> core.fileExists (missing file is a legitimate false, not a failure).

## The stages and the steps

### 1. `readfile-roundtrip`

- `writeFile` — `"lpr104-readme.txt", "hello-lpr-104"`
- `readFile` — `lpr104-readme.txt`
- `fileExists` — `lpr104-readme.txt`
- `fileExists` — `lpr104-missing.txt`
- `echo` — `ROUNDTRIP-OK`
- `sh` — `test -f lpr104-readme.txt && grep -q hello-lpr-104 lpr104-readme.txt`
- `sh` — `test ! -e lpr104-missing.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/output/readfile/readfile.pipeline.kts
pipelinek run      examples/catalogue/features/output/readfile/readfile.pipeline.kts
```

## Its lineage

- **Historical name:** `23-readfile.pipeline.kts`
- **Origin path:** `examples/output/23-readfile.pipeline.kts`
- **Body digest:** `sha256:de2224e8eecdedd76480c3e2d811b078081efb9fcfdcf821fa02eb225b541db3`
