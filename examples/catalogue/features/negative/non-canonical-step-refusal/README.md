# Non Canonical Step Refusal

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `never-runs`

- `registryStep` — `PluginStepId("custom.notCanonical"), EncodedStepValue("{}")`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `custom.notCanonical` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/non-canonical-step-refusal/non-canonical-step-refusal.pipeline.kts
pipelinek run      examples/catalogue/features/negative/non-canonical-step-refusal/non-canonical-step-refusal.pipeline.kts
```

## Its lineage

- **Historical name:** `non-canonical-step-refusal.pipeline.kts`
- **Origin path:** `examples/negative/non-canonical-step-refusal.pipeline.kts`
- **Body digest:** `sha256:7403f7f91be9ba28ccac778e2cf4d45b37b5c2ec69c7897bd2f0f173d02d27ec`
