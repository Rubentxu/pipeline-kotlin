# Wait Until Deadline

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `failure`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> 23-wait-until-deadline.pipeline.kts — a waitUntil whose condition never holds ENDS.

## The stages and the steps

### 1. `wait-until-deadline`

- `waitUntil(initialRecurrencePeriod = 50L, maxBackoffMs = 200L)` — a wait block
  - `sh` — `false`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `Pipeline finished with FAILURE`, `cause [TIMEOUT]: waitUntil deadline exceeded at poll 3 (200ms backoff ceiling)` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/wait-until-deadline/wait-until-deadline.pipeline.kts
pipelinek run      examples/catalogue/features/durable/wait-until-deadline/wait-until-deadline.pipeline.kts
```

## Its lineage

- **Historical name:** `23-wait-until-deadline.pipeline.kts`
- **Origin path:** `examples/durable/23-wait-until-deadline.pipeline.kts`
- **Body digest:** `sha256:e76d593baa47148f5cd6eb6d090694fcafa66b6cc9b10d1b9d4ab59628ec2416`
- **Origin digest:** `sha256:887f73a035fa30f48cc4c62a587988d85f29bf277d62d9bddf67370b9253b014`
