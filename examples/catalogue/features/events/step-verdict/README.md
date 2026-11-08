# Step Verdict

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `events`

**Expected outcome:** `failure`

## What it exercises

The event lane: what a run tells a consumer, and in what order

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `verdict`

- `sh` — `echo 'this one works'`
- `sh` — `echo 'this one refuses' && exit 3`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |

## What it needs

- step-verdict

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/events/step-verdict/step-verdict.pipeline.kts
pipelinek run      examples/catalogue/features/events/step-verdict/step-verdict.pipeline.kts
```

## Its lineage

- **Historical name:** `02-step-verdict.pipeline.kts`
- **Origin path:** `examples/events/02-step-verdict.pipeline.kts`
- **Body digest:** `sha256:c7bdd4a59e300fa6f3480660ed38df46cf374403be597ab705c19fe25af53e2a`
