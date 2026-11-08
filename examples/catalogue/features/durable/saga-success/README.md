# Saga Success

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `success`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> A Saga CHAIN that SUCCEEDS, in a real process — and in BOTH storage worlds.

## The stages and the steps

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — a step this plugin provides (`example-saga-plugin`)
- `post` — the block of steps that run after the stage
  - `success` — a block
    - `sh` — `"test \"$(cat '$home/effects.log')\" = " + "\"$(printf 'reserve\\ncharge')\" " + "|| {...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stderr contains | `saga [checkout]: SETTLED` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/saga-success/saga-success.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-success/saga-success.pipeline.kts
```

## Its lineage

- **Historical name:** `38-saga-success.pipeline.kts`
- **Origin path:** `examples/durable/38-saga-success.pipeline.kts`
- **Body digest:** `sha256:8cd7c8ea2b6654d889fd27533baca458cc47231be66e73b3d24b1bac52d31a3c`
- **Origin digest:** `sha256:e2d819950d40d4f904c4c49f96e854369af28e190f934cabb6595ead4ef7a798`
