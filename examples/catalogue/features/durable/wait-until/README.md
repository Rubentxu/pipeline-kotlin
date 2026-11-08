# Wait Until

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `success`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> 22-wait-until.pipeline.kts — installed-CLI canary
> Real .pipeline.kts using the structural waitUntil block through the canonical
> dispatch path (BlockStepNode + BodyExecutionPolicy.RepeatUntil + dispatchRepeatUntilBody).
> Validates that after the registry removal and the durable journal, the DSL
> 'waitUntil(initialRecurrencePeriod) { body }' produces a correct durable trace with
> WaitUntilPolled (≥1) + WaitUntilCompleted(origin=canonical).
> '//@ durable' makes the launcher run this pipeline twice against one control
> root. That is the property this scenario can actually speak for and no other
> can: a body that already reached WaitUntilCompleted must not be polled again
> on the replay, and must not fail for having done nothing new. A single run
> cannot observe it, which is why the replay branch of the launcher existed
> without ever being reached.
> The marker used to be the absolute path /tmp/marker-wu-g5-restore. The
> comment above it claimed the path was "unique to this corpus to avoid
> collision with other test runs", which was the opposite of what the code did:
> a fixed name in a shared directory is precisely what two concurrent runs
> collide on. It is now relative, so it resolves inside this stage's own
> workspace and the claim is true.

## The stages and the steps

### 1. `wait-until`

- `sh` — `touch marker-wu-g5-restore`
- `waitUntil(initialRecurrencePeriod = 100L)` — a wait block
  - `sh` — `test -f marker-wu-g5-restore && echo READY`
- `sh` — `rm marker-wu-g5-restore`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- durable

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/wait-until/wait-until.pipeline.kts
pipelinek run      examples/catalogue/features/durable/wait-until/wait-until.pipeline.kts
```

## Its lineage

- **Historical name:** `22-wait-until.pipeline.kts`
- **Origin path:** `examples/durable/22-wait-until.pipeline.kts`
- **Body digest:** `sha256:72fe1a49b698d95ad3b0aa647ae0b2eb7cc7f072969ed3c3afb4767a34a4b783`
- **Origin digest:** `sha256:e231d4c32d5ae505da258b631936215e2d133d2f292980e1367a7ae5c141a3e6`
