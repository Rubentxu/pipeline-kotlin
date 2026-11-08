# Saga Reconcile

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `failure`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> A Saga whose COMPENSATION happened, lost its answer, and was RECONCILED.

## The stages and the steps

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", // The provider RECOGNISES ITS OWN KEYS, so the engine may ask it...` — a step this plugin provides (`example-saga-plugin`)
- `post` — the block of steps that run after the stage
  - `always` — the block of steps that always run
    - `sh` — `"test \"\$(grep -c '^reserve:' '$home/effects.log')\" = 1 " + "&& test \"\$(grep -c '^r...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `the write-ahead for reserve was refused`, `saga [checkout]: SETTLED` |

## What it needs

- resume

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/saga-reconcile/saga-reconcile.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-reconcile/saga-reconcile.pipeline.kts
```

## Its lineage

- **Historical name:** `37-saga-reconcile.pipeline.kts`
- **Origin path:** `examples/durable/37-saga-reconcile.pipeline.kts`
- **Body digest:** `sha256:4de15cc45ebe71074cfc6b5e2cbe99ab47074b0e9249528ee7d81cc316400901`
