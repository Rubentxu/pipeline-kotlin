# Writefile In Catcherror

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `control-flow`

**Expected outcome:** `success`

## What it exercises

Which arm of a branch runs, and how many times the body of a loop runs

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `writefile-en-catcherror`

- `catchError` — a block of recovery steps
  - `sh` — `printf antes > capturado.txt`
  - `writeFile` — `file = "capturado.txt", text = "café", encoding = "UTF-8"`
  - `sh` — `"od -An -tx1 -N5 capturado.txt | tr -d \" \\n\" | grep -qx 636166c3a9 && echo fichero-c...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `fichero-capturado` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/control-flow/writefile-in-catcherror/writefile-in-catcherror.pipeline.kts
pipelinek run      examples/catalogue/features/control-flow/writefile-in-catcherror/writefile-in-catcherror.pipeline.kts
```

## Its lineage

- **Historical name:** `12-writefile-in-catcherror.pipeline.kts`
- **Origin path:** `examples/control-flow/12-writefile-in-catcherror.pipeline.kts`
- **Body digest:** `sha256:d00511c3614d6cb24517bfab56adcba17a3292439f532ff9b2d8883ed4b4610b`
- **Origin digest:** `sha256:5dcff692a9f5aa780c916193785242004b7f36d7a54f2070db5c147880b9c5af`
