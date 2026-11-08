# Events Unknown Run

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `Fixture`

- `echo` — `this run exists, and is what the refusal is asked about`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Error: unknown run` |

## What it needs

- durable-once
- events-refusal

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/events-unknown-run/events-unknown-run.pipeline.kts
pipelinek run      examples/catalogue/features/negative/events-unknown-run/events-unknown-run.pipeline.kts
```

## Its lineage

- **Historical name:** `events-unknown-run.pipeline.kts`
- **Origin path:** `examples/negative/events-unknown-run.pipeline.kts`
- **Body digest:** `sha256:0dac869feb13165cb131daad644b645192285ef85182f5e816e06e375736e30b`
- **Origin digest:** `sha256:0058078de8a8c94564d4e255d440d9789a18d838966651ffc7719adf2f93c5d3`
