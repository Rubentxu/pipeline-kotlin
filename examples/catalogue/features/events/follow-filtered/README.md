# Follow Filtered

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `events`

**Expected outcome:** `success`

## What it exercises

The event lane: what a run tells a consumer, and in what order

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `first`

- `sh` — `echo 'the step that ends the run'`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- events-follow

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/events/follow-filtered/follow-filtered.pipeline.kts
pipelinek run      examples/catalogue/features/events/follow-filtered/follow-filtered.pipeline.kts
```

## Its lineage

- **Historical name:** `06-follow-filtered.pipeline.kts`
- **Origin path:** `examples/events/06-follow-filtered.pipeline.kts`
- **Body digest:** `sha256:c94ec18c0c22a92c37ceef7b2f3f474702d7b193d24023d8897353827ace9f66`
- **Origin digest:** `sha256:19d94d77f631051a6958d0db8685cb560cb25cd035c70d13997c2dd846fc36aa`
