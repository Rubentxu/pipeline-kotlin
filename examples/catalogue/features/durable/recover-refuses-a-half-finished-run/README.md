# Recover Refuses A Half Finished Run

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `success`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> 41-recover-refuses-a-half-finished-run.pipeline.kts — recovering a run that did not finish.

## The stages and the steps

### 1. `work`

- `sh` — `echo 'the-step-that-finished'`
- `sh` — `kill -9 \$(cat '$pidFile')`
- `sh` — `echo 'never-reached'`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `nothing was recovered` |

## What it needs

- durable-once
- recover

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/recover-refuses-a-half-finished-run/recover-refuses-a-half-finished-run.pipeline.kts
pipelinek run      examples/catalogue/features/durable/recover-refuses-a-half-finished-run/recover-refuses-a-half-finished-run.pipeline.kts
```

## Its lineage

- **Historical name:** `41-recover-refuses-a-half-finished-run.pipeline.kts`
- **Origin path:** `examples/durable/41-recover-refuses-a-half-finished-run.pipeline.kts`
- **Body digest:** `sha256:2a30b1ef9dff368d3e3184876aa3058daa740f9afc0e31dbe57c17ea84840e97`
- **Origin digest:** `sha256:aca7412a0f64d53b82439e56ac31c73bac9036c732ca4d7f2dbf7251ce7515ba`
