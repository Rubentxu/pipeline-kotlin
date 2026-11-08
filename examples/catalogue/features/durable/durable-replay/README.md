# Durable Replay

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `success`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> 32-durable-replay.pipeline.kts — the ONE real replay guarantee.

## The stages and the steps

### 1. `replay`

- `sh` — `printf 'effect\\n' >> '$marker'`
- `sh` — `"test \"\$(wc -l < '$marker')\" -eq 1 && echo 'effects=1'"`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `effects=1` |

## What it needs

- durable

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/durable-replay/durable-replay.pipeline.kts
pipelinek run      examples/catalogue/features/durable/durable-replay/durable-replay.pipeline.kts
```

## Its lineage

- **Historical name:** `32-durable-replay.pipeline.kts`
- **Origin path:** `examples/durable/32-durable-replay.pipeline.kts`
- **Body digest:** `sha256:e171865d064a94a5f3dad196eab96bcadfeb867fc874b2f4dd246dd5f776c476`
