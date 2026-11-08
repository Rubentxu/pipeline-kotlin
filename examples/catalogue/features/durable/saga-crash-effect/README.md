# Saga Crash Effect

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `failure`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> A Saga whose forward effect HAPPENED and whose answer was LOST.

## The stages and the steps

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — a step this plugin provides (`example-saga-plugin`)
- `post` — the block of steps that run after the stage
  - `always` — the block of steps that always run
    - `sh` — `"test \"$(cat '$home/effects.log')\" = \"reserve\" " + "|| { echo 'SAGA-REPEATED-OR-MAD...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `the write-ahead for reserve was refused`, `saga [checkout]: NEEDS_INTERVENTION (UNKNOWN_EFFECT)` |

## What it needs

- resume

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/saga-crash-effect/saga-crash-effect.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-crash-effect/saga-crash-effect.pipeline.kts
```

## Its lineage

- **Historical name:** `35-saga-crash-effect.pipeline.kts`
- **Origin path:** `examples/durable/35-saga-crash-effect.pipeline.kts`
- **Body digest:** `sha256:fea0fce3935f2318739e675d30b7a29178c052ba0f8a6328a631ed87620986e0`
- **Origin digest:** `sha256:ac545c34e5a5cd20f71afff9fbda0b8fd8cb7a7aea5c8688c706e155b01ec314`
