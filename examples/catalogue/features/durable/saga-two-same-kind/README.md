# Saga Two Same Kind

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `failure`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

## The stages and the steps

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — a step this plugin provides (`example-saga-plugin`)
- `post` — the block of steps that run after the stage
  - `always` — the block of steps that always run
    - `sh` — `"test \"$(cat '$home/effects.log')\" = " + "\"$(printf 'example.saga.reserve.forward\\n...`

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
pipelinek validate examples/catalogue/features/durable/saga-two-same-kind/saga-two-same-kind.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-two-same-kind/saga-two-same-kind.pipeline.kts
```

## Its lineage

- **Historical name:** `40-saga-two-same-kind.pipeline.kts`
- **Origin path:** `examples/durable/40-saga-two-same-kind.pipeline.kts`
- **Body digest:** `sha256:b9204ee7c3320ee3f5012ec888488f2d1fa25e5239debfa054eab2639f71a311`
- **Origin digest:** `sha256:771fe7cd67162b21461922bc5f770f1306246e38561451ef8149a1266c64de6d`
