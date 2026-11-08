# Writefile In Warnerror

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `control-flow`

**Expected outcome:** `unstable`

## What it exercises

Which arm of a branch runs, and how many times the body of a loop runs

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `writefile-en-warnerror`

- `warnError(message = "aviso sin fallo")` — a warning block
  - `sh` — `printf antes > avisado.txt`
  - `writeFile` — `file = "avisado.txt", text = "café", encoding = "UTF-8"`
  - `sh` — `"od -An -tx1 -N5 avisado.txt | tr -d \" \\n\" | grep -qx 636166c3a9 && echo fichero-avi...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `unstable` |
| stdout contains | `fichero-avisado` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/control-flow/writefile-in-warnerror/writefile-in-warnerror.pipeline.kts
pipelinek run      examples/catalogue/features/control-flow/writefile-in-warnerror/writefile-in-warnerror.pipeline.kts
```

## Its lineage

- **Historical name:** `13-writefile-in-warnerror.pipeline.kts`
- **Origin path:** `examples/control-flow/13-writefile-in-warnerror.pipeline.kts`
- **Body digest:** `sha256:7ab3ff0d4c00ceccc18c800dbe2877dcfb3f5f5b9bd250c39b21157ff18aa52d`
