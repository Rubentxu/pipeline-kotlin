# Saga Compensation

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `failure`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> A Saga CHAIN, compensated by the engine, in a real process with a real SQLite.

## The stages and the steps

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — a step this plugin provides (`example-saga-plugin`)
- `post` — the block of steps that run after the stage
  - `always` — the block of steps that always run
    - `sh` — `"test \"$(cat '$home/effects.log')\" = " + "\"$(printf 'reserve\\ncharge\\nrefund\\nrel...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `the action ship failed`, `saga [checkout]: SETTLED` |

## What it needs

- durable-once

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/saga-compensation/saga-compensation.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-compensation/saga-compensation.pipeline.kts
```

## Its lineage

- **Historical name:** `33-saga-compensation.pipeline.kts`
- **Origin path:** `examples/durable/33-saga-compensation.pipeline.kts`
- **Body digest:** `sha256:be3347a4c002bcad25c1d49aca195c07708face5dcab946540b52337250c93d3`
- **Origin digest:** `sha256:7dc749c0fbe6c9544aebc2f4d44fc3f60a77fbd4d2b6b4cc5e62bff5b4302e06`
