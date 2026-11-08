# Failure Stage

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `events`

**Expected outcome:** `failure`

## What it exercises

The event lane: what a run tells a consumer, and in what order

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `first`

- `sh` — `echo 'this one works'`

### 2. `second`

- `sh` — `exit 4`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |

## What it needs

- failure-stage

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/events/failure-stage/failure-stage.pipeline.kts
pipelinek run      examples/catalogue/features/events/failure-stage/failure-stage.pipeline.kts
```

## Its lineage

- **Historical name:** `04-failure-stage.pipeline.kts`
- **Origin path:** `examples/events/04-failure-stage.pipeline.kts`
- **Body digest:** `sha256:88ae5cef033e3f3f8b52b82db03202e334aa4bb40149543563356dc2fbedbcfb`
