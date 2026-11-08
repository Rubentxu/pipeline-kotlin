# Repeat Block

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `plugins`

**Expected outcome:** `success`

## What it exercises

The steps that come from a plugin, and the capabilities they declare

*The example says this about itself:*

> The capability under trial is the REPEAT COUNT, and a count needs no store:
> how many times the body ran is decided by the block, and the file it wrote
> is the witness. The directive says so, and the launcher refuses this
> scenario if it ever needs the store to keep its promise.

## The stages and the steps

### 1. `Repeat`

- `repeatBlock(2)` — a repeated block (`example-block-plugin`)
  - `sh` — `echo iteration | tee -a iterations.txt`
- `sh` — `"test \"$(wc -l < iterations.txt)\" -eq 2"`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `iteration` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/plugins/repeat-block/repeat-block.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/repeat-block/repeat-block.pipeline.kts
```

## Its lineage

- **Historical name:** `repeat-block.pipeline.kts`
- **Origin path:** `examples/plugins/repeat-block.pipeline.kts`
- **Body digest:** `sha256:e2842b12471e5b274ced9cfb17b5573e4b28920f086e6b2ce769072be16e4961`
- **Origin digest:** `sha256:a95f79824f2b4fe5efe29bbbe1523afb2f85053a543f331c6c937928beda3a25`
