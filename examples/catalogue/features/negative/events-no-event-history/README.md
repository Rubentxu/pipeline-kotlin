# Events No Event History

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
| stderr contains | `Error: db has no event history` |

## What it needs

- durable-once
- events-refusal

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/events-no-event-history/events-no-event-history.pipeline.kts
pipelinek run      examples/catalogue/features/negative/events-no-event-history/events-no-event-history.pipeline.kts
```

## Its lineage

- **Historical name:** `events-no-event-history.pipeline.kts`
- **Origin path:** `examples/negative/events-no-event-history.pipeline.kts`
- **Body digest:** `sha256:c5b60d1550acbf9bf02225f93c33212cc37ab4c6c2ea93c591a007fe397beaac`
- **Origin digest:** `sha256:cc4d4dff57266a77ec0423dc8edf78cfb9201bd4ad513f8ed334be4e6cb8ca3c`
