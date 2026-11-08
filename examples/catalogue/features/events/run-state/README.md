# Run State

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `events`

**Expected outcome:** `success`

## What it exercises

The event lane: what a run tells a consumer, and in what order

*The example says this about itself:*

> 07-run-state.pipeline.kts — what the product says about a run that is over.

## The stages and the steps

### 1. `work`

- `sh` — `echo 'the-step-that-finished'`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- run-state

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/events/run-state/run-state.pipeline.kts
pipelinek run      examples/catalogue/features/events/run-state/run-state.pipeline.kts
```

## Its lineage

- **Historical name:** `07-run-state.pipeline.kts`
- **Origin path:** `examples/events/07-run-state.pipeline.kts`
- **Body digest:** `sha256:8b4a7ef4313fcbb2d07c9c32bad6f1a9d9d2559bb4be083b2f4b815133c9575d`
- **Origin digest:** `sha256:1a2a78ab298d2d1faa2ea86ecbb2dc39409222158838572a234efe20334145f9`
