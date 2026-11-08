# Plugin Capability Lies

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The subject is a plugin that claims to need something its own code never asks for.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for`, `capabilities that differ from the contract`, `example.uppercase.ghostCapability` |

## What it needs

- distribution

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/plugin-capability-lies/plugin-capability-lies.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-capability-lies/plugin-capability-lies.pipeline.kts
```

## Its lineage

- **Historical name:** `plugin-capability-lies.pipeline.kts`
- **Origin path:** `examples/negative/plugin-capability-lies.pipeline.kts`
- **Body digest:** `sha256:a2d04759c8825f9417a8f69c7b5887b7f96f7cc8271fbc8f74dfb71edfe60aaa`
- **Origin digest:** `sha256:d3509fd513b0bd68343be6b72c12567a6c0f07992e0b5d7df3a5dfcf70510667`
