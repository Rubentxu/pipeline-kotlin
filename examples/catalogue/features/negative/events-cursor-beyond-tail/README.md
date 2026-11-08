# Events Cursor Beyond Tail

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
| stderr contains | `Error: cursor`, `past the tail` |

## What it needs

- durable-once
- events-refusal

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/events-cursor-beyond-tail/events-cursor-beyond-tail.pipeline.kts
pipelinek run      examples/catalogue/features/negative/events-cursor-beyond-tail/events-cursor-beyond-tail.pipeline.kts
```

## Its lineage

- **Historical name:** `events-cursor-beyond-tail.pipeline.kts`
- **Origin path:** `examples/negative/events-cursor-beyond-tail.pipeline.kts`
- **Body digest:** `sha256:7d529348274391fdcc084cc8dd398522b6c0ef4bfc61d5a0d3df26ddb0209eea`
- **Origin digest:** `sha256:9eb761a6b74a6a13fec5bc1c1bbeebaf75550dac388cac19fba63b5e2786ecdf`
