# Saga Needs Intervention

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `failure`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> The SAME chain as the scenario before it, with ONE thing changed: the provider cannot
> promise that a failed call left nothing behind.

## The stages and the steps

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", // THE ONE DIFFERENCE. The provider cannot promise that a failed c...` — a step this plugin provides (`example-saga-plugin`)
- `post` — the block of steps that run after the stage
  - `always` — the block of steps that always run
    - `sh` — `"test \"$(cat '$home/effects.log')\" = \"$(printf 'reserve\\ncharge')\" " + "|| { echo...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `the action ship failed`, `saga [checkout]: NEEDS_INTERVENTION (UNKNOWN_EFFECT)` |

## What it needs

- durable-once

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/saga-needs-intervention/saga-needs-intervention.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-needs-intervention/saga-needs-intervention.pipeline.kts
```

## Its lineage

- **Historical name:** `34-saga-needs-intervention.pipeline.kts`
- **Origin path:** `examples/durable/34-saga-needs-intervention.pipeline.kts`
- **Body digest:** `sha256:165a16a2d7d06723dac0540f42edaf2ad7c01bc50bff6955e896e3f7344a5d1b`
