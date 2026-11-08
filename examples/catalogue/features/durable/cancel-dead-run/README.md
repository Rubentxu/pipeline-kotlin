# Cancel Dead Run

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `success`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> 39-cancel-dead-run.pipeline.kts — closing a run whose writer was killed.

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
| stdout contains | `cancelled run` |

## What it needs

- durable-once
- cancel

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/cancel-dead-run/cancel-dead-run.pipeline.kts
pipelinek run      examples/catalogue/features/durable/cancel-dead-run/cancel-dead-run.pipeline.kts
```

## Its lineage

- **Historical name:** `39-cancel-dead-run.pipeline.kts`
- **Origin path:** `examples/durable/39-cancel-dead-run.pipeline.kts`
- **Body digest:** `sha256:1786c3cd8e205b5a060d35589fd5bdda041fa4d3720cf28cee4d4396d3598484`
- **Origin digest:** `sha256:f706b4d76466779a4422b40d9b85fa9350728e4d5f002341ba06e81071d86ffd`
