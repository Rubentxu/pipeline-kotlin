# Saga Crash Compensation

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `failure`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> A Saga whose COMPENSATION happened and whose answer was LOST.

## The stages and the steps

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — a step this plugin provides (`example-saga-plugin`)
- `post` — the block of steps that run after the stage
  - `always` — the block of steps that always run
    - `sh` — `"test \"$(cat '$home/effects.log')\" = " + "\"$(printf 'reserve\\ncharge\\nrefund')\" "...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `the write-ahead for reserve was refused`, `saga [checkout]: NEEDS_INTERVENTION (COMPENSATION_UNCERTAIN)` |

## What it needs

- resume

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/saga-crash-compensation/saga-crash-compensation.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-crash-compensation/saga-crash-compensation.pipeline.kts
```

## Its lineage

- **Historical name:** `36-saga-crash-compensation.pipeline.kts`
- **Origin path:** `examples/durable/36-saga-crash-compensation.pipeline.kts`
- **Body digest:** `sha256:b3da7fbf41a4be0918a91b1c295225e5c2247cf7b692ce16e1cff14d28410e79`
- **Origin digest:** `sha256:b8a9ee9b8e549d1f9bb3d9a5136c4673d8ce56d008d3d16b104c0caa54539f21`
