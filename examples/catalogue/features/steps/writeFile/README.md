# Writefile

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

*The example says this about itself:*

> The capability under trial is the WRITE, and a write needs no store: what
> lands on disk and what the next step reads back are decided by the
> filesystem. The directive says so, and the launcher refuses this scenario if
> it ever needs the store to keep its promise.

## The stages and the steps

### 1. `writefile-step`

- `writeFile` — `file = "out.txt", text = "hello"`
- `sh` — `grep -qx hello out.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/writeFile/writeFile.pipeline.kts
pipelinek run      examples/catalogue/features/steps/writeFile/writeFile.pipeline.kts
```

## Its lineage

- **Historical name:** `17-writeFile.pipeline.kts`
- **Origin path:** `examples/steps/17-writeFile.pipeline.kts`
- **Body digest:** `sha256:774e8e2f9950da837ed013d1170ac2980a55f5e9286b6be00218fed5b7b5cfc4`
- **Origin digest:** `sha256:4ab4a34ad4476a1d2164d00fe9db1b23cad2ac87ffbe389718c6467ccdaec456`
