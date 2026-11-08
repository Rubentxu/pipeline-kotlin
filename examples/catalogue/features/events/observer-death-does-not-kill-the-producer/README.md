# Observer Death Does Not Kill The Producer

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `events`

**Expected outcome:** `success`

## What it exercises

The event lane: what a run tells a consumer, and in what order

*The example says this about itself:*

> 08-observer-death-does-not-kill-the-producer.pipeline.kts — the observer dies; the run does not.

## The stages and the steps

### 1. `observe`

- `sh` — `""" setsid env -u PIPELINE_OP_COOKIE -u DURABLE_SH_COOKIE -u DURABLE_SH_OPID '${product...`
- `sh` — `""" observer_pids() { local record cmd pid for record in ${'$'}(command grep -l -z -F -...`
- `sh` — `echo 'the producer survived the death of its observer'`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- durable-once
- run-state

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/events/observer-death-does-not-kill-the-producer/observer-death-does-not-kill-the-producer.pipeline.kts
pipelinek run      examples/catalogue/features/events/observer-death-does-not-kill-the-producer/observer-death-does-not-kill-the-producer.pipeline.kts
```

## Its lineage

- **Historical name:** `08-observer-death-does-not-kill-the-producer.pipeline.kts`
- **Origin path:** `examples/events/08-observer-death-does-not-kill-the-producer.pipeline.kts`
- **Body digest:** `sha256:90ca2ffb2c248ac60d0a21dea71fde3f97301e38f142812fb3f82034ce372827`
- **Origin digest:** `sha256:c8eed8204ebef660eb385ac93cd3ee8918f9b8907ef7d41f505403cfd92b0968`
